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
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshDeliveredStore
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.MeshIds
import com.codeyantra.armorchat.core.mesh.MeshPolicy
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
 * Mesh-1K-O (P3): authenticated offline delivery ACK = the existing MLS DELIVERY receipt over Mesh.
 *
 * REAL production code: MeshReceiptAck (sendBinding, durableTargets, verificationTarget, stageRelease,
 * custodyIdsFor, sealAfterCommit, recordReceiptMarker); ReceiptMessagingPeer (sealDeliveryReceipt,
 * verificationTargets, processLeased -> bindInbound/previewReceipt/applyReceipt, encryptReceipt,
 * flushOutbox); MeshInboxCommit.commit with releases on a FILE-BACKED Room database (real SQLite
 * transactions; close + reopen = restart boundary); MeshDelivery.deliverDurable (lock, pre-check,
 * checkpoint/rollback, afterCommit); MeshDeliveredMarker; EmergencyMeshEngine (holdSealed, ownHeld,
 * releaseOwnCustody, phaseFor, offers, ingest, markForwarded, dropDelivered, recordDelivered,
 * hasDeliveredMarker, arm() -> restore() from mesh_custody); MeshDeliveredStore on real files;
 * MeshFrames/BundleCodec (wire unchanged); MeshCourier.ingest (exact EmergencyMeshService.ingest code);
 * SessionOrchestrator.offerMesh/sync/flush/persist/restore; InMemoryRelayNetwork for the relay leg.
 *
 * SIMULATED (stated explicitly):
 *  - ChatMessagingService is not constructible on the JVM (Hilt, Keystore, notifications): [Phone.deliver]
 *    mirrors deliverMesh after Mesh-1K-O lambda for lambda (durable, offer + installMeshAckTargets,
 *    persistInbound + stageRelease, commit + releaseOwnCustody, afterCommit + sealAfterCommit,
 *    recordAfter, recordReceiptMarker) and [Phone.sendOffline] mirrors sendText's mesh branch
 *    (holdSealed(emergency = true) + MessageEntity insert with sendBinding + persist). Contact /
 *    conversation lookup is a constant; Keystore encryption of contact_sessions is identity.
 *  - MLS is FakeMls: ciphertext is NOT authenticated (anyone knowing the session id can forge it) and
 *    replays are NOT rejected. Forged-ACK tests therefore prove the inner-record binding checks only;
 *    sender authentication and replay rejection rest on native mls-rs and are NOT proven here.
 *  - "Restart" = close the Room database file, reopen it, fresh SessionOrchestrator (restored from
 *    contact_sessions), fresh EmergencyMeshEngine (arm() -> restore() from mesh_custody) and fresh
 *    MeshDeliveredStore on the same file. Not process death in the middle of a transaction; atomicity of
 *    one transaction is SQLite's, exercised here by injected exceptions inside the transaction.
 *  - Radio is not used: an encounter = both phaseFor -> MeshFrames round trip -> offers -> markForwarded ->
 *    MeshCourier.ingest on the other phone (same order as EmergencyMeshService.exchange).
 *  - "Legacy" phones = the same wiring with the P3 sender or receiver step switched off; the real
 *    e19e30f6 APK is not run.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshReceiptAckTest {

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

    private class Pair2(val alice: Phone, val bob: Phone)

    private fun setup(): Pair2 = runBlocking {
        val alice = Phone("alice", "Bob")
        val bob = Phone("bob", "Alice")
        alice.peer = bob
        bob.peer = alice
        alice.orch.provision(alice.signer, bob.pin)
        bob.orch.provision(bob.signer, alice.pin)
        alice.orch.importPeerRoute(bob.orch.localRouteQr(alice.pin)!!, alice.signer)
        confirmSharedMls(alice.orch, bob.orch, alice.pin, bob.pin)
        for (p in listOf(alice, bob)) {
            p.savedMailbox = p.link().peer.mailbox
            p.db.conversationDao().insert(ConversationEntity(p.conversationId, p.contactId, p.title, nowMs))
            p.persistSession()
            p.engine.arm()
        }
        Pair2(alice, bob)
    }

    /** One BLE encounter: phases, offers, markForwarded, then each side ingests (EmergencyMeshService order). */
    private suspend fun encounter(a: Phone, b: Phone): kotlin.Pair<Int, Int> {
        val aPhase = MeshFrames.decodePhase(MeshFrames.encodePhase(a.engine.phaseFor(emptySet(), false)))!!
        val bPhase = MeshFrames.decodePhase(MeshFrames.encodePhase(b.engine.phaseFor(emptySet(), false)))!!
        val aOffers = a.engine.offers(bPhase.ids)
        val bOffers = b.engine.offers(aPhase.ids)
        fun ids(o: List<ByteArray>) = o.mapNotNull { (BundleCodec.decode(it) as? BundleDecode.Ok)?.bundle?.id }
        if (aOffers.isNotEmpty()) a.engine.markForwarded(ids(aOffers), RadioPath.BLE)
        if (bOffers.isNotEmpty()) b.engine.markForwarded(ids(bOffers), RadioPath.BLE)
        MeshCourier.ingest(MeshFrames.encodeBundles(aOffers), RadioPath.BLE, { e, v -> b.engine.ingest(e, v) }, { b.deliver(it) }, { b.engine.dropDelivered(it) })
        MeshCourier.ingest(MeshFrames.encodeBundles(bOffers), RadioPath.BLE, { e, v -> a.engine.ingest(e, v) }, { a.deliver(it) }, { a.engine.dropDelivered(it) })
        return aOffers.size to bOffers.size
    }

    /** Bob seals an arbitrary (possibly forged) receipt with his real session; returns envelope bytes. */
    private fun bobSealed(bob: Phone, inner: InnerRecord): ByteArray {
        val peer = bob.link().peer
        val enc = peer.encryptRaw(inner.encode())
        return peer.makeApplicationEnvelope(enc.ciphertextB64, inner.messageId, inner.expiresAt)
            .canonicalJson().toByteArray(Charsets.UTF_8)
    }

    private suspend fun assertPending(alice: Phone, sent: Sent, why: String) {
        assertEquals("$why: status", MessageStatus.QUEUED.name, alice.status(sent.id))
        assertEquals("$why: own custody row kept", 1, alice.ownCustody().size)
        assertEquals("$why: memory custody kept", 1, alice.engine.ownHeld().size)
    }

    // 1 Offline first delivery + ACK + sender custody release -------------------------------------
    @Test
    fun offlineFirstDeliveryAckReleasesSenderCustodyAndStopsResend() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("hello offline")
        assertPending(alice, sent, "after send")
        assertEquals(MeshDelivery.outerMessageId(sent.payload), sent.id)
        assertEquals(sent.digest, alice.db.messageDao().findById(sent.id)!!.receiptCiphertextSha256)

        val first = encounter(alice, bob)
        assertEquals(1 to 0, first)
        assertEquals(listOf(sent.id), bob.inboundIds())
        assertEquals(MeshReceiptAck.Sealed.HELD, bob.lastSeal)
        assertEquals("MLS persisted before the receipt left memory", listOf("persist", "hold"), bob.events)
        val bobReceipts = bob.ownCustody()
        assertEquals("exactly one receipt bundle held by the receiver", 1, bobReceipts.size)
        assertFalse("receipt bundle held with emergency=false", bobReceipts.single().emergency)
        val receiptPayload = bobReceipts.single().payload
        val receiptId = MeshDelivery.outerMessageId(receiptPayload)!!
        assertTrue(receiptId in bob.receiptIdsInStore())
        assertEquals("the relay outbox keeps the identical sealed bytes",
            bob.receipts().store.receipt(receiptId)!!.envelopeCanonical, String(receiptPayload, Charsets.UTF_8))
        assertEquals("no ACK yet on the sender", MessageStatus.QUEUED.name, alice.status(sent.id))
        assertEquals(1, alice.ownCustody().size)

        val second = encounter(alice, bob)
        assertEquals("sender skips (receiver marker), receiver offers its receipt", 0 to 1, second)
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertEquals(nowMs, alice.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs)
        assertEquals(MessageStatus.DELIVERED, alice.receipts().status(sent.id))
        assertTrue("sender custody deleted in Room", alice.ownCustody().isEmpty())
        assertTrue("sender custody dropped in memory", alice.engine.ownHeld().isEmpty())
        assertTrue("receipt copy not kept by the sender", alice.custodyRows().isEmpty())
        assertEquals(true, alice.lastReceiptMarker)
        assertTrue(alice.advertises(receiptPayload))

        val third = encounter(alice, bob)
        assertEquals("storm over: nothing offered either way", 0 to 0, third)
        assertEquals(listOf(sent.id), bob.inboundIds())
        assertEquals(1, bob.mls.decrypts[JSONObject(String(sent.payload)).getString("encrypted_payload")])
    }

    // 2 No ACK before durable commit -----------------------------------------------------------------
    @Test
    fun noReceiptBeforeDurableInboxAndMlsCommit() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("commit first")
        bob.failSessionUpsert = true
        encounter(alice, bob)
        assertEquals(MeshDelivery.Outcome.PERSIST_FAILED, bob.outcomes.last())
        assertNull("afterCommit never ran", bob.lastSeal)
        assertTrue(bob.inboundIds().isEmpty())
        assertTrue("no receipt intent survives the rollback", bob.receiptIdsInStore().isEmpty())
        assertTrue("no receipt bundle", bob.ownCustody().isEmpty())
        assertPending(alice, sent, "receiver commit failed")

        bob.failSessionUpsert = false
        bob.failReceiptPersist = true
        bob.engine.recoveryCandidates().forEach { assertTrue(bob.deliver(it.payload)); bob.engine.dropDelivered(it.idHex) }
        assertEquals(listOf(sent.id), bob.inboundIds())
        assertEquals("inbox commit stands, receipt rolled back", MeshReceiptAck.Sealed.ROLLED_BACK, bob.lastSeal)
        assertTrue("no receipt bundle when its MLS state could not be persisted", bob.ownCustody().isEmpty())
        val intent = bob.receipts().store.receipts().single()
        assertNull("receipt intent back to unsealed", intent.envelope)
        assertEquals(AttemptState.QUEUED, intent.progress.attempt)
        assertEquals("memory MLS == durable MLS", bob.db.contactSessionDao().find(bob.contactId)!!.mlsSnapshot!!.toList(),
            bob.link().peer.snapshot().toList())
        assertPending(alice, sent, "receipt not sealed")
    }

    // 3 Status/custody atomicity and transaction rollback --------------------------------------------
    @Test
    fun statusAndCustodyReleaseAreOneTransactionAndRollBackTogether() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("atomic")
        encounter(alice, bob)
        val sessionBefore = alice.db.contactSessionDao().find(alice.contactId)!!
        alice.failSessionUpsert = true // fails AFTER updateStatusDelivered + custody delete ran in the tx
        encounter(alice, bob)
        assertEquals(MeshDelivery.Outcome.PERSIST_FAILED, alice.outcomes.last())
        assertPending(alice, sent, "tx failed after status+delete")
        assertEquals("in-memory status rolled back", MessageStatus.QUEUED, alice.receipts().status(sent.id))
        assertEquals(sessionBefore.updatedAtEpochMs, alice.db.contactSessionDao().find(alice.contactId)!!.updatedAtEpochMs)
        assertEquals("receipt copy kept as carrier for retry", 1, alice.custodyRows().count { it.hopCount == 1 })

        alice.failSessionUpsert = false
        alice.failRelease = true // fails inside the tx before any release effect
        alice.engine.recoveryCandidates().forEach { assertFalse(alice.deliver(it.payload)) }
        assertPending(alice, sent, "release failed")

        alice.failRelease = false
        alice.engine.recoveryCandidates().forEach { assertTrue(alice.deliver(it.payload)); alice.engine.dropDelivered(it.idHex) }
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.custodyRows().isEmpty())
        assertTrue(alice.engine.ownHeld().isEmpty())
    }

    // 4 Duplicate ACK ----------------------------------------------------------------------------------
    @Test
    fun duplicateAckIsHarmlessAndNeverRegressesRead() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("dup ack")
        encounter(alice, bob)
        val receipt = bob.ownCustody().single().payload
        alice.markerIo.failWrite = true // first ACK: marker cannot be written
        assertTrue(alice.deliver(receipt))
        assertEquals(false, alice.lastReceiptMarker)
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        val deliveredAt = alice.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs
        val ct = JSONObject(String(receipt)).getString("encrypted_payload")
        assertEquals(1, alice.mls.decrypts[ct])

        alice.db.openHelper.writableDatabase.execSQL("UPDATE messages SET status = 'READ' WHERE id = ?", arrayOf(sent.id))
        nowMs += 60_000
        alice.markerIo.failWrite = false
        assertTrue("in-process replay (seen store) is accepted idempotently", alice.deliver(receipt))
        assertEquals("no second decrypt", 1, alice.mls.decrypts[ct])
        assertEquals("READ never regresses", MessageStatus.READ.name, alice.status(sent.id))
        assertEquals(deliveredAt, alice.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs)
        assertEquals("marker written on the replay", true, alice.lastReceiptMarker)
        repeat(3) { assertTrue(alice.deliver(receipt)) }
        assertEquals(MeshDelivery.Outcome.DUPLICATE, alice.outcomes.last())
        assertEquals(1, alice.mls.decrypts[ct])
        assertTrue(alice.ownCustody().isEmpty())
    }

    // 5-8 Forged, wrong-recipient, wrong-session, orphan ACK -----------------------------------------
    @Test
    fun forgedWrongRecipientWrongSessionAndOrphanAcksNeverRelease() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("forgery target")
        val target = alice.receipts().outbound(sent.id)
        val b = bob.receipts()
        fun body(r: InnerRecord) = r.body as InnerBody.Receipt
        val cases = linkedMapOf<String, ByteArray>(
            "forged digest" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetCiphertextSha256 = Base64Url.encode(ByteArray(32) { 4 }))) }),
            "forged epoch" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetEpoch = 99L)) }),
            "wrong recipient pin" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(recipientIdentityKey = TinkSigner().pin) }),
            "wrong recipient mailbox" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(recipientMailbox = "mailbox-of-someone-else") }),
            "wrong recipient device" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(recipientDeviceId = "other-device") }),
            "wrong sender pin" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(senderIdentityKey = TinkSigner().pin) }),
            "wrong target session" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetSessionId = Base64Url.encode(ByteArray(16) { 8 }))) }),
            "orphan target" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetMessageId = Base64Url.encode(ByteArray(16) { 9 }))) }),
        )
        // Ciphertext of another MLS session (FakeMls binds ciphertext to the session id).
        val genuine = bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target))
        val otherSession = JSONObject(String(genuine)).apply {
            put("encrypted_payload", Base64Url.encode("E|other-session|x".toByteArray()))
        }.toString().toByteArray()
        cases["wrong MLS session ciphertext"] = otherSession
        cases["undecryptable"] = JSONObject(String(genuine)).apply { put("encrypted_payload", "AAAA") }.toString().toByteArray()

        for ((why, payload) in cases) {
            assertFalse(why, alice.deliver(payload))
            assertPending(alice, sent, why)
            assertTrue(why, alice.lastApplied.isEmpty())
            assertNull(why, alice.lastReceiptMarker)
            assertEquals(why, MessageStatus.QUEUED, alice.receipts().status(sent.id))
        }
        // After a restart (durable verification targets), the same forgeries still fail.
        alice.restart()
        for ((why, payload) in cases) {
            assertFalse("after restart: $why", alice.deliver(payload))
            assertPending(alice, sent, "after restart: $why")
        }
        // Control: the genuine receipt from the same session is accepted.
        assertTrue(alice.deliver(genuine))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.ownCustody().isEmpty())
    }

    // 9 Delayed ACK ------------------------------------------------------------------------------------
    @Test
    fun delayedAckReleasesWithinTtlAndStillConfirmsAfterSenderCustodyExpired() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("delayed 1")
        encounter(alice, bob)
        nowMs += 3 * 3_600_000L
        encounter(alice, bob)
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.ownCustody().isEmpty())

        val late = alice.sendOffline("delayed 2")
        nowMs += 60 * 60_000L
        encounter(alice, bob) // bob gets it at +1h; his receipt lives until +5h
        nowMs += MeshPolicy.ABSOLUTE_TTL_MS - 30 * 60_000L // sender custody (+4h) has expired
        alice.engine.sweep()
        assertTrue(alice.ownCustody().isEmpty())
        assertEquals(MessageStatus.QUEUED.name, alice.status(late.id))
        encounter(alice, bob)
        assertEquals("in-memory target still verifies a late ACK", MessageStatus.DELIVERED.name, alice.status(late.id))
        assertEquals(emptyList<String>(), alice.lastApplied.single().custodyIds)
    }

    // 10 Restart before receipt transmission (receiver) -------------------------------------------------
    @Test
    fun receiverRestartAfterSealingStillDeliversTheHeldReceipt() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("receiver restart")
        encounter(alice, bob)
        val receipt = bob.ownCustody().single().payload
        bob.restart()
        assertEquals("receipt bundle survived the restart in mesh_custody", receipt.toList(), bob.ownCustody().single().payload.toList())
        assertTrue("in-memory receipt outbox is gone after restart", bob.receiptIdsInStore().isEmpty())
        encounter(alice, bob)
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.ownCustody().isEmpty())
    }

    // 11 Restart before receipt processing (sender) -----------------------------------------------------
    @Test
    fun senderRestartVerifiesAgainstDurableRowAndLegacyRowFailsClosed() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("sender restart")
        val legacy = alice.sendOffline("legacy row")
        // A pre-1K-O row: receipt* columns null (as on e19e30f6 today).
        alice.db.openHelper.writableDatabase.execSQL(
            "UPDATE messages SET receiptSessionId = NULL, receiptCiphertextSha256 = NULL, receiptEpoch = NULL WHERE id = ?",
            arrayOf(legacy.id),
        )
        encounter(alice, bob)
        assertEquals(2, bob.ownCustody().size)
        alice.restart()
        assertTrue("in-memory outgoing records are gone", alice.receipts().store.texts().isEmpty())
        encounter(alice, bob)
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertEquals("legacy row cannot be verified: stays pending", MessageStatus.QUEUED.name, alice.status(legacy.id))
        val left = alice.ownCustody()
        assertEquals(1, left.size)
        assertEquals(legacy.id, MeshDelivery.outerMessageId(left.single().payload))
        assertTrue("restart never falsely marks delivered", alice.db.messageDao().findById(legacy.id)!!.deliveredAtEpochMs == null)
    }

    // 12 Receipt bundle duplicate suppression -----------------------------------------------------------
    @Test
    fun receiptBundleIsSuppressedByTheSendersMarkerAcrossRestart() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        alice.sendOffline("suppress")
        encounter(alice, bob)
        val receipt = bob.ownCustody().single().payload
        encounter(alice, bob)
        assertTrue(alice.advertises(receipt))
        alice.restart()
        assertTrue("marker persisted", alice.advertises(receipt))
        repeat(3) { assertEquals(0 to 0, encounter(alice, bob)) }
        assertEquals("receiver still holds its receipt (no ACK-of-ACK), it just stops offering", 1, bob.ownCustody().size)
        assertTrue(alice.deliver(receipt))
        assertEquals("exact replay is DUPLICATE before any decrypt", MeshDelivery.Outcome.DUPLICATE, alice.outcomes.last())
    }

    // 13 Relay replay after Mesh delivery ---------------------------------------------------------------
    @Test
    fun relayCopiesAfterMeshDeliveryAreDedupedAndCarryIdenticalReceiptBytes() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("relay too")
        encounter(alice, bob) // Mesh delivery + receipt sealed
        // Sender reconnects before the ACK: its QUEUED outbox resubmits the same ciphertext.
        alice.receipts().submitInterceptor = null
        nowMs += 30 * 60_000L
        alice.lock.withLock { alice.orch.flush(bob.pin) }
        // Receiver syncs the relay (mirrors syncContact -> persistInbound findById guard).
        val captured = mutableListOf<String>()
        bob.receipts().submitInterceptor = { env -> captured += env.canonicalJson(); null }
        var relayInserts = 0
        bob.lock.withLock {
            bob.orch.sync(alice.pin) { inbound ->
                if (inbound.kind.isUserMessage && inbound.text != null && bob.db.messageDao().findById(inbound.messageId) == null) {
                    bob.db.messageDao().insert(MeshInboxCommit.inboundRow(bob.conversationId, inbound, nowMs)!!)
                    relayInserts++
                }
            }
        }
        assertEquals("relay replay inserts nothing", 0, relayInserts)
        assertEquals(listOf(sent.id), bob.inboundIds())
        val ct = JSONObject(String(sent.payload)).getString("encrypted_payload")
        assertEquals("relay replay not decrypted again in-process", 1, bob.mls.decrypts[ct])
        val meshReceipt = String(bob.ownCustody().single().payload, Charsets.UTF_8)
        assertTrue("relay flush submitted the IDENTICAL receipt envelope", captured.contains(meshReceipt))

        encounter(alice, bob)
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.ownCustody().isEmpty())
        val rct = JSONObject(meshReceipt).getString("encrypted_payload")
        alice.lock.withLock { alice.orch.sync(bob.pin) } // relay copy of the same receipt
        assertEquals("relay receipt copy not decrypted again", 1, alice.mls.decrypts[rct])
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertEquals(MessageStatus.DELIVERED, alice.receipts().status(sent.id))
    }

    // 14 Legacy peers ------------------------------------------------------------------------------------
    @Test
    fun legacyReceiverNeverReleasesAndInventoryNeverDeletesCustody() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        bob.p3Receiver = false
        val sent = alice.sendOffline("legacy receiver")
        encounter(alice, bob)
        assertEquals(listOf(sent.id), bob.inboundIds())
        assertTrue("legacy receiver holds no receipt bundle", bob.ownCustody().isEmpty())
        assertTrue("receiver advertises the delivered id (P2 marker)", bob.advertises(sent.payload))
        repeat(3) { encounter(alice, bob) }
        assertPending(alice, sent, "inventory advertisement is not an ACK")
    }

    @Test
    fun legacySenderAcceptsTheReceiptBundleWithoutCrashOrRelease() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        alice.p3Sender = false
        val sent = alice.sendOffline("legacy sender")
        encounter(alice, bob)
        val receipt = bob.ownCustody().single().payload
        assertTrue("receipt bundle uses the existing bundle codec", BundleCodec.decode(BundleCodec.encode(bob.engine.ownHeld().single())) is BundleDecode.Ok)
        assertEquals(ImmutableRelayEnvelope.PAYLOAD_APPLICATION, JSONObject(String(receipt)).getString("payload_type"))
        encounter(alice, bob)
        assertEquals("pre-P3 sender: in-memory receipt only", MessageStatus.DELIVERED, alice.receipts().status(sent.id))
        assertPending(alice, sent, "pre-P3 sender never releases")
        assertTrue("receipt copy dropped like any delivered bundle", alice.custodyRows().none { it.hopCount == 1 })
    }

    // 15 No receipt for rejected / conflicting / duplicate --------------------------------------------
    @Test
    fun noReceiptForRejectedConflictingOrDuplicateMessages() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val clash = alice.sendOffline("conflict")
        bob.db.messageDao().insert(
            MessageEntity(
                id = clash.id, conversationId = bob.conversationId, type = MessageType.TEXT.name, isOutgoing = false,
                localPlaintext = "other", ciphertext = null, status = MessageStatus.DELIVERED.name,
                createdAtEpochMs = nowMs, receiptCiphertextSha256 = "different-digest",
            ),
        )
        assertFalse(bob.deliver(clash.payload))
        assertEquals(MeshDelivery.Outcome.CONFLICT, bob.outcomes.last())
        assertNull(bob.lastSeal)
        assertTrue(bob.receiptIdsInStore().isEmpty())

        val garbage = JSONObject(String(clash.payload)).apply {
            put("message_id", Base64Url.encode(ByteArray(16) { 3 }))
            put("encrypted_payload", "AAAA")
        }.toString().toByteArray()
        assertFalse(bob.deliver(garbage))
        assertNull(bob.lastSeal)
        assertTrue(bob.ownCustody().isEmpty())

        val ok = alice.sendOffline("once")
        assertTrue(bob.deliver(ok.payload))
        assertEquals(MeshReceiptAck.Sealed.HELD, bob.lastSeal)
        bob.lastSeal = null
        repeat(3) { assertTrue(bob.deliver(ok.payload)) }
        assertEquals(MeshDelivery.Outcome.DUPLICATE, bob.outcomes.last())
        assertNull("duplicate never re-seals", bob.lastSeal)
        assertEquals("exactly one receipt bundle", 1, bob.ownCustody().size)
        assertEquals(1, bob.receiptIdsInStore().size)
    }

    // 16 Hold failure keeps the relay path; MLS already durable ----------------------------------------
    @Test
    fun custodyHoldFailureLeavesSealedReceiptForRelayOnly() = runBlocking {
        val (alice, bob) = setup().let { it.alice to it.bob }
        val sent = alice.sendOffline("hold fails")
        bob.failHold = true
        encounter(alice, bob)
        assertEquals(MeshReceiptAck.Sealed.HOLD_FAILED, bob.lastSeal)
        assertEquals(listOf("persist"), bob.events)
        assertTrue(bob.ownCustody().isEmpty())
        val intent = bob.receipts().store.receipts().single()
        assertNotNull("sealed receipt kept for the relay flush", intent.envelope)
        assertPending(alice, sent, "no Mesh ACK")
    }
}
