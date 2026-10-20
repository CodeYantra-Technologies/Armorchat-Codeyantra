package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.dao.MeshCustodyDao
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.core.database.entity.MeshCustodyEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.mesh.BundleCodec
import com.codeyantra.armorchat.core.mesh.CustodyBundle
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshDeliveredStore
import com.codeyantra.armorchat.core.mesh.MeshDirect
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.MeshAdmission
import com.codeyantra.armorchat.core.mesh.MeshHoldResult
import com.codeyantra.armorchat.core.mesh.MeshHoldRetryStore
import com.codeyantra.armorchat.core.mesh.MeshIds
import com.codeyantra.armorchat.core.mesh.MeshRejection
import com.codeyantra.armorchat.core.mesh.MeshPolicy
import com.codeyantra.armorchat.core.mesh.RadioPath
import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Mesh-1K-R: full-custody ACK liveness (fix A: slot-less direct local delivery; fix D1: typed hold
 * result + bounded in-memory same-bytes receipt retry).
 *
 * REAL production code: MeshCourier.ingest incl. the direct branch and budget (exact
 * EmergencyMeshService.ingest arguments, see [Phone.ingestFrom]); EmergencyMeshEngine (ingest/accept with
 * the Mesh-1K-AS pools: own hop 0 capped at 16, foreign hop >= 1 capped at 32, refuse never evicts,
 * holdSealed typed result + crash-durable receipt retry, retry triggers sweep/phaseFor/dropDelivered/releaseOwnCustody,
 * ownHeld, recordDelivered fallback retention, arm() -> restore()); MeshDelivery.deliverDurable incl. the
 * `addressed` gate, MeshDelivery.direct / addressedHere / outerRecipientMailbox;
 * SessionOrchestrator.localMailboxIds/offerMesh; ReceiptMessagingPeer
 * (processLeased -> bindInbound/previewReceipt/applyReceipt, sealDeliveryReceipt); MeshReceiptAck
 * (stageRelease, sealAfterCommit typed admit, recordReceiptMarker); MeshInboxCommit on a FILE-BACKED Room
 * database (real SQLite transactions); MeshDeliveredStore on real files; BundleCodec/MeshFrames (wire unchanged).
 *
 * SIMULATED (stated explicitly):
 *  - ChatMessagingService is not constructible on the JVM: [Phone.deliver] COPIES deliverMeshResult after
 *    Mesh-1K-R lambda for lambda (incl. the localOnly `addressed` gate and the pre-filter cache refresh in
 *    load), [Phone.sendOffline] copies sendText's mesh branch. Contact lookup is a constant.
 *  - MLS is FakeMls: ciphertext is NOT authenticated and replays are NOT rejected by MLS; forged-ACK cases
 *    prove the inner-record binding checks + Mesh-layer logic only. Real mls-rs is not exercised.
 *  - "Restart" = close/reopen the Room file + fresh orchestrator/engine/marker store and the same
 *    mesh_hold_retry file (no mid-transaction kill). A missing retry file loads as an empty queue.
 *  - Lock timeout uses a 300 ms lockWaitMs instead of the production 12 s (same code path).
 *  - Foreign custody rows are synthetic bundles addressed to a non-existent mailbox. "Foreign full" is
 *    [MeshPolicy.FOREIGN_MAX] emergency rows, admitted across one rate-window advance. A receipt is
 *    HOLD_QUEUED only when the own pool is also full. Those own rows are seeded near expiry through
 *    Room and reloaded by arm(), because holdSealed always stamps createdAt = now.
 *  - Persistence-failure retry exhaustion uses a fault-injecting MeshCustodyDao wrapper around the real DAO.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshFullCustodyAckTest {

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
        @Volatile var encrypts = 0
        fun transact(state: ByteArray, request: String): ByteArray {
            val req = CanonicalJson.parseObject(request)
            if (req["op"] == "Decrypt") {
                val ct = req["ciphertext"] as String
                synchronized(decrypts) { decrypts[ct] = (decrypts[ct] ?: 0) + 1 }
            }
            if (req["op"] == "Encrypt") encrypts++
            return FakeMls.transact(state, request)
        }
        fun totalDecrypts() = synchronized(decrypts) { decrypts.values.sum() }
    }

    private class HoldFaultIo : MeshHoldRetryStore.Io {
        @Volatile var failWrite = false
        @Volatile var interruptWrite = false
        @Volatile var failRead = false
        override fun read(file: File): ByteArray? {
            if (failRead) throw IOException("simulated retry read failure")
            return MeshHoldRetryStore.FileIo.read(file)
        }
        override fun writeAtomic(file: File, bytes: ByteArray) {
            if (failWrite) throw IOException("simulated retry write failure")
            if (interruptWrite) {
                File(file.parentFile, file.name + MeshHoldRetryStore.TMP_SUFFIX)
                    .writeBytes(bytes.copyOf(bytes.size / 2))
                throw IOException("simulated crash before retry rename")
            }
            MeshHoldRetryStore.FileIo.writeAtomic(file, bytes)
        }
        override fun rename(file: File, target: File): Boolean = MeshHoldRetryStore.FileIo.rename(file, target)
    }

    private class FaultDao(val real: MeshCustodyDao) : MeshCustodyDao {
        @Volatile var failUpsert = false
        override suspend fun all(): List<MeshCustodyEntity> = real.all()
        override suspend fun upsert(row: MeshCustodyEntity) {
            if (failUpsert) throw IOException("simulated mesh_custody write failure")
            real.upsert(row)
        }
        override suspend fun delete(id: String) = real.delete(id)
        override suspend fun deleteExpired(nowMs: Long) = real.deleteExpired(nowMs)
    }

    private var nowMs = 1_800_000_000_000L
    private val clock = AppClock { nowMs }
    private val origin = "https://relay-mesh-1kr.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-1kr", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val app get() = RuntimeEnvironment.getApplication()
    private val openDbs = mutableListOf<ArmorChatDatabase>()
    private var foreignSeq = 0

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

    @Before
    fun logs() {
        ShadowLog.clear()
    }

    @After
    fun close() {
        openDbs.forEach { runCatching { it.close() } }
    }

    private fun armorLogs(): List<String> = ShadowLog.getLogsForTag("ArmorChat").map { it.msg }

    private inner class Phone(val name: String, val title: String) {
        val signer = TinkSigner()
        val pin get() = signer.pin
        val mls = CountingMls()
        val contactId = "contact-of-$name"
        val conversationId = "conversation-of-$name"
        val markerIo = MeshDeliveredStore.FileIo
        val holdIo = HoldFaultIo()
        val lock = Mutex()
        lateinit var peer: Phone
        var db: ArmorChatDatabase = openDb()
        var dao = FaultDao(db.meshCustodyDao())
        var orch: SessionOrchestrator = newOrch(mls::transact)
        var engine: EmergencyMeshEngine = newEngine()
        var savedMailbox: com.codeyantra.armorchat.core.network.MailboxRegistration? = null

        /** Copy of ChatMessagingService.meshLocalMailboxes (null = unknown, fresh process). */
        @Volatile var knownMailboxes: Set<String>? = null
        var directEnabled = true
        var lockWaitMs = MeshDelivery.MESH_LOCK_WAIT_MS
        var failSessionUpsert = false
        var failRelease = false
        val outcomes: MutableList<MeshDelivery.Outcome> = Collections.synchronizedList(mutableListOf())
        val directCalls: MutableList<ByteArray> = Collections.synchronizedList(mutableListOf())
        val directReports: MutableList<Map<MeshDirect, Int>> = Collections.synchronizedList(mutableListOf())
        val sealLogs: MutableList<String> = Collections.synchronizedList(mutableListOf())
        var lastSeal: MeshReceiptAck.Sealed? = null
        var lastReceiptMarker: Boolean? = null
        var lastApplied: List<MeshInboxCommit.Release> = emptyList()

        fun openDb(): ArmorChatDatabase =
            Room.databaseBuilder(app, ArmorChatDatabase::class.java, File(tmp.root, "$name.db").absolutePath)
                .allowMainThreadQueries().build().also { openDbs += it }

        fun newEngine() = EmergencyMeshEngine(app, dao, clock).also {
            it.delivered = MeshDeliveredStore(File(tmp.root, "$name.markers"), markerIo)
            it.holdRetryStore = MeshHoldRetryStore(
                file = File(tmp.root, "$name.holdretry"),
                io = holdIo,
                log = { detail -> runCatching { SafeLog.event("mesh_hold_retry_store", detail) } },
            )
        }

        fun retryFile() = File(tmp.root, "$name.holdretry")

        fun link() = orch.link(peer.pin)!!
        fun receipts() = link().receipts
        fun mailbox(): String = link().peer.ownMailboxId()

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

        /** Restart: reopen the Room file, fresh orchestrator, engine (arm -> restore), markers; caches reset. */
        fun restart() = runBlocking {
            db.close()
            db = openDb()
            dao = FaultDao(db.meshCustodyDao())
            orch = newOrch(mls::transact)
            engine = newEngine()
            engine.arm()
            knownMailboxes = null
        }

        /** Mirrors ChatMessagingService.sendText with the relay down and mesh armed. */
        suspend fun sendOffline(text: String): Sent = lock.withLock {
            load()
            receipts().submitInterceptor = { RelaySubmitResult.Unavailable }
            val record = orch.sendText(peer.pin, text)
            check(record.progress.status != MessageStatus.SENT)
            val canonical = record.envelopeCanonical!!
            val hold = engine.holdSealed(canonical.toByteArray(Charsets.UTF_8), emergency = true)
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
            Sent(record.messageId, canonical.toByteArray(Charsets.UTF_8), record.ciphertextSha256!!, hold)
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

        suspend fun deliver(payload: ByteArray): Boolean = deliverResult(payload, localOnly = false).first

        /** Copy of ChatMessagingService.deliverMeshDirect. */
        suspend fun deliverDirect(payload: ByteArray): MeshDirect {
            directCalls += payload
            val (ok, outcome) = deliverResult(payload, localOnly = true)
            return MeshDelivery.direct(ok, outcome)
        }

        // WIRING: copy of ChatMessagingService.deliverMeshResult after Mesh-1K-R ---------------------------
        suspend fun deliverResult(payload: ByteArray, localOnly: Boolean): kotlin.Pair<Boolean, MeshDelivery.Outcome?> {
            val staged = mutableListOf<MeshInboxCommit.Staged>()
            var committed: List<MeshInboxCommit.Staged> = emptyList()
            var commitOutcome = MeshDelivery.Outcome.DELIVERED
            var finalOutcome: MeshDelivery.Outcome? = null
            val releases = mutableListOf<MeshInboxCommit.Release>()
            var applied: List<MeshInboxCommit.Release> = emptyList()
            val ok = MeshDelivery.deliverDurable(
                lock = lock,
                payload = payload,
                lockWaitMs = lockWaitMs,
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
                load = {
                    load()
                    knownMailboxes = runCatching { orch.localMailboxIds() }.getOrNull()
                },
                addressed = if (!localOnly) {
                    null
                } else {
                    {
                        val mailbox = MeshDelivery.outerRecipientMailbox(payload)
                        mailbox != null && mailbox in orch.localMailboxIds()
                    }
                },
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
                    val installed = installTargets()
                    try {
                        orch.offerMesh(raw, cb)
                    } finally {
                        installed.forEach { it.verificationTargets = emptyMap() }
                    }
                },
                persistInbound = { p, inbound ->
                    if (inbound.kind == InnerKind.DELIVERY) {
                        val target = inbound.receiptTargetMessageId?.let { orch.link(p)?.receipts?.store?.text(it) }
                        MeshReceiptAck.stageRelease(p, conversationId, inbound, target, engine.ownHeld(), nowMs)
                            ?.let { releases += it }
                    }
                    MeshInboxCommit.inboundRow(conversationId, inbound, nowMs)?.let { e ->
                        staged += MeshInboxCommit.Staged(p, e, title) { }
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
                    lastSeal = MeshReceiptAck.sealAfterCommit(
                        targets = committed.map { it.peerPin to it.message.id },
                        checkpoint = { orch.meshCheckpoint() },
                        rollback = { orch.restoreMeshCheckpoint(it) },
                        seal = { p, id -> orch.link(p)?.receipts?.sealDeliveryReceipt(id) },
                        persist = { p -> db.contactSessionDao().upsert(sessionRow(orch.persist(p) ?: error("mesh session missing"))) },
                        admit = { bytes -> engine.holdSealed(bytes, emergency = false, retryIfFull = true) },
                        log = { sealLogs += it },
                    )
                },
            )
            MeshDeliveredMarker.recordAfter(payload, ok, finalOutcome, committed, { db.messageDao().findById(it) }) { b, id, binding ->
                engine.recordDelivered(b, id, binding)
            }
            lastReceiptMarker = MeshReceiptAck.recordReceiptMarker(payload, ok, finalOutcome, applied) { b, id, binding ->
                engine.recordDelivered(b, id, binding)
            }
            lastApplied = applied
            if (ok) MeshInboxCommit.runAfterCommit(committed)
            return ok to finalOutcome
        }

        /** EmergencyMeshService.ingest with the exact Mesh-1K-R MeshCourier arguments. */
        suspend fun ingestFrom(encoded: List<ByteArray>) {
            MeshCourier.ingest(
                bytes = MeshFrames.encodeBundles(encoded),
                path = RadioPath.BLE,
                store = { e, v -> engine.ingest(e, v) },
                deliver = { p -> deliver(p) },
                dropDelivered = { id -> engine.dropDelivered(id) },
                addressedHere = if (directEnabled) { p -> MeshDelivery.addressedHere(p, knownMailboxes) } else null,
                deliverDirect = if (directEnabled) { p -> deliverDirect(p) } else null,
                reportDirect = { counts ->
                    directReports += counts
                    // same line EmergencyMeshService logs
                    SafeLog.event("mesh_direct", counts.entries.joinToString(" ") { "${it.key.name.lowercase()}=${it.value}" })
                },
            )
        }

        suspend fun status(id: String): String? = db.messageDao().findById(id)?.status
        suspend fun custodyRows() = db.meshCustodyDao().all()
        suspend fun custodyIds() = custodyRows().map { it.id }.toSet()
        suspend fun ownCustody() = custodyRows().filter { it.hopCount == 0 }
        suspend fun inboundIds() = db.messageDao().observeAllMessages().first().filter { !it.isOutgoing }.map { it.id }
        fun advertises(payload: ByteArray): Boolean = runBlocking {
            engine.phaseFor(emptySet(), false).ids.any { it.contentEquals(MeshIds.idFor(payload)) }
        }
        fun mlsState(): List<Byte> = link().peer.snapshot().toList()
    }

    private data class Sent(val id: String, val payload: ByteArray, val digest: String, val hold: MeshHoldResult)

    private fun setup(a: String = "alice", b: String = "bob"): kotlin.Pair<Phone, Phone> = runBlocking {
        val alice = Phone(a, "Bob")
        val bob = Phone(b, "Alice")
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
        alice to bob
    }

    private fun bundle(payload: ByteArray, createdAt: Long = nowMs, emergency: Boolean = false) = BundleCodec.encode(
        CustodyBundle(MeshIds.idFor(payload), createdAt, MeshPolicy.expireAt(createdAt), 0, emergency, payload),
    )

    private fun foreignPayload(
        mailbox: String = "mailbox-of-a-stranger",
        expiresAtSeconds: Long? = null,
    ): ByteArray {
        val n = foreignSeq++
        val expires = expiresAtSeconds
            ?: (nowMs / 1000 + MeshPolicy.ABSOLUTE_TTL_MS / 1000 + MeshPolicy.CLOCK_SKEW_MS / 1000)
        return JSONObject()
            .put("protocol_version", 1).put("suite", "x").put("message_id", "foreign-$n").put("recipient_mailbox", mailbox)
            .put("expires_at", expires)
            .put("payload_type", "application")
            .put("encrypted_payload", Base64Url.encode("opaque-$n".toByteArray()))
            .toString().toByteArray()
    }

    /**
     * Fills [p]'s foreign pool to [MeshPolicy.FOREIGN_MAX]. At most [MeshPolicy.FOREIGN_RATE_MAX]
     * admits share one window; the clock advances once between the two windows and, when this call
     * admitted anything, once more so a following refusal is FULL (the direct path), not RATE.
     * A phone that is already at the cap is left on the clock. Existing own rows are kept.
     */
    private suspend fun fill(p: Phone, createdAt: Long = nowMs - MeshPolicy.ABSOLUTE_TTL_MS + 30 * 60_000L) {
        val ownBefore = p.ownCustody().map { it.id }.toSet()
        var admitted = 0
        var rateAdvances = 0
        while (p.custodyRows().count { it.hopCount >= 1 } < MeshPolicy.FOREIGN_MAX) {
            check(admitted <= MeshPolicy.FOREIGN_MAX) { "foreign fill did not converge" }
            val result = p.engine.ingest(bundle(foreignPayload(), createdAt, emergency = true), RadioPath.BLE)
            when {
                result is MeshAdmission.Admitted -> admitted++
                result is MeshAdmission.Rejected && result.reason == MeshRejection.RATE -> {
                    check(rateAdvances == 0) { "rate window stayed closed before the foreign pool filled" }
                    rateAdvances++
                    nowMs += MeshPolicy.FOREIGN_RATE_WINDOW_MS + 1_000L
                }
                else -> error("foreign fill: $result")
            }
        }
        if (admitted > 0) nowMs += MeshPolicy.FOREIGN_RATE_WINDOW_MS + 1_000L
        assertEquals(MeshPolicy.FOREIGN_MAX, p.custodyRows().count { it.hopCount >= 1 })
        assertEquals(ownBefore, p.ownCustody().map { it.id }.toSet())
        assertTrue(p.custodyRows().filter { it.hopCount >= 1 }.all { it.emergency })
        val overflow = p.engine.ingest(bundle(foreignPayload(), createdAt, emergency = true), RadioPath.BLE)
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), overflow)
        assertEquals(ownBefore, p.ownCustody().map { it.id }.toSet())
    }

    /**
     * Tops the own pool up to [MeshPolicy.OWN_MAX] with hop-0 rows whose deadline is [createdAt] + 4h.
     * Room insert + arm() -> restore(), so the rows can be near expiry. holdSealed cannot backdate them.
     */
    private suspend fun fillOwn(p: Phone, createdAt: Long = nowMs - MeshPolicy.ABSOLUTE_TTL_MS + 20 * 60_000L) {
        val foreignBefore = p.custodyRows().filter { it.hopCount >= 1 }.map { it.id }.toSet()
        val missing = MeshPolicy.OWN_MAX - p.ownCustody().size
        check(missing >= 0)
        repeat(missing) { i ->
            val payload = "own-seed-${p.name}-$i-${foreignSeq++}".toByteArray()
            val id = MeshIds.hex(MeshIds.idFor(payload))
            p.dao.upsert(MeshCustodyEntity(id, createdAt, MeshPolicy.expireAt(createdAt), 0, false, payload, 0))
        }
        p.engine.arm()
        assertEquals(MeshPolicy.OWN_MAX, p.ownCustody().size)
        assertEquals(foreignBefore, p.custodyRows().filter { it.hopCount >= 1 }.map { it.id }.toSet())
        assertTrue(p.ownCustody().all { it.expireAtMs > nowMs })
    }

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
        assertEquals("$why: in-memory status", MessageStatus.QUEUED, alice.receipts().status(sent.id))
    }

    /** Alice sent one message, Bob delivered it and holds his receipt; Alice's foreign pool is then full. */
    private suspend fun ackScenario(): Triple<Phone, Phone, kotlin.Pair<Sent, ByteArray>> {
        val (alice, bob) = setup()
        val sent = alice.sendOffline("hello full custody")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(listOf(sent.id), bob.inboundIds())
        assertEquals(MeshReceiptAck.Sealed.HELD, bob.lastSeal)
        val receipt = bob.ownCustody().single().payload
        fill(alice)
        assertEquals("alice keeps her own row", 1, alice.ownCustody().size)
        assertEquals(MeshPolicy.FOREIGN_MAX, alice.custodyRows().count { it.hopCount >= 1 })
        return Triple(alice, bob, sent to receipt)
    }

    // 1 Full custody + valid locally addressed ACK --------------------------------------------------------
    @Test
    fun fullCustodySenderAppliesLocallyAddressedAckWithoutASlot() = runBlocking {
        val (alice, _, x) = ackScenario()
        val (sent, receipt) = x
        val foreignBefore = alice.custodyIds() - alice.ownCustody().map { it.id }.toSet()

        alice.directEnabled = false // control: pre-1K-R behaviour
        alice.ingestFrom(listOf(bundle(receipt)))
        assertPending(alice, sent, "without fix A the ACK is refused as full")
        assertTrue(alice.directCalls.isEmpty())

        alice.directEnabled = true
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(1, alice.directCalls.size)
        assertEquals(listOf(mapOf(MeshDirect.DELIVERED to 1)), alice.directReports)
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertEquals(MessageStatus.DELIVERED, alice.receipts().status(sent.id))
        assertTrue("own row released in Room", alice.ownCustody().isEmpty())
        assertTrue("own row released in memory", alice.engine.ownHeld().isEmpty())
        assertEquals("ACK not stored; foreign rows untouched", foreignBefore, alice.custodyIds())
        assertEquals(MeshPolicy.FOREIGN_MAX, alice.custodyRows().size)
        assertEquals("receipt marker recorded with fallback retention", true, alice.lastReceiptMarker)
        assertTrue(alice.advertises(receipt))
        assertTrue(armorLogs().any { it == "mesh_direct delivered=1" })
    }

    // 2 Full custody + valid locally addressed text; receipt hold rejected -> D1 retry; same-bytes retry ----
    @Test
    fun fullCustodyReceiverGetsTextAndRetriesItsReceiptWithIdenticalBytes() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val bobBefore = bob.custodyIds()
        val sent = alice.sendOffline("text to a full phone")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(listOf(mapOf(MeshDirect.DELIVERED to 1)), bob.directReports)
        assertEquals(listOf(sent.id), bob.inboundIds())
        assertEquals("no eviction, no slot used", bobBefore, bob.custodyIds())
        assertTrue("P2 marker for the direct-delivered message", bob.advertises(sent.payload))
        // D1: own pool full -> typed result, truthful log, queued for retry. Foreign rows cannot take that slot.
        assertEquals(MeshReceiptAck.Sealed.HOLD_QUEUED, bob.lastSeal)
        assertEquals("receipt=sealed count=1 held=0 queued=1 rejected=0 failed=0", bob.sealLogs.last())
        assertEquals(1, bob.engine.pendingHoldRetries())
        assertTrue(armorLogs().any { it == "mesh_hold_rejected reason=full retry=queued pending=1" })
        val intent = bob.receipts().store.receipts().single()
        val sealedBytes = intent.envelopeCanonical!!.toByteArray(Charsets.UTF_8)
        val mlsBefore = bob.mlsState()
        val encryptsBefore = bob.mls.encrypts

        // Still full: triggers make no attempt. The seeded own rows stay; the receipt is not among them.
        bob.engine.sweep()
        bob.engine.phaseFor(emptySet(), false)
        assertEquals(1, bob.engine.pendingHoldRetries())
        assertEquals(MeshPolicy.OWN_MAX, bob.ownCustody().size)
        assertTrue(bob.ownCustody().none { it.payload.contentEquals(sealedBytes) })

        // Near-expiry own rows reach their absolute deadline -> sweep re-holds the IDENTICAL receipt bytes.
        nowMs += 31 * 60_000L
        bob.engine.sweep()
        assertEquals(0, bob.engine.pendingHoldRetries())
        val held = bob.ownCustody().single()
        assertEquals("same sealed bytes", sealedBytes.toList(), held.payload.toList())
        assertEquals("same custody id", MeshIds.hex(MeshIds.idFor(sealedBytes)), held.id)
        assertFalse("receipt stays emergency=false", held.emergency)
        assertEquals("no MLS mutation on retry", mlsBefore, bob.mlsState())
        assertEquals("never re-encrypted", encryptsBefore, bob.mls.encrypts)
        assertTrue(armorLogs().any { it.startsWith("mesh_hold bytes=") && it.endsWith("retry=1") })

        // End to end: the retried receipt releases Alice's custody.
        alice.ingestFrom(listOf(BundleCodec.encode(bob.engine.ownHeld().single())))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.ownCustody().isEmpty())
    }

    // 3 Full + foreign transit, wrong outer mailbox, non-JSON -> never direct --------------------------------
    @Test
    fun foreignTransitAndWrongOuterMailboxNeverTakeTheDirectPath() = runBlocking {
        val (alice, bob, x) = ackScenario()
        val (sent, receipt) = x
        alice.deliver(foreignPayload()) // any prior delivery refreshes the mailbox cache under the lock
        alice.directCalls.clear()
        val before = alice.custodyIds()
        val decryptsBefore = alice.mls.totalDecrypts()
        val wrongOuter = JSONObject(String(receipt)).put("recipient_mailbox", bob.mailbox()).toString().toByteArray()
        alice.ingestFrom(listOf(bundle(foreignPayload()), bundle(wrongOuter), bundle("not json".toByteArray())))
        assertTrue("no direct attempt", alice.directCalls.isEmpty())
        assertTrue(alice.directReports.isEmpty())
        assertEquals("nothing stored or evicted", before, alice.custodyIds())
        assertEquals(decryptsBefore, alice.mls.totalDecrypts())
        assertPending(alice, sent, "foreign/wrong-mailbox bundles")
    }

    // 4 Cold start: unknown mailboxes -> in-lock gate declines before any decrypt -----------------------------
    @Test
    fun coldStartGateDeclinesForeignBeforeDecryptThenCacheFilters() = runBlocking {
        val (alice, _, x) = ackScenario()
        val (sent, receipt) = x
        alice.restart()
        assertNull(alice.knownMailboxes)
        val decryptsBefore = alice.mls.totalDecrypts()
        alice.ingestFrom(listOf(bundle(foreignPayload()), bundle(foreignPayload())))
        assertEquals("first foreign bundle reaches the gate; the second is filtered by the refreshed cache", 1, alice.directCalls.size)
        assertEquals(listOf(mapOf(MeshDirect.REJECTED to 1)), alice.directReports)
        assertEquals(MeshDelivery.Outcome.DECLINED, alice.outcomes.last())
        assertEquals("gate runs before any decrypt", decryptsBefore, alice.mls.totalDecrypts())
        assertTrue(alice.knownMailboxes!!.isNotEmpty())
        // After restart the genuine ACK still verifies against the durable row.
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.ownCustody().isEmpty())
    }

    // 5 Forged / wrong mailbox / wrong peer / wrong session / malformed ACKs via the direct path ---------------
    @Test
    fun forgedWrongMailboxPeerSessionAndMalformedAcksNeverRelease() = runBlocking {
        val (alice, bob) = setup()
        val sent = alice.sendOffline("forgery target")
        fill(alice)
        val before = alice.custodyIds()
        val ownId = alice.ownCustody().single().id
        val target = alice.receipts().outbound(sent.id)
        val b = bob.receipts()
        fun body(r: InnerRecord) = r.body as InnerBody.Receipt
        val cases = linkedMapOf<String, ByteArray>(
            "forged digest" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetCiphertextSha256 = Base64Url.encode(ByteArray(32) { 4 }))) }),
            "forged epoch" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetEpoch = 99L)) }),
            "wrong recipient pin" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(recipientIdentityKey = TinkSigner().pin) }),
            "wrong inner mailbox (outer is ours)" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(recipientMailbox = "mailbox-of-someone-else") }),
            "wrong recipient device" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(recipientDeviceId = "other-device") }),
            "wrong sender pin (peer)" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(senderIdentityKey = TinkSigner().pin) }),
            "wrong sender device (peer)" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(senderDeviceId = "other-sender-device") }),
            "wrong target session" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetSessionId = Base64Url.encode(ByteArray(16) { 8 }))) }),
            "orphan target" to bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target) { it.copy(body = body(it).copy(targetMessageId = Base64Url.encode(ByteArray(16) { 9 }))) }),
        )
        val genuine = bobSealed(bob, b.receiptTemplate(InnerKind.DELIVERY, target))
        cases["wrong MLS session ciphertext"] = JSONObject(String(genuine)).apply {
            put("encrypted_payload", Base64Url.encode("E|other-session|x".toByteArray()))
        }.toString().toByteArray()
        cases["malformed ciphertext"] = JSONObject(String(genuine)).apply { put("encrypted_payload", "AAAA") }.toString().toByteArray()
        cases["truncated envelope json"] = String(genuine).dropLast(1).toByteArray()
        for ((why, payload) in cases) {
            val mls = alice.link().peer.snapshot().toList()
            alice.directReports.clear()
            alice.ingestFrom(listOf(bundle(payload)))
            assertPending(alice, sent, why)
            assertEquals("$why: custody rows identical (no deletion, no storage)", before, alice.custodyIds())
            assertTrue(why, alice.lastApplied.isEmpty())
            assertEquals("$why: MLS state unchanged", mls, alice.link().peer.snapshot().toList())
            assertTrue("$why: never DELIVERED", alice.directReports.none { it.containsKey(MeshDirect.DELIVERED) })
        }
        // Control: the genuine receipt from the same session is applied through the same path.
        alice.ingestFrom(listOf(bundle(genuine)))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertEquals("only the own row left custody", setOf(ownId), before - alice.custodyIds())
        assertEquals(before - ownId, alice.custodyIds())
    }

    // 6 Replayed ACK + duplicate message via the direct path -----------------------------------------------
    @Test
    fun replayedAckAndDuplicateMessageAreIdempotentOnTheDirectPath() = runBlocking {
        val (alice, bob, x) = ackScenario()
        val (sent, receipt) = x
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        fill(alice) // full again
        val before = alice.custodyIds()
        alice.db.openHelper.writableDatabase.execSQL("UPDATE messages SET status = 'READ' WHERE id = ?", arrayOf(sent.id))
        val ct = JSONObject(String(receipt)).getString("encrypted_payload")
        val decrypts = alice.mls.decrypts[ct]
        repeat(3) { alice.ingestFrom(listOf(bundle(receipt))) }
        assertEquals(List(3) { mapOf(MeshDirect.DUPLICATE to 1) }, alice.directReports.drop(1))
        assertEquals("no second decrypt", decrypts, alice.mls.decrypts[ct])
        assertEquals("READ never regresses", MessageStatus.READ.name, alice.status(sent.id))
        assertEquals(before, alice.custodyIds())
        alice.restart()
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals("after restart: persisted marker -> DUPLICATE", mapOf(MeshDirect.DUPLICATE to 1), alice.directReports.last())
        assertEquals(MessageStatus.READ.name, alice.status(sent.id))

    }

    @Test
    fun duplicateMessageToAFullReceiverIsDuplicateWithNoSecondReceipt() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("dup to full")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(MeshReceiptAck.Sealed.HOLD_QUEUED, bob.lastSeal)
        bob.lastSeal = null
        val ct = JSONObject(String(sent.payload)).getString("encrypted_payload")
        repeat(2) { bob.ingestFrom(listOf(bundle(sent.payload, emergency = true))) }
        assertEquals(listOf(mapOf(MeshDirect.DELIVERED to 1)) + List(2) { mapOf(MeshDirect.DUPLICATE to 1) }, bob.directReports)
        assertEquals(listOf(sent.id), bob.inboundIds())
        assertEquals("P1: never decrypted again", 1, bob.mls.decrypts[ct])
        assertNull("duplicate never re-seals", bob.lastSeal)
        assertEquals("still exactly one queued receipt", 1, bob.engine.pendingHoldRetries())
        assertEquals(1, bob.receipts().store.receipts().size)
    }

    // 7 Budget + repeated malicious encounters --------------------------------------------------------------
    @Test
    fun directBudgetAndRepeatedMaliciousEncountersAreBoundedAndLeaveNoTrace() = runBlocking {
        val (alice, _, x) = ackScenario()
        val (sent, receipt) = x
        val before = alice.custodyIds()
        val mine = alice.mailbox()
        fun forged() = bundle(foreignPayload(mine)) // claims our mailbox, garbage ciphertext
        val mls = alice.mlsState()
        // Genuine ACK placed 9th: budget exhausted before it (documented DoS limit).
        alice.ingestFrom(List(8) { forged() } + bundle(receipt) + List(11) { forged() })
        assertEquals(8, alice.directCalls.size)
        assertEquals(mapOf(MeshDirect.REJECTED to 8, MeshDirect.BUDGET_EXHAUSTED to 12), alice.directReports.single())
        assertPending(alice, sent, "ACK beyond the budget is not attempted")
        // Repeated malicious encounters: each bounded, nothing persists.
        repeat(5) { alice.ingestFrom(List(20) { forged() }) }
        assertEquals(8 + 5 * 8, alice.directCalls.size)
        assertTrue(alice.directReports.drop(1).all { it == mapOf(MeshDirect.REJECTED to 8, MeshDirect.BUDGET_EXHAUSTED to 12) })
        assertEquals(before, alice.custodyIds())
        assertEquals(mls, alice.mlsState())
        assertPending(alice, sent, "after 5 malicious encounters")
        // No lasting lockout: next encounter with the genuine ACK first.
        alice.ingestFrom(listOf(bundle(receipt)) + List(3) { forged() })
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.ownCustody().isEmpty())
    }

    // 8 Lock contention and timeout ------------------------------------------------------------------------
    @Test
    fun lockTimeoutIsFailedAndChangesNothingThenRetrySucceeds() = runBlocking {
        val (alice, _, x) = ackScenario()
        val (sent, receipt) = x
        val before = alice.custodyIds()
        alice.lockWaitMs = 300
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            alice.lock.withLock {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(mapOf(MeshDirect.FAILED to 1), alice.directReports.single())
        assertEquals(MeshDelivery.Outcome.LOCK_TIMEOUT, alice.outcomes.last())
        assertPending(alice, sent, "lock timeout")
        assertEquals(before, alice.custodyIds())
        release.complete(Unit)
        holder.join()
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(mapOf(MeshDirect.DELIVERED to 1), alice.directReports.last())
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
    }

    // 9 Durable commit failure ----------------------------------------------------------------------------
    @Test
    fun durableCommitFailureOnTheDirectPathRollsBackEverything() = runBlocking {
        val (alice, _, x) = ackScenario()
        val (sent, receipt) = x
        val before = alice.custodyIds()
        val session = alice.db.contactSessionDao().find(alice.contactId)!!
        alice.failSessionUpsert = true // after status + custody delete ran inside the transaction
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(mapOf(MeshDirect.FAILED to 1), alice.directReports.last())
        assertEquals(MeshDelivery.Outcome.PERSIST_FAILED, alice.outcomes.last())
        assertPending(alice, sent, "commit failed")
        assertEquals(before, alice.custodyIds())
        assertEquals(session.updatedAtEpochMs, alice.db.contactSessionDao().find(alice.contactId)!!.updatedAtEpochMs)
        assertNull("no marker for a failed commit", alice.lastReceiptMarker)
        alice.failSessionUpsert = false
        alice.failRelease = true
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(mapOf(MeshDirect.FAILED to 1), alice.directReports.last())
        assertPending(alice, sent, "release failed")
        alice.failRelease = false
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertTrue(alice.ownCustody().isEmpty())
    }

    // 10 Receipt hold accepted (not full) -> truthful held=1 -------------------------------------------------
    @Test
    fun receiptHoldAcceptedIsLoggedHeldOnlyAfterDurableAdmission() = runBlocking {
        val (alice, bob) = setup()
        val sent = alice.sendOffline("normal path")
        assertTrue("normal send hold is Held", sent.hold is MeshHoldResult.Held)
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertTrue("not full: no direct path", bob.directCalls.isEmpty())
        assertEquals(MeshReceiptAck.Sealed.HELD, bob.lastSeal)
        assertEquals("receipt=sealed count=1 held=1 queued=0 rejected=0 failed=0", bob.sealLogs.last())
        assertEquals(1, bob.ownCustody().size)
        assertEquals(0, bob.engine.pendingHoldRetries())
        val again = bob.engine.holdSealed(bob.ownCustody().single().payload, emergency = false, retryIfFull = true)
        assertTrue(again is MeshHoldResult.Duplicate)
    }

    // 11 Receipt hold rejected + failure kinds ------------------------------------------------------------
    @Test
    fun receiptHoldRejectedAndFailedAreReportedTruthfully() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        val reserved = bob.engine.holdSealed(foreignPayload(), emergency = true)
        assertTrue("foreign-full phone still admits an own hold: $reserved", reserved is MeshHoldResult.Held)
        assertEquals(0, bob.engine.pendingHoldRetries())
        assertEquals(1, bob.ownCustody().size)
        assertEquals(MeshPolicy.FOREIGN_MAX, bob.custodyRows().count { it.hopCount >= 1 })
        fillOwn(bob)
        val own = bob.engine.holdSealed(foreignPayload(), emergency = true)
        assertEquals(MeshHoldResult.Rejected("full", retryQueued = false), own)
        assertEquals(0, bob.engine.pendingHoldRetries())
        assertEquals(MeshPolicy.OWN_MAX, bob.ownCustody().size)
        assertTrue(armorLogs().contains("mesh_hold_rejected reason=full"))
        // Typed results never contain or log content.
        val sent = alice.sendOffline("secret words here")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(MeshReceiptAck.Sealed.HOLD_QUEUED, bob.lastSeal)
        val logs = armorLogs()
        assertTrue(logs.none { it.contains("secret words") || it.contains(sent.id) || it.contains(bob.mailbox()) || it.contains(alice.mailbox()) })
        // A Room write failure during hold is HOLD_FAILED (thrown, not counted as held) - unchanged semantics.
        val (alice2, bob2) = setup("alice2", "bob2")
        val s3 = alice2.sendOffline("hold write fails")
        val bobRows = bob2.custodyRows().size
        // failure only hits the receipt hold (the inbox commit does not touch mesh_custody)
        bob2.dao.failUpsert = true
        assertTrue("inbox delivery itself succeeds", bob2.deliver(s3.payload))
        bob2.dao.failUpsert = false
        assertEquals(MeshReceiptAck.Sealed.HOLD_FAILED, bob2.lastSeal)
        assertEquals("receipt=sealed count=1 held=0 queued=0 rejected=0 failed=1", bob2.sealLogs.last())
        assertEquals(bobRows, bob2.custodyRows().size)
    }

    // 12 Retry capacity bound -------------------------------------------------------------------------------
    @Test
    fun retryCapacityIsBoundedAndOnlyReceiptsAreQueued() = runBlocking {
        val (_, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val longExp = nowMs / 1000 + OutboxPolicy.TTL_SECONDS
        val results = (1..20).map {
            bob.engine.holdSealed(foreignPayload(expiresAtSeconds = longExp), emergency = false, retryIfFull = true)
        }
        assertEquals(16, results.count { it == MeshHoldResult.Rejected("full", retryQueued = true) })
        assertEquals(4, results.count { it == MeshHoldResult.Rejected("full", retryQueued = false) })
        assertEquals(16, bob.engine.pendingHoldRetries())
        assertTrue(armorLogs().contains("mesh_hold_rejected reason=full retry=dropped pending=16"))
        assertEquals(MeshPolicy.FOREIGN_MAX, bob.custodyRows().count { it.hopCount >= 1 })
        assertEquals(MeshPolicy.OWN_MAX, bob.ownCustody().size)
        assertEquals("both pools full, nothing evicted", MeshPolicy.MAX_BUNDLES, bob.custodyRows().size)
        // Each trigger tries each entry at most once; when capacity frees all 16 are re-held in one pass.
        nowMs += 31 * 60_000L
        bob.engine.sweep()
        assertEquals(0, bob.engine.pendingHoldRetries())
        assertEquals(16, bob.ownCustody().size)
    }

    // 13 Retry exhaustion (attempts and deadline) + clock rollback -------------------------------------------
    @Test
    fun retryExhaustsAfterBoundedAttemptsOrDeadline() = runBlocking {
        val (_, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val p1 = foreignPayload(expiresAtSeconds = nowMs / 1000 + OutboxPolicy.TTL_SECONDS)
        val queued = bob.engine.holdSealed(p1, emergency = false, retryIfFull = true)
        assertEquals(MeshHoldResult.Rejected("full", retryQueued = true), queued)
        assertEquals(1, bob.engine.pendingHoldRetries())
        nowMs += 31 * 60_000L
        bob.dao.failUpsert = true // capacity is free but every durable write fails
        repeat(7) {
            bob.engine.sweep()
            assertEquals("attempt ${it + 1} failed, kept", 1, bob.engine.pendingHoldRetries())
        }
        bob.engine.sweep()
        assertEquals("dropped after 8 failed attempts", 0, bob.engine.pendingHoldRetries())
        bob.dao.failUpsert = false
        assertTrue("never claimed held", bob.ownCustody().isEmpty())
        assertTrue(armorLogs().any { it.startsWith("mesh_hold_retry") && it.contains("exhausted=1") })

        // Deadline: queued while full, capacity never frees before 4 h -> pruned, never held.
        val (_, carol) = setup("a2", "carol")
        val carolCreated = nowMs
        fill(carol, createdAt = carolCreated)
        fillOwn(carol, createdAt = carolCreated)
        val carolQueued = carol.engine.holdSealed(foreignPayload(), emergency = false, retryIfFull = true)
        assertEquals(MeshHoldResult.Rejected("full", retryQueued = true), carolQueued)
        assertEquals(1, carol.engine.pendingHoldRetries())
        nowMs += MeshPolicy.ABSOLUTE_TTL_MS
        carol.engine.sweep()
        assertEquals(0, carol.engine.pendingHoldRetries())
        assertTrue(carol.ownCustody().isEmpty())

        // Clock rollback beyond the skew drops the entry (fail safe).
        val (_, dave) = setup("a3", "dave")
        val daveCreated = nowMs
        fill(dave, createdAt = daveCreated)
        fillOwn(dave, createdAt = daveCreated)
        val daveQueued = dave.engine.holdSealed(foreignPayload(), emergency = false, retryIfFull = true)
        assertEquals(MeshHoldResult.Rejected("full", retryQueued = true), daveQueued)
        assertEquals(1, dave.engine.pendingHoldRetries())
        nowMs -= MeshPolicy.CLOCK_SKEW_MS + 60_000L
        dave.engine.phaseFor(emptySet(), false)
        assertEquals(0, dave.engine.pendingHoldRetries())
    }

    // 14 Crash-durable retry: restart keeps the sealed receipt; DELIVERED only after a real ACK. ---------
    @Test
    fun restartKeepsDurableReceiptAndSenderStaysQueuedUntilAuthenticatedDelivery() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("durable retry")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(MeshReceiptAck.Sealed.HOLD_QUEUED, bob.lastSeal)
        val encryptsAfterSeal = bob.mls.encrypts
        assertEquals(1, bob.engine.pendingHoldRetries())
        val queued = bob.engine.holdRetry.pending().single()
        val sealedBytes = queued.payload.copyOf()
        val authMs = JSONObject(String(sealedBytes, Charsets.UTF_8)).getLong("expires_at") * 1000L
        assertTrue(queued.deadlineMs <= queued.queuedAtMs + MeshPolicy.ABSOLUTE_TTL_MS)
        assertTrue(queued.deadlineMs <= authMs)
        assertEquals(0, queued.attempts)
        assertFalse(queued.emergency)
        bob.restart()
        assertEquals("sealed receipt reloaded", 1, bob.engine.pendingHoldRetries())
        val reloaded = bob.engine.holdRetry.pending().single()
        assertTrue(reloaded.payload.contentEquals(sealedBytes))
        assertEquals(queued.queuedAtMs, reloaded.queuedAtMs)
        assertEquals(queued.deadlineMs, reloaded.deadlineMs)
        assertEquals(0, reloaded.attempts)
        assertEquals(queued.idHex, reloaded.idHex)
        assertEquals("still full after restart", MeshPolicy.OWN_MAX, bob.ownCustody().size)
        assertTrue(bob.ownCustody().none { it.payload.contentEquals(sealedBytes) })
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
        assertEquals("restart did not re-seal", encryptsAfterSeal, bob.mls.encrypts)
        bob.engine.sweep()
        assertEquals("full pool consumes no attempt", 1, bob.engine.pendingHoldRetries())
        assertEquals(0, bob.engine.holdRetry.pending().single().attempts)
        val victim = bob.ownCustody().first { !it.payload.contentEquals(sealedBytes) }
        bob.engine.dropDelivered(victim.id)
        assertEquals("freed own slot admits the same bytes", 0, bob.engine.pendingHoldRetries())
        val held = bob.ownCustody().single { it.payload.contentEquals(sealedBytes) }
        assertEquals(MeshIds.hex(MeshIds.idFor(sealedBytes)), held.id)
        assertTrue("custody TTL does not pass the authenticated expiry", held.expireAtMs <= authMs)
        assertEquals("admitted retry is not delivery", MessageStatus.QUEUED.name, alice.status(sent.id))
        assertEquals("admit does not re-seal", encryptsAfterSeal, bob.mls.encrypts)
        val encoded = BundleCodec.encode(bob.engine.ownHeld().single { it.payload.contentEquals(sealedBytes) })
        alice.ingestFrom(listOf(encoded))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        val deliveredAt = alice.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs
        assertTrue(deliveredAt != null && deliveredAt > 0L)
        alice.ingestFrom(listOf(encoded))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertEquals(deliveredAt, alice.db.messageDao().findById(sent.id)!!.deliveredAtEpochMs)
        assertTrue(alice.ownCustody().isEmpty())
    }

    // 15 No unauthorized custody deletion across the whole direct path -------------------------------------
    @Test
    fun directPathNeverDeletesOrEvictsCustodyExceptTheValidatedOwnRelease() = runBlocking {
        val (alice, bob, x) = ackScenario()
        val (sent, receipt) = x
        val foreign = alice.custodyIds() - alice.ownCustody().map { it.id }.toSet()
        val mine = alice.mailbox()
        // Foreign pool is full and the own pool still has room: the reply receipt is reserved, not queued.
        val text = bob.sendOffline("reply while alice is full")
        alice.ingestFrom(listOf(bundle(text.payload, emergency = true), bundle(foreignPayload(mine)), bundle(foreignPayload())))
        assertEquals(listOf(text.id), alice.inboundIds())
        assertEquals(MeshReceiptAck.Sealed.HELD, alice.lastSeal)
        assertEquals(0, alice.engine.pendingHoldRetries())
        val ownAfterText = alice.ownCustody()
        assertEquals("original own row plus the reserved receipt", 2, ownAfterText.size)
        assertEquals(foreign + ownAfterText.map { it.id }, alice.custodyIds())
        alice.ingestFrom(listOf(bundle(receipt)))
        assertEquals(MessageStatus.DELIVERED.name, alice.status(sent.id))
        assertEquals(0, alice.engine.pendingHoldRetries())
        val ownNow = alice.ownCustody()
        assertEquals("ACK released only the validated own row; the receipt stays", 1, ownNow.size)
        assertFalse(ownNow.single().emergency)
        assertEquals("every foreign row survived", foreign + ownNow.map { it.id }, alice.custodyIds())
    }

    // AW: attempts, deadline, and clock rollback stay durable across a process-equivalent restart. --------
    @Test
    fun retryAttemptsDeadlineAndClockRollbackSurviveRestart() = runBlocking {
        val (_, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val longLived = foreignPayload(expiresAtSeconds = nowMs / 1000 + OutboxPolicy.TTL_SECONDS)
        val queued = bob.engine.holdSealed(longLived, emergency = false, retryIfFull = true)
        assertEquals(MeshHoldResult.Rejected("full", retryQueued = true), queued)
        val victim = bob.ownCustody().first().id
        bob.dao.failUpsert = true
        bob.engine.dropDelivered(victim)
        assertEquals(1, bob.engine.pendingHoldRetries())
        assertEquals(1, bob.engine.holdRetry.pending().single().attempts)
        // The freed slot would be admitted by restore(). Fill it again so restart only reloads.
        bob.dao.failUpsert = false
        val pad = "own-pad-${bob.name}".toByteArray()
        val padId = MeshIds.hex(MeshIds.idFor(pad))
        bob.dao.upsert(MeshCustodyEntity(padId, nowMs, MeshPolicy.expireAt(nowMs), 0, false, pad, 0))
        bob.restart()
        assertEquals(1, bob.engine.pendingHoldRetries())
        assertEquals("attempt count reloaded", 1, bob.engine.holdRetry.pending().single().attempts)
        assertTrue(bob.engine.holdRetry.pending().single().payload.contentEquals(longLived))
        assertEquals(MeshPolicy.OWN_MAX, bob.ownCustody().size)
        bob.dao.failUpsert = true
        bob.engine.dropDelivered(padId)
        assertEquals(1, bob.engine.pendingHoldRetries())
        repeat(5) {
            bob.engine.sweep()
            assertEquals("attempt ${it + 3} kept", 1, bob.engine.pendingHoldRetries())
        }
        bob.engine.sweep()
        assertEquals("eighth failure drops the durable entry", 0, bob.engine.pendingHoldRetries())
        bob.dao.failUpsert = false
        bob.engine.sweep()
        assertTrue(bob.ownCustody().none { it.payload.contentEquals(longLived) })

        val (_, carol) = setup("a2", "carol")
        val carolCreated = nowMs
        fill(carol, createdAt = carolCreated)
        fillOwn(carol, createdAt = carolCreated)
        val carolPayload = foreignPayload()
        assertEquals(
            MeshHoldResult.Rejected("full", retryQueued = true),
            carol.engine.holdSealed(carolPayload, emergency = false, retryIfFull = true),
        )
        carol.restart()
        assertEquals(1, carol.engine.pendingHoldRetries())
        nowMs += MeshPolicy.ABSOLUTE_TTL_MS
        carol.engine.sweep()
        assertEquals(0, carol.engine.pendingHoldRetries())
        assertTrue(carol.ownCustody().none { it.payload.contentEquals(carolPayload) })

        val (_, dave) = setup("a3", "dave")
        val daveCreated = nowMs
        fill(dave, createdAt = daveCreated)
        fillOwn(dave, createdAt = daveCreated)
        assertEquals(
            MeshHoldResult.Rejected("full", retryQueued = true),
            dave.engine.holdSealed(foreignPayload(), emergency = false, retryIfFull = true),
        )
        dave.restart()
        assertEquals(1, dave.engine.pendingHoldRetries())
        nowMs -= MeshPolicy.CLOCK_SKEW_MS + 60_000L
        dave.engine.phaseFor(emptySet(), false)
        assertEquals(0, dave.engine.pendingHoldRetries())
    }

    @Test
    fun disarmRearmAndRestartPreserveThePendingReceipt() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("still queued while mesh is off")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(1, bob.engine.pendingHoldRetries())
        val sealedBytes = bob.engine.holdRetry.pending().single().payload.copyOf()
        bob.engine.disarm()
        bob.engine.arm()
        assertEquals(1, bob.engine.pendingHoldRetries())
        assertTrue(bob.engine.holdRetry.pending().single().payload.contentEquals(sealedBytes))
        assertEquals(MeshPolicy.OWN_MAX, bob.ownCustody().size)
        bob.restart()
        assertEquals(1, bob.engine.pendingHoldRetries())
        assertTrue(bob.engine.holdRetry.pending().single().payload.contentEquals(sealedBytes))
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
        assertTrue(bob.ownCustody().none { it.payload.contentEquals(sealedBytes) })
    }

    @Test
    fun historicalForeignOverQuotaDoesNotBlockDurableOwnRetry() = runBlocking {
        val (alice, bob) = setup()
        val seeded = mutableListOf<String>()
        repeat(40) {
            val payload = foreignPayload(expiresAtSeconds = nowMs / 1000 + OutboxPolicy.TTL_SECONDS)
            val id = MeshIds.hex(MeshIds.idFor(payload))
            bob.dao.upsert(MeshCustodyEntity(id, nowMs, MeshPolicy.expireAt(nowMs), 1, false, payload, 0))
            seeded += id
        }
        bob.engine.arm()
        fillOwn(bob)
        assertEquals(40, bob.custodyRows().count { it.hopCount >= 1 })
        val sent = alice.sendOffline("foreign history stays")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(MeshReceiptAck.Sealed.HOLD_QUEUED, bob.lastSeal)
        assertEquals(seeded.toSet(), bob.custodyRows().filter { it.hopCount >= 1 }.map { it.id }.toSet())
        bob.restart()
        assertEquals(1, bob.engine.pendingHoldRetries())
        assertEquals(40, bob.custodyRows().count { it.hopCount >= 1 })
        val sealedBytes = bob.engine.holdRetry.pending().single().payload.copyOf()
        val victim = bob.ownCustody().first().id
        bob.engine.dropDelivered(victim)
        assertEquals(0, bob.engine.pendingHoldRetries())
        assertTrue(bob.ownCustody().any { it.payload.contentEquals(sealedBytes) })
        assertEquals(seeded.toSet(), bob.custodyRows().filter { it.hopCount >= 1 }.map { it.id }.toSet())
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
    }

    @Test
    fun retryNeverExtendsPastAuthenticatedReceiptExpiry() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("expiry bound")
        // Authenticated life is 4h+10min. After the seeded own rows expire, a fresh 4h custody
        // row would outlive that expiry. The retry must not admit those bytes.
        val shortExp = nowMs / 1000 + MeshPolicy.ABSOLUTE_TTL_MS / 1000 + 10 * 60
        val payload = foreignPayload(expiresAtSeconds = shortExp)
        val authMs = shortExp * 1000L
        assertEquals(
            MeshHoldResult.Rejected("full", retryQueued = true),
            bob.engine.holdSealed(payload, emergency = false, retryIfFull = true),
        )
        assertTrue(bob.engine.holdRetry.pending().single().deadlineMs <= authMs)
        nowMs += 21 * 60_000L
        bob.engine.sweep()
        assertTrue(bob.custodyRows().none { it.payload.contentEquals(payload) })
        assertEquals(0, bob.engine.pendingHoldRetries())
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))

        val (alice2, bob2) = setup("a4", "bob4")
        fill(bob2)
        fillOwn(bob2)
        val sent2 = alice2.sendOffline("seven day receipt")
        bob2.ingestFrom(listOf(bundle(sent2.payload, emergency = true)))
        assertEquals(MeshReceiptAck.Sealed.HOLD_QUEUED, bob2.lastSeal)
        val real = bob2.engine.holdRetry.pending().single().payload.copyOf()
        val realAuth = JSONObject(String(real, Charsets.UTF_8)).getLong("expires_at") * 1000L
        bob2.engine.dropDelivered(bob2.ownCustody().first().id)
        val held = bob2.ownCustody().single { it.payload.contentEquals(real) }
        assertTrue(held.expireAtMs <= realAuth)
        assertEquals(MessageStatus.QUEUED.name, alice2.status(sent2.id))
    }

    @Test
    fun duplicateEnqueueKeepsTheOriginalDeadline() = runBlocking {
        val (_, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val payload = foreignPayload(expiresAtSeconds = nowMs / 1000 + OutboxPolicy.TTL_SECONDS)
        assertEquals(
            MeshHoldResult.Rejected("full", retryQueued = true),
            bob.engine.holdSealed(payload, emergency = false, retryIfFull = true),
        )
        val first = bob.engine.holdRetry.pending().single()
        nowMs += 5_000L
        assertEquals(
            MeshHoldResult.Rejected("full", retryQueued = true),
            bob.engine.holdSealed(payload.copyOf(), emergency = true, retryIfFull = true),
        )
        val second = bob.engine.holdRetry.pending().single()
        assertEquals(first.queuedAtMs, second.queuedAtMs)
        assertEquals(first.deadlineMs, second.deadlineMs)
        assertEquals(0, second.attempts)
        assertFalse(second.emergency)
        assertTrue(second.payload.contentEquals(payload))
    }

    @Test
    fun failedAtomicWriteDoesNotReportTheReceiptQueued() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("write failed")
        bob.holdIo.failWrite = true
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(MeshReceiptAck.Sealed.HOLD_REJECTED, bob.lastSeal)
        assertEquals(0, bob.engine.pendingHoldRetries())
        assertFalse(bob.retryFile().exists())
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
        assertEquals(MeshPolicy.OWN_MAX, bob.ownCustody().size)
        bob.holdIo.failWrite = false
        bob.holdIo.interruptWrite = true
        val payload = foreignPayload(expiresAtSeconds = nowMs / 1000 + OutboxPolicy.TTL_SECONDS)
        assertEquals(
            MeshHoldResult.Rejected("full", retryQueued = false),
            bob.engine.holdSealed(payload, emergency = false, retryIfFull = true),
        )
        assertEquals(0, bob.engine.pendingHoldRetries())
        assertFalse(bob.retryFile().exists())
        assertTrue(File(bob.retryFile().path + MeshHoldRetryStore.TMP_SUFFIX).exists())
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
        bob.holdIo.interruptWrite = false
    }

    @Test
    fun crashAfterDiskWriteBeforeMemoryUpdateReloadsOnRestart() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("crash after write")
        bob.engine.crashAfterRetryWrite = true
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        val encryptsAfter = bob.mls.encrypts
        assertEquals(MeshReceiptAck.Sealed.HOLD_FAILED, bob.lastSeal)
        assertEquals(0, bob.engine.pendingHoldRetries())
        val peeked = MeshHoldRetryStore(bob.retryFile()).load()
        assertEquals(1, peeked.size)
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
        bob.restart()
        assertEquals(1, bob.engine.pendingHoldRetries())
        val reloaded = bob.engine.holdRetry.pending().single()
        assertTrue(reloaded.payload.contentEquals(peeked.single().payload))
        assertEquals(peeked.single().queuedAtMs, reloaded.queuedAtMs)
        assertEquals(peeked.single().deadlineMs, reloaded.deadlineMs)
        assertEquals(0, reloaded.attempts)
        assertEquals(MeshPolicy.OWN_MAX, bob.ownCustody().size)
        assertTrue(bob.ownCustody().none { it.payload.contentEquals(reloaded.payload) })
        assertEquals("restart did not re-seal", encryptsAfter, bob.mls.encrypts)
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
    }

    @Test
    fun crashAfterRoomAdmitBeforeRetryFileRemovalClearsWhenPayloadMatches() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("room before file")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(MeshReceiptAck.Sealed.HOLD_QUEUED, bob.lastSeal)
        val sealed = bob.engine.holdRetry.pending().single().payload.copyOf()
        bob.holdIo.failWrite = true
        val victim = bob.ownCustody().first { !it.payload.contentEquals(sealed) }.id
        bob.engine.dropDelivered(victim)
        assertEquals(1, bob.engine.pendingHoldRetries())
        assertEquals(0, bob.engine.holdRetry.pending().single().attempts)
        assertTrue(bob.engine.holdRetry.pending().single().payload.contentEquals(sealed))
        assertEquals(1, MeshHoldRetryStore(bob.retryFile()).load().size)
        assertTrue(bob.ownCustody().any { it.payload.contentEquals(sealed) })
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
        bob.holdIo.failWrite = false
        bob.restart()
        assertEquals(0, bob.engine.pendingHoldRetries())
        assertEquals(1, bob.ownCustody().count { it.payload.contentEquals(sealed) })
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
    }

    @Test
    fun corruptOrMissingRetryFileFailsClosedAndKeepsTheSenderQueued() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("corrupt file")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        assertEquals(1, bob.engine.pendingHoldRetries())
        val sealed = bob.engine.holdRetry.pending().single().payload.copyOf()
        val damaged = bob.retryFile().readBytes()
        damaged[damaged.size - 1] = (damaged[damaged.size - 1].toInt() xor 0xff).toByte()
        bob.retryFile().writeBytes(damaged)
        bob.restart()
        assertEquals(0, bob.engine.pendingHoldRetries())
        val quarantine = File(bob.retryFile().path + MeshHoldRetryStore.CORRUPT_SUFFIX + "1")
        assertTrue(quarantine.exists())
        assertTrue(quarantine.readBytes().contentEquals(damaged))
        assertFalse(bob.retryFile().exists())
        assertTrue(bob.ownCustody().none { it.payload.contentEquals(sealed) })
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))

        val (alice2, bob2) = setup("a5", "bob5")
        fill(bob2)
        fillOwn(bob2)
        val sent2 = alice2.sendOffline("missing file")
        bob2.ingestFrom(listOf(bundle(sent2.payload, emergency = true)))
        assertEquals(1, bob2.engine.pendingHoldRetries())
        val sealed2 = bob2.engine.holdRetry.pending().single().payload.copyOf()
        assertTrue(bob2.retryFile().delete())
        bob2.restart()
        assertEquals(0, bob2.engine.pendingHoldRetries())
        assertFalse(bob2.retryFile().exists())
        assertTrue(bob2.ownCustody().none { it.payload.contentEquals(sealed2) })
        assertEquals(MessageStatus.QUEUED.name, alice2.status(sent2.id))
    }

    @Test
    fun custodyIdentityMismatchDoesNotDropTheRetryOrTheBadRow() = runBlocking {
        val (alice, bob) = setup()
        fill(bob)
        fillOwn(bob)
        val sent = alice.sendOffline("identity mismatch")
        bob.ingestFrom(listOf(bundle(sent.payload, emergency = true)))
        val entry = bob.engine.holdRetry.pending().single()
        val bad = "not-the-sealed-receipt".toByteArray()
        bob.dao.upsert(MeshCustodyEntity(entry.idHex, nowMs, MeshPolicy.expireAt(nowMs), 0, false, bad, 0))
        bob.engine.arm()
        assertEquals(1, bob.engine.pendingHoldRetries())
        val kept = bob.engine.holdRetry.pending().single()
        assertEquals(1, kept.attempts)
        assertTrue(kept.payload.contentEquals(entry.payload))
        val planted = bob.custodyRows().single { it.id == entry.idHex }
        assertTrue(planted.payload.contentEquals(bad))
        assertEquals(MessageStatus.QUEUED.name, alice.status(sent.id))
    }
}
