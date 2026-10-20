package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.mesh.BundleCodec
import com.codeyantra.armorchat.core.mesh.BundleDecode
import com.codeyantra.armorchat.core.mesh.CustodyBundle
import com.codeyantra.armorchat.core.mesh.MeshAdmission
import com.codeyantra.armorchat.core.mesh.MeshRejection
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshDeliveredStore
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.MeshIds
import com.codeyantra.armorchat.core.mesh.MeshPolicy
import com.codeyantra.armorchat.core.mesh.MeshHoldResult
import com.codeyantra.armorchat.core.mesh.RadioPath
import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.ImmutableRelayEnvelope
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.core.network.RelaySubmitResult
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.core.network.ServicePolicyTrust
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.domain.model.MessageType
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
/**
 * Mesh-1K-AQ: deterministic three-node JVM simulation  A (sender) <-> B (untrusted relay) <-> C (receiver).
 *
 * Topology law: the only simulated links are A-B and B-C (plus J-B for a junk/upstream injector that
 * talks only to B). [encounter] and [push] refuse any other pair and every encounter is logged, so
 * "no direct A-C link" is asserted, not assumed.
 *
 * REAL production code: the same set as MeshReceiptAckTest (the A/C [Phone] wiring is copied verbatim
 * from it): EmergencyMeshEngine + CustodyLedger on FILE-BACKED Room mesh_custody (holdSealed, phaseFor,
 * offers, ingest, markForwarded, dropDelivered, ownHeld, releaseOwnCustody, recordDelivered, sweep,
 * arm() -> restore()), BundleCodec, MeshFrames, MeshCourier.ingest (exact EmergencyMeshService.ingest
 * code), MeshDelivery.deliver / deliverDurable, MeshDeliveredMarker, MeshReceiptAck, MeshInboxCommit,
 * SessionOrchestrator.sendText/offerMesh/persist/restore, ReceiptMessagingPeer (processLeased ->
 * bindInbound/previewReceipt/applyReceipt, sealDeliveryReceipt). B is a phone with an EmergencyMeshEngine
 * and a SessionOrchestrator that was NEVER provisioned: it has no identity binding, no session, no MLS
 * state, so MeshDelivery.deliver -> offerMesh can only refuse; B keeps foreign custody.
 *
 * SIMULATED / MOCKED (stated explicitly, no authenticity is claimed from these):
 *  - MLS is FakeMls (via CountingMls). Its ciphertext is NOT authenticated and replays are NOT rejected by
 *    MLS. "Authenticated receipt" here means: the receipt is the real ReceiptMessagingPeer DELIVERY record,
 *    sealed through the real C-side session and accepted by A only after the real inner binding checks
 *    (digest, epoch, recipient, sender, session, target). Sender/cryptographic authenticity rests on native
 *    mls-rs and is NOT proven by this file.
 *  - Identity keys are real Tink Ed25519 (TinkSigner) as in the other messaging tests.
 *  - A-C pairing / MLS establishment uses InMemoryRelayNetwork BEFORE the scenario (as two real phones
 *    pair while online); afterwards A's relay submit is forced Unavailable, so messages move only over mesh.
 *  - ChatMessagingService is not constructible on the JVM: [Phone.deliver]/[Phone.sendOffline] mirror
 *    deliverMesh / sendText's mesh branch (copied from MeshReceiptAckTest). CMS:1094-1105 (sendText relay
 *    retry status write) is NOT exercised here.
 *  - BLE/GATT radio is not used: one encounter = phaseFor -> MeshFrames round trip -> offers ->
 *    markForwarded -> MeshCourier.ingest on the other side (EmergencyMeshService.exchange order).
 *    MeshCourier.ingest is called without addressedHere/deliverDirect (as MeshReceiptAckTest does).
 *  - "Restart" = close + reopen the Room file, fresh engine (arm -> restore). Not process death.
 *
 * _KNOWN_GAP remains only on relayCanRewriteHopCountAndEmergencyFlag: a relay can still rewrite
 * hopCount and the emergency bit. as02b keeps its _DEFECT name; Mesh-1K-AU makes that delivery
 * assertion pass. junkFlood no longer uses _KNOWN_GAP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshThreeNodeSimulationTest {

    @get:Rule val tmp = TemporaryFolder()

    private class TinkSigner : IdentitySigner {
        private val handle: KeysetHandle
        override val pin: String

        init {
            SignatureConfig.register()
            handle = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            val pub = (handle.publicKeysetHandle.primary.key as Ed25519PublicKey).publicKeyBytes.toByteArray()
            pin = Base64Url.encode(pub)
        }

        override fun sign(context: String, bodyCanonical: ByteArray): ByteArray {
            require(context in ArmorChatContexts.allowed)
            return RawIdentitySignature.sign(handle, bodyCanonical)
        }
    }

    private class CountingMls {
        val decrypts: MutableMap<String, Int> = Collections.synchronizedMap(mutableMapOf())
        fun transact(state: ByteArray, request: String): ByteArray {
            val req = CanonicalJson.parseObject(request)
            if (req["op"] == "Decrypt") {
                val ct = req["ciphertext"] as String
                synchronized(decrypts) { decrypts[ct] = (decrypts[ct] ?: 0) + 1 }
            }
            return FakeMls.transact(state, request)
        }
    }

    private class FaultIo : MeshDeliveredStore.Io {
        var failWrite = false
        override fun read(file: File): ByteArray? = MeshDeliveredStore.FileIo.read(file)
        override fun writeAtomic(file: File, bytes: ByteArray) {
            if (failWrite) throw IOException("simulated marker write failure")
            MeshDeliveredStore.FileIo.writeAtomic(file, bytes)
        }
        override fun rename(file: File, target: File): Boolean = MeshDeliveredStore.FileIo.rename(file, target)
    }

    private var nowMs = 1_800_000_000_000L
    private val clock = AppClock { nowMs }
    private val origin = "https://relay-mesh-1ko.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-1ko", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val app get() = RuntimeEnvironment.getApplication()
    private val openDbs = mutableListOf<ArmorChatDatabase>()

    private fun newOrch(mls: (ByteArray, String) -> ByteArray) = SessionOrchestrator(
        registry = registry,
        provider = provider,
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = MessagingLinkFactory { signer, relay, c, engine ->
            val peer = TwoPhonePeer(signer, relay, c, mls, engine)
            LiveLink(peer, ReceiptMessagingPeer(peer, c, jitter = { 0 }))
        },
        nowSeconds = { nowMs / 1000 },
    )

    @After
    fun close() {
        openDbs.forEach { runCatching { it.close() } }
    }

    private inner class Phone(val name: String, val title: String) {
        val signer = TinkSigner()
        val pin get() = signer.pin
        val mls = CountingMls()
        val contactId = "contact-of-$name"
        val conversationId = "conversation-of-$name"
        val markerIo = FaultIo()
        val lock = Mutex()
        lateinit var peer: Phone
        var db: ArmorChatDatabase = openDb()
        var orch: SessionOrchestrator = newOrch(mls::transact)
        var engine: EmergencyMeshEngine = newEngine()
        var savedMailbox: com.codeyantra.armorchat.core.network.MailboxRegistration? = null

        // P3 switches (legacy simulation) and fault injection.
        var p3Sender = true
        var p3Receiver = true
        var failSessionUpsert = false
        var failRelease = false
        var failReceiptPersist = false
        var failHold = false
        val events: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val outcomes: MutableList<MeshDelivery.Outcome> = Collections.synchronizedList(mutableListOf())
        val notifications: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var lastSeal: MeshReceiptAck.Sealed? = null
        var lastReceiptMarker: Boolean? = null
        var lastApplied: List<MeshInboxCommit.Release> = emptyList()

        fun openDb(): ArmorChatDatabase =
            Room.databaseBuilder(app, ArmorChatDatabase::class.java, File(tmp.root, "$name.db").absolutePath)
                .allowMainThreadQueries().build().also { openDbs += it }

        fun newEngine() = EmergencyMeshEngine(app, db.meshCustodyDao(), clock).also {
            it.delivered = MeshDeliveredStore(File(tmp.root, "$name.markers"), markerIo)
        }

        fun link() = orch.link(peer.pin)!!
        fun receipts() = link().receipts

        fun sessionRow(saved: PersistedLink) = ContactSessionEntity(
            contactId = contactId,
            peerPin = saved.peerPin,
            relayOrigin = saved.relayOrigin,
            mailboxId = saved.mailbox?.mailboxId,
            encryptedOwnerToken = saved.mailbox?.ownerToken?.toByteArray(),
            encryptedSubmitCapability = saved.mailbox?.submitCapability?.toByteArray(),
            deviceId = saved.deviceId,
            binding = saved.binding,
            localRouteQr = saved.localRouteQr,
            peerRouteQr = saved.peerRouteQr,
            mlsSnapshot = saved.mlsSnapshot,
            groupCreated = saved.groupCreated,
            state = saved.state.name,
            updatedAtEpochMs = nowMs,
            mlsSessionId = saved.sessionId,
        )

        suspend fun persistSession() {
            db.contactSessionDao().upsert(sessionRow(orch.persist(peer.pin) ?: error("mesh session missing")))
        }

        suspend fun load() {
            if (orch.link(peer.pin) != null) return
            val row = db.contactSessionDao().find(contactId) ?: return
            orch.restore(
                signer,
                PersistedLink(
                    peerPin = row.peerPin,
                    relayOrigin = row.relayOrigin,
                    mailbox = savedMailbox,
                    deviceId = row.deviceId ?: "",
                    binding = row.binding,
                    localRouteQr = row.localRouteQr,
                    peerRouteQr = row.peerRouteQr,
                    mlsSnapshot = row.mlsSnapshot ?: ByteArray(0),
                    groupCreated = row.groupCreated,
                    state = SecureSessionState.valueOf(row.state),
                    sessionId = row.mlsSessionId,
                ),
                signer.pin,
            )
        }

        /** Restart: close + reopen the Room file, fresh orchestrator, engine (arm -> restore) and markers. */
        fun restart() = runBlocking {
            db.close()
            db = openDb()
            orch = newOrch(mls::transact)
            engine = newEngine()
            engine.arm()
            load()
        }

        /** Mirrors ChatMessagingService.sendText with the relay down and mesh armed. */
        suspend fun sendOffline(text: String): Sent = lock.withLock {
            load()
            receipts().submitInterceptor = { RelaySubmitResult.Unavailable }
            val record = orch.sendText(peer.pin, text)
            check(record.progress.status != MessageStatus.SENT)
            val canonical = record.envelopeCanonical!!
            engine.holdSealed(canonical.toByteArray(Charsets.UTF_8), emergency = true)
            db.messageDao().insert(
                MeshReceiptAck.sendBinding(
                    MessageEntity(
                        id = record.messageId,
                        conversationId = conversationId,
                        type = MessageType.TEXT.name,
                        isOutgoing = true,
                        localPlaintext = text,
                        ciphertext = record.ciphertextB64?.toByteArray(Charsets.UTF_8),
                        status = record.progress.status.name,
                        createdAtEpochMs = nowMs,
                    ),
                    record,
                ),
            )
            persistSession()
            Sent(record.messageId, canonical.toByteArray(Charsets.UTF_8), record.ciphertextSha256!!)
        }

        private suspend fun installTargets(): List<ReceiptMessagingPeer> {
            val own = engine.ownHeld()
            if (own.isEmpty()) return emptyList()
            val byPin = MeshReceiptAck.durableTargets(own, { db.messageDao().findById(it) }) { c ->
                if (c == conversationId) peer.pin else null
            }
            val touched = mutableListOf<ReceiptMessagingPeer>()
            for ((p, targets) in byPin) {
                val r = orch.link(p)?.receipts ?: continue
                r.verificationTargets = targets
                touched += r
            }
            return touched
        }

        // WIRING: mirrors ChatMessagingService.deliverMesh after Mesh-1K-O ----------------------------
        suspend fun deliver(payload: ByteArray): Boolean {
            val staged = mutableListOf<MeshInboxCommit.Staged>()
            var committed: List<MeshInboxCommit.Staged> = emptyList()
            var commitOutcome = MeshDelivery.Outcome.DELIVERED
            var finalOutcome: MeshDelivery.Outcome? = null
            val releases = mutableListOf<MeshInboxCommit.Release>()
            var applied: List<MeshInboxCommit.Release> = emptyList()
            val ok = MeshDelivery.deliverDurable(
                lock = lock,
                payload = payload,
                durable = {
                    MeshDeliveredMarker.durable(payload, { db.messageDao().findById(it) }) { bytes, id ->
                        engine.hasDeliveredMarker(bytes, id)
                    }
                },
                committedAs = { commitOutcome },
                report = { _, _, o ->
                    finalOutcome = o
                    outcomes += o
                },
                load = { load() },
                checkpoint = {
                    staged.clear()
                    releases.clear()
                    orch.meshCheckpoint()
                },
                rollback = {
                    staged.clear()
                    releases.clear()
                    orch.restoreMeshCheckpoint(it)
                },
                offer = { raw, cb ->
                    val installed = if (p3Sender) installTargets() else emptyList()
                    try {
                        orch.offerMesh(raw, cb)
                    } finally {
                        installed.forEach { it.verificationTargets = emptyMap() }
                    }
                },
                persistInbound = { p, inbound ->
                    if (p3Sender && inbound.kind == InnerKind.DELIVERY) {
                        val target = inbound.receiptTargetMessageId?.let { orch.link(p)?.receipts?.store?.text(it) }
                        MeshReceiptAck.stageRelease(p, conversationId, inbound, target, engine.ownHeld(), nowMs)
                            ?.let { releases += it }
                    }
                    MeshInboxCommit.inboundRow(conversationId, inbound, nowMs)?.let { e ->
                        staged += MeshInboxCommit.Staged(p, e, title) { notifications += e.id }
                    }
                },
                commit = { p ->
                    val session = sessionRow(orch.persist(p)!!)
                    val mine = releases.filter { it.peerPin == p }
                    val commit = MeshInboxCommit(
                        db,
                        beforeSessionUpsert = { if (failSessionUpsert) throw IOException("simulated contact_sessions failure") },
                        beforeRelease = { if (failRelease) throw IOException("simulated release failure") },
                    )
                    val result = if (mine.isEmpty()) {
                        commit.commit(staged.filter { it.peerPin == p }, session)
                    } else {
                        engine.releaseOwnCustody {
                            commit.commit(staged.filter { it.peerPin == p }, session, mine).let { it to it.releasedCustodyIds }
                        }
                    }
                    applied = result.released
                    committed = result.inserted
                    commitOutcome = result.outcome()
                },
                afterCommit = {
                    if (p3Receiver) {
                        lastSeal = MeshReceiptAck.sealAfterCommit(
                            targets = committed.map { it.peerPin to it.message.id },
                            checkpoint = { orch.meshCheckpoint() },
                            rollback = { orch.restoreMeshCheckpoint(it) },
                            seal = { p, id -> orch.link(p)?.receipts?.sealDeliveryReceipt(id) },
                            persist = { p ->
                                if (failReceiptPersist) throw IOException("simulated receipt MLS persist failure")
                                db.contactSessionDao().upsert(sessionRow(orch.persist(p) ?: error("mesh session missing")))
                                events += "persist"
                            },
                            hold = { bytes ->
                                if (failHold) throw IOException("simulated custody failure")
                                engine.holdSealed(bytes, emergency = false)
                                events += "hold"
                            },
                        )
                    }
                },
            )
            MeshDeliveredMarker.recordAfter(payload, ok, finalOutcome, committed, { db.messageDao().findById(it) }) { b, id, binding ->
                engine.recordDelivered(b, id, binding)
            }
            if (p3Sender) {
                lastReceiptMarker = MeshReceiptAck.recordReceiptMarker(payload, ok, finalOutcome, applied) { b, id, binding ->
                    engine.recordDelivered(b, id, binding)
                }
            }
            lastApplied = applied
            if (ok) MeshInboxCommit.runAfterCommit(committed)
            return ok
        }

        suspend fun status(id: String): String? = db.messageDao().findById(id)?.status
        suspend fun custodyRows() = db.meshCustodyDao().all()
        suspend fun ownCustody() = custodyRows().filter { it.hopCount == 0 }
        suspend fun inboundIds() = db.messageDao().observeAllMessages().first().filter { !it.isOutgoing }.map { it.id }
        fun receiptIdsInStore(): Set<String> =
            orch.link(peer.pin)?.receipts?.store?.receipts()?.map { it.messageId }?.toSet() ?: emptySet()
        fun advertises(payload: ByteArray): Boolean = runBlocking {
            engine.phaseFor(emptySet(), false).ids.any { it.contentEquals(MeshIds.idFor(payload)) }
        }
    }

    private data class Sent(val id: String, val payload: ByteArray, val digest: String)

    // ---------------------------------------------------------------------------------------------
    // Three-node additions (Mesh-1K-AQ)
    // ---------------------------------------------------------------------------------------------

    /** Untrusted relay phone: real engine + Room, an orchestrator with NO session, NO MLS, NO identity binding. */
    private inner class Carrier(val name: String) {
        val markerIo = FaultIo()
        var db: ArmorChatDatabase = openDb()
        val orch: SessionOrchestrator = newOrch(FakeMls::transact) // never provisioned
        var engine: EmergencyMeshEngine = newEngine()
        var deliverCalls = 0
        var inboxCalls = 0

        fun openDb(): ArmorChatDatabase =
            Room.databaseBuilder(app, ArmorChatDatabase::class.java, File(tmp.root, "$name.db").absolutePath)
                .allowMainThreadQueries().build().also { openDbs += it }

        fun newEngine() = EmergencyMeshEngine(app, db.meshCustodyDao(), clock).also {
            it.delivered = MeshDeliveredStore(File(tmp.root, "$name.markers"), markerIo)
        }

        /** Same MeshDelivery.deliver -> offerMesh call ChatMessagingService.deliverMesh starts with. */
        suspend fun deliver(payload: ByteArray): Boolean {
            deliverCalls += 1
            return MeshDelivery.deliver(payload, { raw, persist -> orch.offerMesh(raw, persist) }) { _, _ -> inboxCalls += 1 }
        }

        fun restart() = runBlocking {
            db.close()
            db = openDb()
            engine = newEngine()
            engine.arm()
        }

        suspend fun rows() = db.meshCustodyDao().all()
    }

    private class Node(val name: String, val engine: () -> EmergencyMeshEngine, val deliver: suspend (ByteArray) -> Boolean)

    private fun Phone.node() = Node(name, { engine }, { deliver(it) })
    private fun Carrier.node() = Node(name, { engine }, { deliver(it) })

    private val allowedLinks = setOf(setOf("A", "B"), setOf("B", "C"), setOf("J", "B"))
    private val linkLog = mutableListOf<Set<String>>()

    private fun requireLink(x: String, y: String) {
        val link = setOf(x, y)
        check(link in allowedLinks) { "no simulated link $x-$y" }
        linkLog += link
    }

    private fun assertNoDirectAC() {
        assertFalse("a direct A-C link was used: $linkLog", linkLog.any { it == setOf("A", "C") })
    }

    /** One BLE encounter between linked nodes (MeshReceiptAckTest.encounter order). [tamper] rewrites x -> y bundles. */
    private suspend fun encounter(x: Node, y: Node, tamper: (ByteArray) -> ByteArray = { it }): kotlin.Pair<Int, Int> {
        requireLink(x.name, y.name)
        val xPhase = MeshFrames.decodePhase(MeshFrames.encodePhase(x.engine().phaseFor(emptySet(), false)))!!
        val yPhase = MeshFrames.decodePhase(MeshFrames.encodePhase(y.engine().phaseFor(emptySet(), false)))!!
        val xOffers = x.engine().offers(yPhase.ids)
        val yOffers = y.engine().offers(xPhase.ids)
        fun ids(o: List<ByteArray>) = o.mapNotNull { (BundleCodec.decode(it) as? BundleDecode.Ok)?.bundle?.id }
        if (xOffers.isNotEmpty()) x.engine().markForwarded(ids(xOffers), RadioPath.BLE)
        if (yOffers.isNotEmpty()) y.engine().markForwarded(ids(yOffers), RadioPath.BLE)
        MeshCourier.ingest(MeshFrames.encodeBundles(xOffers.map(tamper)), RadioPath.BLE, { e, v -> y.engine().ingest(e, v) }, { y.deliver(it) }, { y.engine().dropDelivered(it) })
        MeshCourier.ingest(MeshFrames.encodeBundles(yOffers), RadioPath.BLE, { e, v -> x.engine().ingest(e, v) }, { x.deliver(it) }, { x.engine().dropDelivered(it) })
        return xOffers.size to yOffers.size
    }

    /** Unsolicited frames from [from] to [to] over an allowed link, ignoring inventory (replay / injection). */
    private suspend fun push(from: String, to: Node, bundles: List<ByteArray>) {
        requireLink(from, to.name)
        MeshCourier.ingest(MeshFrames.encodeBundles(bundles), RadioPath.BLE, { e, v -> to.engine().ingest(e, v) }, { to.deliver(it) }, { to.engine().dropDelivered(it) })
    }

    private fun bundle(payload: ByteArray, createdAtMs: Long, hop: Int = 0, emergency: Boolean = true): ByteArray = BundleCodec.encode(
        CustodyBundle(
            id = MeshIds.idFor(payload),
            createdAtMs = createdAtMs,
            expireAtMs = MeshPolicy.expireAt(createdAtMs),
            hopCount = hop,
            emergency = emergency,
            payload = payload,
        ),
    )

    private fun decoded(encoded: ByteArray): CustodyBundle = (BundleCodec.decode(encoded) as BundleDecode.Ok).bundle

    private fun ct(payload: ByteArray): String = JSONObject(String(payload, Charsets.UTF_8)).getString("encrypted_payload")

    private class World(val a: Phone, val b: Carrier, val c: Phone)

    private fun world(): World = runBlocking {
        val a = Phone("A", "C")
        val c = Phone("C", "A")
        a.peer = c
        c.peer = a
        a.orch.provision(a.signer, c.pin)
        c.orch.provision(c.signer, a.pin)
        a.orch.importPeerRoute(c.orch.localRouteQr(a.pin)!!, a.signer)
        confirmSharedMls(a.orch, c.orch, a.pin, c.pin)
        for (p in listOf(a, c)) {
            p.savedMailbox = p.link().peer.mailbox
            p.db.conversationDao().insert(ConversationEntity(p.conversationId, p.contactId, p.title, nowMs))
            p.persistSession()
            p.engine.arm()
        }
        val b = Carrier("B")
        b.engine.arm()
        World(a, b, c)
    }

    private suspend fun assertPending(a: Phone, sent: Sent, why: String) {
        assertEquals("$why: status", MessageStatus.QUEUED.name, a.status(sent.id))
        assertEquals("$why: own custody row kept", 1, a.ownCustody().size)
    }

    /** Steps 1-5: A seals for C, A meets B, B meets C. Returns the sent message. */
    private suspend fun World.deliverAToC(text: String): Sent {
        val sent = a.sendOffline(text)
        assertEquals(1 to 0, encounter(a.node(), b.node()))
        nowMs += 60_000
        assertEquals(1 to 0, encounter(b.node(), c.node()))
        return sent
    }

    // 1 -------------------------------------------------------------------------------------------
    @Test
    fun noDirectAToCLinkExistsAndIsRefused() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("must go via B")
        val refused = runCatching { encounter(w.a.node(), w.c.node()) }.exceptionOrNull()
        assertTrue("A-C encounter must be impossible", refused is IllegalStateException)
        val refusedPush = runCatching { push("A", w.c.node(), w.a.engine.offers(emptyList())) }.exceptionOrNull()
        assertTrue("A-C push must be impossible", refusedPush is IllegalStateException)
        assertTrue(linkLog.isEmpty())
        assertTrue(w.c.inboundIds().isEmpty())
        // 1K-AQ run 1: decrypts.size was 2 here because pairing (confirmSharedMls) decrypts on C; assert on this message.
        assertNull(w.c.mls.decrypts[ct(sent.payload)])
        assertPending(w.a, sent, "no link used")
    }

    // 2 -------------------------------------------------------------------------------------------
    @Test
    fun validAToBToCDeliveryWithoutRelayPlaintext() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("three node hello")
        val own = w.a.ownCustody().single()
        assertEquals(1 to 0, encounter(w.a.node(), w.b.node()))
        val carried = w.b.rows().single()
        assertEquals(own.id, carried.id)
        assertEquals("B stores hop + 1", 1, carried.hopCount)
        assertEquals("deadline copied, never restarted", own.expireAtMs, carried.expireAtMs)
        assertTrue("identical sealed bytes", carried.payload.contentEquals(sent.payload))
        assertFalse("no plaintext in B's custody", String(carried.payload, Charsets.UTF_8).contains("three node hello"))
        assertEquals("B tried messaging once", 1, w.b.deliverCalls)
        assertEquals("B persisted nothing", 0, w.b.inboxCalls)
        assertNull(w.b.orch.link(w.a.pin))
        assertNull(w.b.orch.link(w.c.pin))
        assertTrue(w.c.inboundIds().isEmpty())

        nowMs += 60_000
        assertEquals(1 to 0, encounter(w.b.node(), w.c.node()))
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals("three node hello", w.c.receipts().inbound(sent.id)!!.text)
        assertEquals("C decrypted exactly once", 1, w.c.mls.decrypts[ct(sent.payload)])
        assertTrue("C keeps no copy of the delivered bundle", w.c.custodyRows().none { it.id == own.id })
        assertEquals(MeshReceiptAck.Sealed.HELD, w.c.lastSeal)
        assertEquals("no ACK on A yet", MessageStatus.QUEUED.name, w.a.status(sent.id))
        assertNoDirectAC()
    }

    // 3 -------------------------------------------------------------------------------------------
    @Test
    fun validCToBToAReceiptMarksDeliveredAndReleasesSenderCustody() = runBlocking {
        val w = world()
        val sent = w.deliverAToC("ack me")
        val receipt = w.c.ownCustody().single()
        assertFalse("receipt held with emergency=false", receipt.emergency)
        val receiptCt = ct(receipt.payload)

        nowMs += 60_000
        assertEquals("C offers its receipt, B has nothing new for C", 0 to 1, encounter(w.b.node(), w.c.node()))
        val bRows = w.b.rows().associateBy { it.id }
        assertEquals(setOf(MeshIds.hex(MeshIds.idFor(sent.payload)), receipt.id), bRows.keys)
        assertEquals(1, bRows.getValue(receipt.id).hopCount)
        assertEquals(0, w.b.inboxCalls)

        nowMs += 60_000
        assertEquals("A skips its own id, B offers the receipt", 0 to 1, encounter(w.a.node(), w.b.node()))
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        assertEquals(nowMs, w.a.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs)
        assertEquals(MessageStatus.DELIVERED, w.a.receipts().status(sent.id))
        assertTrue("sender custody released (Room)", w.a.ownCustody().isEmpty())
        assertTrue("sender custody released (memory)", w.a.engine.ownHeld().isEmpty())
        assertTrue("A keeps no receipt copy", w.a.custodyRows().isEmpty())
        assertEquals(true, w.a.lastReceiptMarker)
        assertEquals("A decrypted the receipt once", 1, w.a.mls.decrypts[receiptCt])
        assertEquals("B never persisted anything", 0, w.b.inboxCalls)
        // KNOWN GAP (1K-AP MISSING): B does not learn of the ACK and keeps both copies until the 4 h deadline.
        assertEquals(2, w.b.rows().size)
        assertNoDirectAC()
        assertEquals(listOf(setOf("A", "B"), setOf("B", "C"), setOf("B", "C"), setOf("A", "B")), linkLog)
    }

    // 4 -------------------------------------------------------------------------------------------
    @Test
    fun duplicateEncountersAndReplaysNeverDuplicateDeliveryOrAck() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("once only")
        encounter(w.a.node(), w.b.node())
        assertEquals("inventory suppresses a repeat A-B encounter", 0 to 0, encounter(w.a.node(), w.b.node()))
        val bCalls = w.b.deliverCalls
        push("A", w.b.node(), listOf(bundle(sent.payload, nowMs))) // raw replay, same payload id
        push("A", w.b.node(), w.a.engine.offers(emptyList()))
        assertEquals("replayed duplicate never re-offered to messaging", bCalls, w.b.deliverCalls)
        assertEquals(1, w.b.rows().size)
        assertEquals(1, w.b.rows().single().hopCount)

        encounter(w.b.node(), w.c.node())
        encounter(w.b.node(), w.c.node())
        push("B", w.c.node(), w.b.engine.offers(emptyList())) // B replays everything it holds to C
        push("B", w.c.node(), w.b.engine.offers(emptyList()))
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals("one decrypt for the message despite replays", 1, w.c.mls.decrypts[ct(sent.payload)])
        val receipt = w.c.ownCustody().single()

        encounter(w.a.node(), w.b.node())
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        val deliveredAt = w.a.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs
        nowMs += 60_000
        repeat(3) { push("B", w.a.node(), listOf(BundleCodec.encode(decoded(bundle(receipt.payload, receipt.createdAtMs))))) }
        encounter(w.a.node(), w.b.node())
        assertEquals("one decrypt for the receipt despite replays", 1, w.a.mls.decrypts[ct(receipt.payload)])
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        assertEquals(deliveredAt, w.a.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs)
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertTrue(w.a.ownCustody().isEmpty())
        assertNoDirectAC()
    }

    // 5 -------------------------------------------------------------------------------------------
    @Test
    fun custodyTtlIsAbsoluteAcrossRelayAndExpiresOnB() = runBlocking {
        val w = world()
        val created = nowMs
        val sent = w.a.sendOffline("expires in transit")
        nowMs += 3 * 3_600_000L
        encounter(w.a.node(), w.b.node())
        assertEquals("B copies A's deadline (no reset at hop)", MeshPolicy.expireAt(created), w.b.rows().single().expireAtMs)
        nowMs = MeshPolicy.expireAt(created) // exact deadline
        assertEquals("nothing live to offer", 0 to 0, encounter(w.b.node(), w.c.node()))
        assertTrue("B swept the expired copy", w.b.rows().isEmpty())
        assertTrue(w.c.inboundIds().isEmpty())
        assertNull(w.c.mls.decrypts[ct(sent.payload)])
        // A late replay of the expired bundle is refused before messaging.
        val cCalls = w.c.outcomes.size
        push("B", w.c.node(), listOf(bundle(sent.payload, created)))
        assertEquals(cCalls, w.c.outcomes.size)
        assertTrue(w.c.inboundIds().isEmpty())
        w.a.engine.sweep()
        assertTrue("sender custody expired too", w.a.ownCustody().isEmpty())
        assertEquals("expiry does not fake delivery", MessageStatus.QUEUED.name, w.a.status(sent.id))
        assertNoDirectAC()
    }

    // 6 -------------------------------------------------------------------------------------------
    @Test
    fun hopLimitIsEnforcedAcrossTheRelay() = runBlocking {
        val w = world()
        val (_, sealedOk) = w.a.sendOffline("hop four").let { it.id to it.payload }
        val (_, sealedDead) = w.a.sendOffline("hop five").let { it.id to it.payload }
        val (_, sealedOver) = w.a.sendOffline("hop six").let { it.id to it.payload }
        // An upstream node J hands B bundles that already travelled.
        push("J", w.b.node(), listOf(
            bundle(sealedOk, nowMs, hop = MeshPolicy.MAX_HOPS - 2),
            bundle(sealedDead, nowMs, hop = MeshPolicy.MAX_HOPS - 1),
            bundle(sealedOver, nowMs, hop = MeshPolicy.MAX_HOPS),
        ))
        val hops = w.b.rows().associate { it.id to it.hopCount }
        assertEquals(MeshPolicy.MAX_HOPS - 1, hops[MeshIds.hex(MeshIds.idFor(sealedOk))])
        assertEquals(MeshPolicy.MAX_HOPS, hops[MeshIds.hex(MeshIds.idFor(sealedDead))])
        assertNull("hop == MAX refused by B (CustodyLedger.kt:66)", hops[MeshIds.hex(MeshIds.idFor(sealedOver))])

        encounter(w.b.node(), w.c.node())
        assertEquals("only the in-limit bundle reaches C", 1, w.c.inboundIds().size)
        assertEquals(1, w.c.mls.decrypts[ct(sealedOk)])
        assertNull("bundle at MAX_HOPS on B is refused by C before decrypt", w.c.mls.decrypts[ct(sealedDead)])
        assertNull(w.c.mls.decrypts[ct(sealedOver)])
        assertNoDirectAC()
    }

    // 7 -------------------------------------------------------------------------------------------
    @Test
    fun relayCanRewriteHopCountAndEmergencyFlag_KNOWN_GAP() = runBlocking {
        // KNOWN GAP: hopCount and emergency are outside MeshIds.idFor(payload) (BundleCodec.kt:17-22,
        // CustodyLedger.kt:67), so B can rewrite them undetected. C accepts the rewritten header.
        val w = world()
        val sent = w.a.sendOffline("tamper header")
        encounter(w.a.node(), w.b.node())
        val atB = w.b.rows().single()
        assertTrue(atB.emergency)
        assertEquals(1, atB.hopCount)
        val probe = Carrier("P") // isolated observer of what C's ledger admits
        val rewritten = mutableListOf<ByteArray>()
        val tamper: (ByteArray) -> ByteArray = { enc ->
            BundleCodec.encode(decoded(enc).copy(hopCount = 0, emergency = false)).also { rewritten += it }
        }
        encounter(w.b.node(), w.c.node(), tamper)
        assertEquals(1, rewritten.size)
        assertTrue("C admitted a header B rewrote", probe.engine.ingest(rewritten.single(), RadioPath.BLE) is MeshAdmission.Admitted)
        val stored = probe.rows().single()
        assertEquals("rewritten hop accepted (0 + 1 instead of 1 + 1)", 1, stored.hopCount)
        assertFalse("rewritten emergency flag accepted", stored.emergency)
        assertEquals(listOf(sent.id), w.c.inboundIds()) // payload still authenticated by MLS layer
        // Raising the hop to the limit silently kills onward delivery:
        val sent2 = w.a.sendOffline("kill by hop")
        encounter(w.a.node(), w.b.node())
        encounter(w.b.node(), w.c.node()) { enc ->
            val b = decoded(enc)
            if (b.payload.contentEquals(sent2.payload)) BundleCodec.encode(b.copy(hopCount = MeshPolicy.MAX_HOPS)) else enc
        }
        assertFalse("C refused the inflated hop: no delivery", sent2.id in w.c.inboundIds())
        assertNoDirectAC()
    }

    // 8 -------------------------------------------------------------------------------------------
    @Test
    fun junkFloodIsBoundedAndLegitDeliversAfterTheRateWindow() = runBlocking {
        // Mesh-1K-AS contract: an encounter stores at most 8 foreign rows and a 10-minute window at most 16.
        // Refusal does not evict. After the window advances, a later legitimate bundle is admitted and,
        // once the earlier junk has been offered, delivered. A full foreign pool then refuses without eviction.
        val w = world()
        val junk = (0 until MeshPolicy.MAX_BUNDLES).map { i -> bundle(asJunk("flood", i), nowMs) }
        push("J", w.b.node(), junk.take(24))
        assertEquals(asPerEncounter, w.b.rows().size)
        push("J", w.b.node(), junk.drop(24))
        assertEquals(asRatePerWindow, w.b.rows().size)
        val junkBytes = w.b.rows().associate { it.id to it.payload.toList() }
        val probe = bundle(asJunk("probe", 0), nowMs)
        assertEquals(MeshAdmission.Rejected(MeshRejection.RATE), w.b.engine.ingest(probe, RadioPath.BLE))
        assertEquals(junkBytes, w.b.rows().associate { it.id to it.payload.toList() })
        encounter(w.b.node(), w.c.node())
        assertEquals(asPerEncounter, w.c.custodyRows().size)
        assertTrue(w.c.inboundIds().isEmpty())

        nowMs += asWindowMs + 1_000
        val sent = w.a.sendOffline("legit after the rate window")
        val legitId = MeshIds.hex(MeshIds.idFor(sent.payload))
        encounter(w.a.node(), w.b.node())
        assertTrue(legitId in w.b.rows().map { it.id })
        assertEquals("junk bytes kept when the legit bundle is admitted", junkBytes, w.b.rows().filter { it.id in junkBytes }.associate { it.id to it.payload.toList() })
        assertEquals(junkBytes.size + 1, w.b.rows().size)
        encounter(w.b.node(), w.c.node())
        assertFalse("sooner junk is offered before the later legit bundle", sent.id in w.c.inboundIds())
        assertEquals(asRatePerWindow, w.c.custodyRows().count { it.hopCount >= 1 })
        encounter(w.b.node(), w.c.node())
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals(1, w.c.mls.decrypts[ct(sent.payload)])
        assertPending(w.a, sent, "C has the message; A's own copy stays until the receipt returns")

        val more = (0 until asPerEncounter * 2).map { bundle(asJunk("cap", it), nowMs) }
        push("J", w.b.node(), more.take(asPerEncounter))
        push("J", w.b.node(), more.drop(asPerEncounter))
        assertEquals(asForeignMax, w.b.rows().count { it.hopCount >= 1 })
        val capped = w.b.rows().associate { it.id to it.payload.toList() }
        nowMs += asWindowMs + 1_000
        val blocked = bundle(asJunk("blocked", 0), nowMs)
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), w.b.engine.ingest(blocked, RadioPath.BLE))
        assertEquals("FULL refusal does not evict", capped, w.b.rows().associate { it.id to it.payload.toList() })
        assertPending(w.a, sent, "sender own hold survives a full foreign pool")
        assertNoDirectAC()
    }

    // 9 -------------------------------------------------------------------------------------------
    @Test
    fun forgedReceiptsCarriedThroughRelayNeverReleaseSenderCustody() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("forgery target")
        val target = w.a.receipts().outbound(sent.id)
        encounter(w.a.node(), w.b.node())
        encounter(w.b.node(), w.c.node())
        val genuine = w.c.ownCustody().single().payload
        fun mutate(f: JSONObject.() -> Unit) = JSONObject(String(genuine, Charsets.UTF_8)).apply(f).toString().toByteArray()
        val flipped = ct(genuine).let { s -> s.substring(0, 8) + (if (s[8] == 'A') 'B' else 'A') + s.substring(9) }
        // Forgeries B can make with NO session (outer envelope / ciphertext only).
        val relayForged = linkedMapOf(
            "ciphertext byte flipped by B" to mutate { put("encrypted_payload", flipped) },
            "undecryptable ciphertext" to mutate { put("encrypted_payload", "AAAA") },
            "other MLS session ciphertext" to mutate { put("encrypted_payload", Base64Url.encode("E|other-session|x".toByteArray())) },
            "outer message_id rewritten" to mutate { put("message_id", Base64Url.encode(ByteArray(16) { 7 })) },
        )
        // Inner-binding forgeries sealed with C's real session (prove inner checks only, FakeMls).
        val c = w.c.receipts()
        fun body(r: InnerRecord) = r.body as InnerBody.Receipt
        fun cSealed(inner: InnerRecord): ByteArray {
            val peer = w.c.link().peer
            val enc = peer.encryptRaw(inner.encode())
            return peer.makeApplicationEnvelope(enc.ciphertextB64, inner.messageId, inner.expiresAt).canonicalJson().toByteArray(Charsets.UTF_8)
        }
        val innerForged = linkedMapOf(
            "forged digest" to cSealed(c.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetCiphertextSha256 = Base64Url.encode(ByteArray(32) { 4 }))) }),
            "orphan target" to cSealed(c.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetMessageId = Base64Url.encode(ByteArray(16) { 9 }))) }),
            "wrong sender pin" to cSealed(c.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(senderIdentityKey = TinkSigner().pin) }),
        )
        for ((why, payload) in relayForged + innerForged) {
            push("B", w.a.node(), listOf(bundle(payload, nowMs)))
            assertPending(w.a, sent, why)
            assertTrue(why, w.a.lastApplied.isEmpty())
            assertEquals(why, MessageStatus.QUEUED, w.a.receipts().status(sent.id))
        }
        // Control: the genuine receipt via the real C -> B -> A path is accepted.
        encounter(w.b.node(), w.c.node())
        encounter(w.a.node(), w.b.node())
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        assertTrue(w.a.ownCustody().isEmpty())
        assertNoDirectAC()
    }

    // 10 ------------------------------------------------------------------------------------------
    @Test
    fun deliveredNeverRegressesToQueuedAfterReplayRestartAndExpiry() = runBlocking {
        val w = world()
        val sent = w.deliverAToC("stay delivered")
        encounter(w.b.node(), w.c.node())
        encounter(w.a.node(), w.b.node())
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        val deliveredAt = w.a.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs
        val receipt = w.b.rows().first { it.id != MeshIds.hex(MeshIds.idFor(sent.payload)) }

        // B still carries A's own original (A released it, so A's inventory no longer lists it): B offers it back.
        nowMs += 60_000
        encounter(w.a.node(), w.b.node())
        val carriedMsg = w.b.rows().first { it.id == MeshIds.hex(MeshIds.idFor(sent.payload)) }
        push("B", w.a.node(), listOf(bundle(sent.payload, carriedMsg.createdAtMs)))
        repeat(2) { push("B", w.a.node(), listOf(bundle(receipt.payload, receipt.createdAtMs))) }
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        assertEquals(deliveredAt, w.a.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs)

        w.a.restart()
        assertEquals("after restart", MessageStatus.DELIVERED.name, w.a.status(sent.id))
        encounter(w.a.node(), w.b.node())
        assertEquals("after restart + encounter", MessageStatus.DELIVERED.name, w.a.status(sent.id))

        nowMs += MeshPolicy.ABSOLUTE_TTL_MS + 1
        w.a.engine.sweep()
        assertEquals("after custody expiry", MessageStatus.DELIVERED.name, w.a.status(sent.id))
        assertEquals(deliveredAt, w.a.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs)
        assertNoDirectAC()
    }

    // 11 ------------------------------------------------------------------------------------------
    @Test
    fun relayRestartKeepsPersistedCustodyAndCompletesRoundTrip() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("survive B restart")
        encounter(w.a.node(), w.b.node())
        val before = w.b.rows().single()
        w.b.restart()
        val after = w.b.rows().single()
        assertEquals(before.id, after.id)
        assertEquals(before.hopCount, after.hopCount)
        assertEquals(before.expireAtMs, after.expireAtMs)
        assertTrue(before.payload.contentEquals(after.payload))
        nowMs += 60_000
        assertEquals("restored ledger offers the bundle", 1 to 0, encounter(w.b.node(), w.c.node()))
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals("C hands B the receipt", 1, encounter(w.b.node(), w.c.node()).second)
        w.b.restart()
        assertEquals(2, w.b.rows().size)
        encounter(w.a.node(), w.b.node())
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        assertTrue(w.a.ownCustody().isEmpty())
        assertEquals(1, w.c.mls.decrypts[ct(sent.payload)])
        assertNoDirectAC()
    }

    // =============================================================================================
    // Mesh-1K-AS (test-first hardening). Appended only; tests 1-11 above are unchanged.
    // Policy under test (1K-AR design): own pool (hopCount 0) 16, foreign pool (hopCount >= 1) 32,
    // refuse (never evict) when a pool is full, <= 8 foreign admissions per ingest (encounter),
    // <= 16 foreign admissions per 10-minute window, emergency bit ignored for foreign rows,
    // destination rejects an MLS-bound inner expiry that has passed (mesh only).
    // These are RESOURCE-ABUSE mitigations, not cryptographic guarantees. Rate/encounter limits are not
    // keyed on any origin id, so they give no Sybil resistance. MLS is FakeMls (no crypto-auth claim).
    // =============================================================================================

    private val asOwnMax = 16
    private val asForeignMax = 32
    private val asPerEncounter = 8
    private val asRatePerWindow = 16
    private val asWindowMs = 10L * 60_000L

    private fun asJunk(tag: String, i: Int): ByteArray = "as-junk-$tag-$i-".toByteArray() + ByteArray(24) { (i * 7 + tag.length).toByte() }

    private fun asEnvExpiresSec(payload: ByteArray): Long = JSONObject(String(payload, Charsets.UTF_8)).getLong("expires_at")

    /** Push junk in batches of [asPerEncounter], opening a fresh rate window before each pair of batches. */
    private suspend fun asFillForeign(to: Node, from: String, tag: String, batches: Int, emergency: Boolean = true): List<String> {
        val ids = mutableListOf<String>()
        for (k in 0 until batches) {
            if (k % 2 == 0) nowMs += asWindowMs + 1_000
            val batch = (0 until asPerEncounter).map { j -> asJunk(tag, k * asPerEncounter + j) }
            ids += batch.map { MeshIds.hex(MeshIds.idFor(it)) }
            push(from, to, batch.map { bundle(it, nowMs, emergency = emergency) })
        }
        return ids
    }

    // AS-1 ----------------------------------------------------------------------------------------
    @Test
    fun as01_receiverRejectsExpiredAuthenticatedMeshMessage() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("expired inner")
        val innerExp = asEnvExpiresSec(sent.payload) // == MLS-bound inner expiresAt (bindInbound :959)
        // Destination clock is past the authenticated expiry (beyond OutboxPolicy skew).
        nowMs = (innerExp + OutboxPolicy.CLOCK_SKEW_SECONDS + 60) * 1000
        // A carrier re-wraps it in a still-live bundle that also passes the relay sanity bound.
        val created = nowMs - MeshPolicy.ABSOLUTE_TTL_MS + 60_000
        push("B", w.c.node(), listOf(bundle(sent.payload, created)))
        assertFalse("expired authenticated message must not reach C's inbox", sent.id in w.c.inboundIds())
        assertTrue("no receipt sealed for an expired message", w.c.ownCustody().isEmpty())
        assertNoDirectAC()
    }

    // AS-2 ----------------------------------------------------------------------------------------
    @Test
    fun as02_tamperedOuterExpiryIsRejectedAgainstMlsBoundInnerExpiry() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("tampered outer expiry")
        val exp = asEnvExpiresSec(sent.payload)
        for (delta in listOf(3_600L, -3_600L)) {
            val forged = JSONObject(String(sent.payload, Charsets.UTF_8)).put("expires_at", exp + delta).toString().toByteArray()
            push("B", w.c.node(), listOf(bundle(forged, nowMs)))
            assertTrue("outer expiry ${if (delta > 0) "extended" else "shortened"} must be rejected", w.c.inboundIds().isEmpty())
            assertTrue(w.c.ownCustody().isEmpty())
        }
        assertNoDirectAC()
    }

    // AS-2b ---------------------------------------------------------------------------------------
    @Test
    fun as02b_genuineEnvelopeStillDeliversAfterTamperedOuterExpiryCopy_DEFECT() = runBlocking {
        // Asserts the correct behaviour. Mesh-1K-AU: a mismatched outer expires_at is rejected without
        // putSeen(accepted=false), and the pre-decrypt MLS snapshot is restored. The genuine copy,
        // same message id and ciphertext, must still deliver. Authenticated "expired" still records seen.
        val w = world()
        val sent = w.a.sendOffline("tamper then genuine")
        val forged = JSONObject(String(sent.payload, Charsets.UTF_8)).put("expires_at", asEnvExpiresSec(sent.payload) + 3_600).toString().toByteArray()
        push("B", w.c.node(), listOf(bundle(forged, nowMs)))
        assertTrue(w.c.inboundIds().isEmpty())
        push("B", w.c.node(), listOf(bundle(sent.payload, nowMs)))
        assertEquals("genuine copy must still deliver after a forged copy was rejected", listOf(sent.id), w.c.inboundIds())
        assertNoDirectAC()
    }

    // Mesh-1K-AU: outer-expiry seen poison. as02b above stays. These lock the five required cases.
    @Test
    fun au01_tamperedOuterExpiryIsRejected() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("au tampered outer")
        val exp = asEnvExpiresSec(sent.payload)
        val before = w.c.link().peer.snapshot().toList()
        for (delta in listOf(3_600L, -3_600L)) {
            val forged = JSONObject(String(sent.payload, Charsets.UTF_8)).put("expires_at", exp + delta).toString().toByteArray()
            push("B", w.c.node(), listOf(bundle(forged, nowMs)))
            assertTrue(w.c.inboundIds().isEmpty())
            assertTrue(w.c.ownCustody().isEmpty())
            assertEquals("MLS snapshot restored after unauthenticated outer expiry", before, w.c.link().peer.snapshot().toList())
        }
        assertEquals(2, w.c.mls.decrypts[ct(sent.payload)])
        assertNoDirectAC()
    }

    @Test
    fun au02_genuineCopyAcceptedAfterTamperedOuterExpiry() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("au genuine after tamper")
        val forged = JSONObject(String(sent.payload, Charsets.UTF_8))
            .put("expires_at", asEnvExpiresSec(sent.payload) + 3_600)
            .toString().toByteArray()
        push("B", w.c.node(), listOf(bundle(forged, nowMs)))
        assertTrue(w.c.inboundIds().isEmpty())
        push("B", w.c.node(), listOf(bundle(sent.payload, nowMs)))
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals(2, w.c.mls.decrypts[ct(sent.payload)])
        assertNoDirectAC()
    }

    @Test
    fun au03_repeatedTamperedCopiesCannotPoisonGenuineDelivery() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("au repeated tamper")
        val exp = asEnvExpiresSec(sent.payload)
        repeat(3) { i ->
            val forged = JSONObject(String(sent.payload, Charsets.UTF_8))
                .put("expires_at", exp + 3_600 + i)
                .toString().toByteArray()
            push("B", w.c.node(), listOf(bundle(forged, nowMs)))
            assertTrue("tamper $i", w.c.inboundIds().isEmpty())
        }
        assertEquals(3, w.c.mls.decrypts[ct(sent.payload)])
        push("B", w.c.node(), listOf(bundle(sent.payload, nowMs)))
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals(4, w.c.mls.decrypts[ct(sent.payload)])
        push("B", w.c.node(), listOf(bundle(sent.payload, nowMs)))
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals("accepted genuine copy is not decrypted again", 4, w.c.mls.decrypts[ct(sent.payload)])
        assertNoDirectAC()
    }

    @Test
    fun au04_genuineAcceptedCopyRemainsDeduplicated() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("au dedup")
        push("B", w.c.node(), listOf(bundle(sent.payload, nowMs)))
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals(1, w.c.mls.decrypts[ct(sent.payload)])
        push("B", w.c.node(), listOf(bundle(sent.payload, nowMs)))
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals(1, w.c.mls.decrypts[ct(sent.payload)])
        assertEquals(1, w.c.ownCustody().size)
        assertNoDirectAC()
    }

    @Test
    fun au05_authenticatedExpiryCannotBeBypassedByExtendingOuterHeader() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("au authenticated expiry")
        val innerExp = asEnvExpiresSec(sent.payload)
        nowMs = (innerExp + OutboxPolicy.CLOCK_SKEW_SECONDS + 60) * 1000
        val created = nowMs - MeshPolicy.ABSOLUTE_TTL_MS + 60_000
        val extended = JSONObject(String(sent.payload, Charsets.UTF_8))
            .put("expires_at", innerExp + OutboxPolicy.TTL_SECONDS)
            .toString().toByteArray()
        val before = w.c.link().peer.snapshot().toList()
        push("B", w.c.node(), listOf(bundle(extended, created)))
        assertTrue("extended outer header must not deliver an expired inner", w.c.inboundIds().isEmpty())
        assertEquals(before, w.c.link().peer.snapshot().toList())
        assertEquals(1, w.c.mls.decrypts[ct(sent.payload)])
        push("B", w.c.node(), listOf(bundle(sent.payload, created)))
        assertTrue("matching outer of an expired inner is still rejected", w.c.inboundIds().isEmpty())
        assertEquals(2, w.c.mls.decrypts[ct(sent.payload)])
        nowMs += 61_000
        w.c.engine.sweep()
        val replayCreated = nowMs - MeshPolicy.ABSOLUTE_TTL_MS + 60_000
        push("B", w.c.node(), listOf(bundle(sent.payload, replayCreated)))
        push("B", w.c.node(), listOf(bundle(extended, replayCreated)))
        assertEquals("expired rejection stays in seen state", 2, w.c.mls.decrypts[ct(sent.payload)])
        assertTrue(w.c.inboundIds().isEmpty())
        assertTrue(w.c.ownCustody().isEmpty())
        assertNoDirectAC()
    }

    // AS-3 ----------------------------------------------------------------------------------------
    @Test
    fun as03_relayRefusesMalformedTimestampsAndObviousTtlExtension() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("ttl sanity")
        val exp = asEnvExpiresSec(sent.payload) * 1000
        val b = w.b.engine
        // Malformed: deadline != created + 4h (decoder), future createdAt, already expired.
        val bad = CustodyBundle(MeshIds.idFor(sent.payload), nowMs, nowMs + 1, 0, false, sent.payload)
        assertEquals(MeshAdmission.Rejected(MeshRejection.MALFORMED), b.ingest(BundleCodec.encode(bad), RadioPath.BLE))
        assertEquals(MeshAdmission.Rejected(MeshRejection.FUTURE), b.ingest(bundle(sent.payload, nowMs + MeshPolicy.CLOCK_SKEW_MS + 60_000), RadioPath.BLE))
        assertEquals(MeshAdmission.Rejected(MeshRejection.EXPIRED), b.ingest(bundle(sent.payload, nowMs - MeshPolicy.ABSOLUTE_TTL_MS), RadioPath.BLE))
        // Malformed envelope expires_at in a JSON payload.
        for (badExp in listOf<Any>("soon", -5L, 0L)) {
            val p = JSONObject(String(sent.payload, Charsets.UTF_8)).put("expires_at", badExp).toString().toByteArray()
            assertEquals("expires_at=$badExp", MeshAdmission.Rejected(MeshRejection.DEADLINE), b.ingest(bundle(p, nowMs), RadioPath.BLE))
        }
        // Obvious TTL extension: bundle deadline beyond the cleartext envelope expiry (+ skew).
        nowMs = exp - 60 * 60_000L
        assertEquals("bundle outliving its envelope", MeshAdmission.Rejected(MeshRejection.DEADLINE), b.ingest(bundle(sent.payload, nowMs), RadioPath.BLE))
        assertTrue(w.b.rows().isEmpty())
        // LIMITATION (documented, not a guarantee): a refresh that stays inside the envelope lifetime is
        // indistinguishable from an honest late first hop, so it is still accepted.
        nowMs = exp - MeshPolicy.ABSOLUTE_TTL_MS - 60_000
        assertTrue(b.ingest(bundle(sent.payload, nowMs), RadioPath.BLE) is MeshAdmission.Admitted)
    }

    // AS-4 ----------------------------------------------------------------------------------------
    @Test
    fun as04_forgedEmergencyFlagGivesForeignBundleNoOfferPriority() = runBlocking {
        val w = world()
        val sent = w.a.sendOffline("legit, no emergency bit")
        // Legit bundle arrives at B with emergency=false and the soonest deadline.
        push("J", w.b.node(), listOf(bundle(sent.payload, nowMs - 30 * 60_000L, emergency = false)))
        nowMs += 60_000
        // Then 8 junk bundles with a forged emergency bit and later deadlines.
        push("J", w.b.node(), (0 until asPerEncounter).map { bundle(asJunk("em", it), nowMs, emergency = true) })
        assertEquals(1 + asPerEncounter, w.b.rows().size)
        val offered = w.b.engine.offers(emptyList()).map { decoded(it).idHex }
        assertTrue("legit foreign bundle must be offered despite forged emergency junk", MeshIds.hex(MeshIds.idFor(sent.payload)) in offered)
        assertEquals("foreign rows ordered by deadline only", MeshIds.hex(MeshIds.idFor(sent.payload)), offered.first())
        encounter(w.b.node(), w.c.node())
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertNoDirectAC()
    }

    // AS-5 ----------------------------------------------------------------------------------------
    @Test
    fun as05_ownAndReceiptCapacityIsReservedAgainstForeignFlood() = runBlocking {
        val w = world()
        asFillForeign(w.b.node(), "J", "own", batches = 6) // 48 junk offered over 3 windows
        assertEquals("foreign pool capped", asForeignMax, w.b.rows().count { it.hopCount >= 1 })
        // B's own message and own receipt still get custody.
        assertTrue(w.b.engine.holdSealed("b-own-msg".toByteArray(), emergency = true) is MeshHoldResult.Held)
        assertTrue(w.b.engine.holdSealed("b-own-receipt".toByteArray(), emergency = false, retryIfFull = true) is MeshHoldResult.Held)
        // Own pool itself is bounded at 16 and refuses (no eviction) at the cap.
        for (i in 2 until asOwnMax) assertTrue(w.b.engine.holdSealed("b-own-$i".toByteArray(), emergency = i % 2 == 0) is MeshHoldResult.Held)
        val ownBefore = w.b.rows().filter { it.hopCount == 0 }.map { it.id }.toSet()
        assertEquals(asOwnMax, ownBefore.size)
        val over = w.b.engine.holdSealed("b-own-over".toByteArray(), emergency = true)
        assertTrue("17th own row refused", over is MeshHoldResult.Rejected)
        assertEquals("no own row evicted", ownBefore, w.b.rows().filter { it.hopCount == 0 }.map { it.id }.toSet())
        assertEquals(asForeignMax, w.b.rows().count { it.hopCount >= 1 })
    }

    // AS-6 ----------------------------------------------------------------------------------------
    @Test
    fun as06_foreignPoolExhaustionRefusesWithoutEvictingValidCustody() = runBlocking {
        val w = world()
        val first = asFillForeign(w.b.node(), "J", "pool", batches = 4, emergency = false)
        val snapshot = w.b.rows().associate { it.id to (it.createdAtMs to it.payload.toList()) }
        assertEquals(asForeignMax, snapshot.size)
        assertEquals(first.toSet(), snapshot.keys)
        // A fresh window: emergency junk and the legit message are both refused FULL; nothing is evicted.
        nowMs += asWindowMs + 1_000
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), w.b.engine.ingest(bundle(asJunk("late", 0), nowMs, emergency = true), RadioPath.BLE))
        val sent = w.a.sendOffline("legit into full pool")
        encounter(w.a.node(), w.b.node())
        assertEquals("existing custody byte-identical", snapshot, w.b.rows().filter { it.hopCount >= 1 }.associate { it.id to (it.createdAtMs to it.payload.toList()) })
        assertPending(w.a, sent, "B foreign pool full")
        assertNoDirectAC()
    }

    // AS-7 ----------------------------------------------------------------------------------------
    @Test
    fun as07_atMostEightForeignAdmissionsPerEncounter() = runBlocking {
        val w = world()
        push("J", w.b.node(), (0 until 20).map { bundle(asJunk("enc", it), nowMs) })
        assertEquals(asPerEncounter, w.b.rows().size)
        // The next encounter may admit up to 8 more (rate window permitting).
        push("J", w.b.node(), (20 until 40).map { bundle(asJunk("enc", it), nowMs) })
        assertEquals(2 * asPerEncounter, w.b.rows().size)
    }

    // AS-8 ----------------------------------------------------------------------------------------
    @Test
    fun as08_rateWindowLimitsForeignAdmissions() = runBlocking {
        val w = world()
        repeat(3) { k -> push("J", w.b.node(), (0 until asPerEncounter).map { bundle(asJunk("rate$k", it), nowMs) }) }
        assertEquals("16 per window", asRatePerWindow, w.b.rows().size)
        nowMs += asWindowMs + 1_000
        push("J", w.b.node(), (0 until asPerEncounter).map { bundle(asJunk("rate3", it), nowMs) })
        assertEquals("window reopened", asRatePerWindow + asPerEncounter, w.b.rows().size)
    }

    // AS-9 ----------------------------------------------------------------------------------------
    @Test
    fun as09_rotatingPeerIdentitiesDoNotBypassEncounterRateOrPoolLimits() = runBlocking {
        val w = world()
        // Each batch claims a different (spoofable, cleartext) identity: sender_credential / mailbox.
        // Limits are not keyed on any of these, so rotation buys nothing. This is NOT Sybil resistance.
        fun spoof(peer: Int, i: Int) = JSONObject()
            .put("encrypted_payload", "junk-$peer-$i")
            .put("message_id", "m-$peer-$i")
            .put("recipient_mailbox", "mbox-$peer")
            .put("sender_credential", "cred-$peer")
            .put("payload_type", "application")
            .put("expires_at", nowMs / 1000 + 86_400)
            .toString().toByteArray()
        for (peer in 0 until 3) push("J", w.b.node(), (0 until 12).map { bundle(spoof(peer, it), nowMs) })
        assertEquals("rate window caps rotating identities", asRatePerWindow, w.b.rows().size)
        for (round in 0 until 4) {
            nowMs += asWindowMs + 1_000
            for (peer in 10 + round * 3 until 13 + round * 3) push("J", w.b.node(), (0 until 12).map { bundle(spoof(peer, it), nowMs) })
        }
        assertEquals("pool cap holds against rotating identities", asForeignMax, w.b.rows().size)
    }

    // AS-10 ---------------------------------------------------------------------------------------
    @Test
    fun as10_validRoundTripUnderPartialCapacityPressure() = runBlocking {
        val w = world()
        asFillForeign(w.b.node(), "J", "press", batches = 3) // 24 junk on B
        assertEquals(24, w.b.rows().size)
        nowMs += asWindowMs + 1_000
        val sent = w.a.sendOffline("under pressure")
        encounter(w.a.node(), w.b.node())
        assertTrue(MeshIds.hex(MeshIds.idFor(sent.payload)) in w.b.rows().map { it.id })
        // B offers 8 per encounter in deadline order; keep meeting C until the message lands.
        repeat(5) { if (sent.id !in w.c.inboundIds()) { nowMs += asWindowMs + 1_000; encounter(w.b.node(), w.c.node()) } }
        assertEquals(listOf(sent.id), w.c.inboundIds())
        repeat(5) { if (w.a.status(sent.id) != MessageStatus.DELIVERED.name) { nowMs += asWindowMs + 1_000; encounter(w.b.node(), w.c.node()); encounter(w.a.node(), w.b.node()) } }
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        assertTrue(w.a.ownCustody().isEmpty())
        assertEquals(1, w.c.mls.decrypts[ct(sent.payload)])
        assertNoDirectAC()
    }

    // AS-11 ---------------------------------------------------------------------------------------
    @Test
    fun as11_receiptHoldAdmittedWhenForeignPoolFull() = runBlocking {
        val w = world()
        asFillForeign(w.c.node(), "B", "cfull", batches = 6)
        assertEquals("C foreign pool full", asForeignMax, w.c.custodyRows().count { it.hopCount >= 1 })
        nowMs += asWindowMs + 1_000
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), w.c.engine.ingest(bundle(asJunk("cfull-x", 0), nowMs), RadioPath.BLE))
        val r = w.c.engine.holdSealed("c-own-receipt".toByteArray(), emergency = false, retryIfFull = true)
        assertTrue("own receipt must get custody from the reserved own pool: $r", r is MeshHoldResult.Held)
    }

    // AS-12 ---------------------------------------------------------------------------------------
    @Test
    fun as12_restartWithLegacyOverQuotaRowsKeepsThemAndAppliesPolicy() = runBlocking {
        val w = world()
        val dao = w.b.db.meshCustodyDao()
        // Legacy rows written by the old policy: 40 foreign (over the 32 quota), all forged emergency, + 2 own.
        val legacy = (0 until 40).map { i ->
            val p = asJunk("legacy", i)
            com.codeyantra.armorchat.core.database.entity.MeshCustodyEntity(MeshIds.hex(MeshIds.idFor(p)), nowMs - i * 1_000L, MeshPolicy.expireAt(nowMs - i * 1_000L), 1, true, p, 0)
        }
        val own = (0 until 2).map { i ->
            val p = "legacy-own-$i".toByteArray()
            com.codeyantra.armorchat.core.database.entity.MeshCustodyEntity(MeshIds.hex(MeshIds.idFor(p)), nowMs, MeshPolicy.expireAt(nowMs), 0, false, p, 0)
        }
        (legacy + own).forEach { dao.upsert(it) }
        w.b.restart()
        assertEquals("no legacy row deleted on restart", (legacy + own).map { it.id }.toSet(), w.b.rows().map { it.id }.toSet())
        nowMs += asWindowMs + 1_000
        val sent = w.a.sendOffline("after legacy restart")
        assertEquals("foreign over quota: refuse new", MeshAdmission.Rejected(MeshRejection.FULL), w.b.engine.ingest(bundle(sent.payload, nowMs), RadioPath.BLE))
        assertEquals(42, w.b.rows().size)
        assertTrue("own pool still available", w.b.engine.holdSealed("b-own-after".toByteArray()) is MeshHoldResult.Held)
        // Legacy foreign emergency bit grants no priority: own emergency rows first, then deadline order only.
        val offered = w.b.engine.offers(emptyList()).map { decoded(it).idHex }
        val expected = w.b.rows().sortedWith(compareBy({ !(it.hopCount == 0 && it.emergency) }, { it.expireAtMs }, { it.id }))
            .take(MeshPolicy.MAX_OFFERS_PER_ENCOUNTER).map { it.id }
        assertEquals(expected, offered)
    }

    // AS-13 ---------------------------------------------------------------------------------------
    @Test
    fun as13_regressionAToBToCAndCToBToAUnderHardenedPolicy() = runBlocking {
        val w = world()
        val sent = w.deliverAToC("hardened regression")
        assertEquals(listOf(sent.id), w.c.inboundIds())
        assertEquals("C hands B the receipt", 1, encounter(w.b.node(), w.c.node()).second)
        encounter(w.a.node(), w.b.node())
        assertEquals(MessageStatus.DELIVERED.name, w.a.status(sent.id))
        assertTrue(w.a.ownCustody().isEmpty())
        assertEquals(1, w.c.mls.decrypts[ct(sent.payload)])
        assertNoDirectAC()
    }

    // AS-14 / Mesh-1K-AU Gate 4 -------------------------------------------------------------------
    @Test
    fun as14_historicalOverQuotaIsRetainedWithoutUncontrolledGrowth() = runBlocking {
        val w = world()
        val dao = w.b.db.meshCustodyDao()
        val legacy = (0 until 40).map { i ->
            val p = asJunk("over", i)
            com.codeyantra.armorchat.core.database.entity.MeshCustodyEntity(
                MeshIds.hex(MeshIds.idFor(p)), nowMs - i * 1_000L, MeshPolicy.expireAt(nowMs - i * 1_000L), 1, true, p, 0,
            )
        }
        legacy.forEach { dao.upsert(it) }
        w.b.restart()
        assertEquals(40, w.b.rows().size)
        repeat(3) { i ->
            assertEquals(
                MeshAdmission.Rejected(MeshRejection.FULL),
                w.b.engine.ingest(bundle(asJunk("over-more", i), nowMs), RadioPath.BLE),
            )
        }
        assertEquals(40, w.b.rows().size)
        repeat(asOwnMax) { i ->
            assertTrue(w.b.engine.holdSealed("over-own-$i".toByteArray(), emergency = i % 2 == 0) is MeshHoldResult.Held)
        }
        assertEquals(40 + asOwnMax, w.b.rows().size)
        assertTrue(w.b.engine.holdSealed("over-own-extra".toByteArray(), emergency = true) is MeshHoldResult.Rejected)
        val ids = w.b.rows().map { it.id }.toSet()
        assertEquals(40 + asOwnMax, ids.size)
        w.b.restart()
        assertEquals(ids, w.b.rows().map { it.id }.toSet())
        assertEquals(
            MeshAdmission.Rejected(MeshRejection.FULL),
            w.b.engine.ingest(bundle(asJunk("over-after", 0), nowMs), RadioPath.BLE),
        )
        assertTrue(w.b.engine.holdSealed("over-own-after".toByteArray()) is MeshHoldResult.Rejected)
        assertEquals(ids, w.b.rows().map { it.id }.toSet())
        assertNoDirectAC()
    }
}
