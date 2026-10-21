package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.mesh.BundleCodec
import com.codeyantra.armorchat.core.mesh.CustodyBundle
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshAdmission
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshCustodyRecovery
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.MeshIds
import com.codeyantra.armorchat.core.mesh.MeshPolicy
import com.codeyantra.armorchat.core.mesh.RadioPath
import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.core.network.ServicePolicyTrust
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.transport.DealEnvelope
import com.codeyantra.armorchat.deal.transport.DealEnvelopes
import com.codeyantra.armorchat.deal.transport.DealWireEvent
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-1H-2: bounded custody recovery end to end.
 *
 * REAL: Room (in-memory ArmorChatDatabase: mesh_custody, messages, conversations, contact_sessions
 * in one database, real SQLite transactions), EmergencyMeshEngine (ingest/restore/
 * recoveryCandidates/dropDelivered/offers), MeshCourier.ingest, MeshCustodyRecovery,
 * SessionOrchestrator.offerMesh/persist/restore, ReceiptMessagingPeer, MeshDelivery.deliverDurable,
 * MeshInboxCommit, MeshDelivery.alreadyStored.
 * SIMULATED: ChatMessagingService is not constructible on the JVM; [wiring] mirrors deliverMesh.
 * Keystore encryption = identity. MLS is the JVM fake engine + rx counter: NOT proof of native
 * mls-rs behaviour (in particular the fake engine decrypts a replayed ciphertext again; native
 * rejection of consumed generations is a hypothesis). "Restart" = fresh orchestrator, engine
 * (restore()) and recovery worker over the same open Room database: NOT proof of process death /
 * fsync behaviour. The BLE encounter is the recovery gate only; no radio is involved.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshCustodyRecoveryIntegrationTest {

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

    /** Fake engine + per-ciphertext decrypt counter + "rx" counter carried in the MLS state. */
    private class RxMls {
        val decrypts = mutableMapOf<String, Int>()
        fun transact(state: ByteArray, request: String): ByteArray {
            val out = FakeMls.transact(state, request)
            val req = CanonicalJson.parseObject(request)
            if (req["op"] != "Decrypt") return out
            val ct = req["ciphertext"] as String
            decrypts[ct] = (decrypts[ct] ?: 0) + 1
            val staged = CanonicalJson.parseObject(String(out, Charsets.UTF_8))
            val st = CanonicalJson.parseObject(
                String(Base64Url.decode(staged["state"] as String), Charsets.UTF_8),
            ).toMutableMap()
            st["rx"] = ((st["rx"] as? Long) ?: 0L) + 1L
            return CanonicalJson.of(
                mapOf("output" to staged["output"], "state" to Base64Url.encode(CanonicalJson.of(st).toByteArray())),
            ).toByteArray()
        }
    }

    private var nowMs = 1_800_000_000_000L
    private val origin = "https://relay-mesh-1h2.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-1h2", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val bobMls = RxMls()
    private val lock = Mutex()
    private val db: ArmorChatDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArmorChatDatabase::class.java).build()
    private val scopes = mutableListOf<CoroutineScope>()

    private fun orch(mls: (ByteArray, String) -> ByteArray) = SessionOrchestrator(
        registry = registry,
        provider = provider,
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = MessagingLinkFactory { signer, relay, clock, engine ->
            val peer = TwoPhonePeer(signer, relay, clock, mls, engine)
            LiveLink(peer, ReceiptMessagingPeer(peer, clock, jitter = { 0 }))
        },
        nowSeconds = { nowMs / 1000 },
    )

    private fun rxOf(snap: ByteArray?): Long =
        snap?.let { (CanonicalJson.parseObject(String(it, Charsets.UTF_8))["rx"] as? Long) ?: 0L } ?: -1L

    private val contactId = "contact-alice"
    private val conversationId = "conversation-alice"

    private class Hooks {
        var lockWaitMs = MeshDelivery.MESH_LOCK_WAIT_MS
        var failSessionUpsertOnce = false
        var stageGate: suspend () -> Unit = {}
    }

    /** Bob's phone: messaging (orchestrator), custody engine and recovery worker; Alice sends. */
    private inner class Phone(
        val contactId: String = this@MeshCustodyRecoveryIntegrationTest.contactId,
        val conversationId: String = this@MeshCustodyRecoveryIntegrationTest.conversationId,
        private val mls: RxMls = bobMls,
    ) {
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceOrch = orch(FakeMls::transact)
        var bobOrch = orch(mls::transact)
        var engine = newEngine()
        val hooks = Hooks()
        val notifications = mutableListOf<String>()
        val outcomes = mutableListOf<MeshDelivery.Outcome>()
        var deliverCalls = 0
        lateinit var recovery: MeshCustodyRecovery

        init {
            runBlocking {
                aliceOrch.provision(alice, bob.pin)
                bobOrch.provision(bob, alice.pin)
                aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
                confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
                db.conversationDao().insert(ConversationEntity(conversationId, contactId, "Alice", nowMs))
                db.contactSessionDao().upsert(sessionRow(bobOrch.persist(alice.pin)!!))
            }
            recovery = newRecovery()
        }

        private val savedMailbox = bobOrch.link(alice.pin)?.peer?.mailbox

        private fun newEngine() =
            EmergencyMeshEngine(RuntimeEnvironment.getApplication(), db.meshCustodyDao(), AppClock { nowMs })

        /** EmergencyMeshService wiring of MeshCustodyRecovery (see Mesh-1H-2 in the service). */
        fun newRecovery(): MeshCustodyRecovery {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
            return MeshCustodyRecovery(
                scope = scope,
                candidates = { engine.recoveryCandidates() },
                alreadyStored = { payload -> MeshDelivery.alreadyStored(payload) { db.messageDao().findById(it) } },
                deliver = { payload -> deliver(payload) },
                dropDelivered = { idHex -> engine.dropDelivered(idHex) },
            )
        }

        fun sessionRow(saved: PersistedLink): ContactSessionEntity = ContactSessionEntity(
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

        private fun rowToLink(row: ContactSessionEntity) = PersistedLink(
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
        )

        suspend fun load() {
            if (bobOrch.link(alice.pin) != null) return
            val row = db.contactSessionDao().find(contactId) ?: return
            bobOrch.restore(bob, rowToLink(row), bob.pin)
        }

        /** Simulated service/process recreation over the same Room database. */
        fun restart() {
            bobOrch = orch(mls::transact)
            engine = newEngine()
            val before = runBlocking { engine.restores.first() }
            runBlocking { engine.arm() } // production restore()
            assertTrue("restore() must signal recovery", runBlocking { engine.restores.first() } > before)
            recovery = newRecovery()
        }

        fun rx(): Long = rxOf(bobOrch.link(alice.pin)?.peer?.snapshot())
        suspend fun durableRx(): Long = rxOf(db.contactSessionDao().find(contactId)?.mlsSnapshot)
        suspend fun rows(): List<String> =
            db.messageDao().observeAllMessages().first().filter { !it.isOutgoing }.map { it.id }
        suspend fun custodyIds(): Set<String> = db.meshCustodyDao().all().map { it.id }.toSet()

        fun seal(text: String): Triple<String, ByteArray, String> = runBlocking {
            val r = aliceOrch.sendText(bob.pin, text)
            val canonical = r.envelopeCanonical!!
            val ct = org.json.JSONObject(canonical).getString("encrypted_payload")
            Triple(r.messageId, canonical.toByteArray(Charsets.UTF_8), ct)
        }

        /** Mirrors ChatMessagingService.deliverMesh (Mesh-1H-1, unchanged in 1H-2). */
        suspend fun deliver(payload: ByteArray): Boolean {
            deliverCalls++
            val staged = mutableListOf<MeshInboxCommit.Staged>()
            var committed: List<MeshInboxCommit.Staged> = emptyList()
            val ok = MeshDelivery.deliverDurable(
                lock = lock,
                payload = payload,
                load = { load() },
                checkpoint = { staged.clear(); bobOrch.meshCheckpoint() },
                rollback = { staged.clear(); bobOrch.restoreMeshCheckpoint(it) },
                offer = { raw, cb -> bobOrch.offerMesh(raw, cb) },
                persistInbound = { pin, r ->
                    hooks.stageGate()
                    MeshInboxCommit.inboundRow(conversationId, r, nowMs)?.let { e ->
                        staged += MeshInboxCommit.Staged(pin, e, "Alice") { notifications += e.id }
                    }
                },
                commit = { pin ->
                    val session = sessionRow(bobOrch.persist(pin)!!)
                    val failOnce = hooks.failSessionUpsertOnce
                    hooks.failSessionUpsertOnce = false
                    val result = MeshInboxCommit(
                        db,
                        beforeSessionUpsert = { if (failOnce) throw IOException("simulated contact_sessions failure") },
                    ).commit(staged.filter { it.peerPin == pin }, session)
                    committed = result.inserted
                },
                report = { _, _, o -> outcomes += o },
                lockWaitMs = hooks.lockWaitMs,
            )
            if (ok) MeshInboxCommit.runAfterCommit(committed)
            return ok
        }

        /** EmergencyMeshService.ingest wiring (custody admission, single offer, drop on success). */
        suspend fun ingest(frames: ByteArray, drop: suspend (String) -> Unit = { engine.dropDelivered(it) }) {
            MeshCourier.ingest(
                bytes = frames,
                path = RadioPath.BLE,
                store = { encoded, via -> engine.ingest(encoded, via) },
                deliver = { payload -> deliver(payload) },
                dropDelivered = drop,
            )
        }
    }

    /** A carrier hands the sealed envelope over: hop 1 on the wire, hop 2 once admitted here. */
    private fun frames(payload: ByteArray, hop: Int = 1): ByteArray = MeshFrames.encodeBundles(
        listOf(
            BundleCodec.encode(
                CustodyBundle(MeshIds.idFor(payload), nowMs, MeshPolicy.expireAt(nowMs), hop, true, payload),
            ),
        ),
    )

    private fun idOf(payload: ByteArray) = MeshIds.hex(MeshIds.idFor(payload))

    @After
    fun close() {
        scopes.forEach { it.cancel() }
        db.close()
    }

    private suspend fun assertConsistent(p: Phone) {
        assertEquals("in-memory MLS must equal durable MLS", p.durableRx(), p.rx())
    }

    // 0 (before evidence) -------------------------------------------------------------------------
    @Test
    fun withoutRecoveryHeldLocalRowIsNeverRedeliveredByCourier() = runBlocking {
        val p = Phone()
        val (_, sealed, ct) = p.seal("stuck before 1H-2")
        p.hooks.lockWaitMs = 50
        lock.lock()
        try { p.ingest(frames(sealed)) } finally { lock.unlock() }
        assertEquals(listOf(MeshDelivery.Outcome.LOCK_TIMEOUT), p.outcomes)
        p.hooks.lockWaitMs = MeshDelivery.MESH_LOCK_WAIT_MS
        // The same bundle arrives again from another carrier: Duplicate, never offered.
        p.ingest(frames(sealed))
        assertEquals(1, p.deliverCalls)
        assertEquals(setOf(idOf(sealed)), p.custodyIds())
        assertTrue(p.rows().isEmpty())
        assertNull(bobMls.decrypts[ct])
    }

    // 1 ------------------------------------------------------------------------------------------
    @Test
    fun lockTimeoutThenRecoveryDeliversOnceAndDropsOnlyThatRow() = runBlocking {
        val p = Phone()
        val carol = Phone("contact-carol", "conversation-carol", RxMls())
        val (m1, sealed, ct) = p.seal("lock timeout")
        val (_, foreign, _) = carol.seal("transit, not for Bob")
        val base = p.durableRx()
        p.hooks.lockWaitMs = 50
        lock.lock()
        try { p.ingest(frames(sealed)) } finally { lock.unlock() }
        p.hooks.lockWaitMs = MeshDelivery.MESH_LOCK_WAIT_MS
        assertEquals(listOf(MeshDelivery.Outcome.LOCK_TIMEOUT), p.outcomes)
        assertTrue(p.engine.ingest(MeshFrames.decodeBundles(frames(foreign)).single(), RadioPath.BLE) is MeshAdmission.Admitted)
        assertEquals(setOf(idOf(sealed), idOf(foreign)), p.custodyIds())
        assertTrue(p.rows().isEmpty())
        assertNull("no decrypt on LOCK_TIMEOUT", bobMls.decrypts[ct])
        assertEquals(MeshCustodyRecovery.PassResult.RAN, p.recovery.passNow())
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertEquals(1, bobMls.decrypts[ct])
        assertEquals("only the delivered row is dropped", setOf(idOf(foreign)), p.custodyIds())
        assertEquals(base + 1, p.durableRx())
        assertConsistent(p)
    }

    // 2 ------------------------------------------------------------------------------------------
    @Test
    fun coldStartRestoreThenRecoveryDelivers() = runBlocking {
        val p = Phone()
        val (m1, sealed, ct) = p.seal("admitted, process died before delivery")
        val base = p.durableRx()
        // Admission committed to Room; process died before the single courier offer.
        assertTrue(p.engine.ingest(MeshFrames.decodeBundles(frames(sealed)).single(), RadioPath.BLE) is MeshAdmission.Admitted)
        p.restart()
        assertEquals(setOf(idOf(sealed)), p.engine.recoveryCandidates().map { it.idHex }.toSet())
        assertEquals(MeshCustodyRecovery.PassResult.RAN, p.recovery.passNow())
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertEquals(1, bobMls.decrypts[ct])
        assertTrue(p.custodyIds().isEmpty())
        assertEquals(base + 1, p.durableRx())
        assertConsistent(p)
    }

    // 3 ------------------------------------------------------------------------------------------
    @Test
    fun mlsPersistenceFailureThenRecoveryDelivers() = runBlocking {
        val p = Phone()
        val (m1, sealed, _) = p.seal("persist failure")
        val base = p.durableRx()
        p.hooks.failSessionUpsertOnce = true
        p.ingest(frames(sealed))
        assertEquals(listOf(MeshDelivery.Outcome.PERSIST_FAILED), p.outcomes)
        assertTrue(p.rows().isEmpty())
        assertTrue(p.notifications.isEmpty())
        assertEquals(setOf(idOf(sealed)), p.custodyIds())
        assertEquals(base, p.durableRx())
        assertConsistent(p)
        p.recovery.passNow()
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertTrue(p.custodyIds().isEmpty())
        assertEquals(base + 1, p.durableRx())
        assertConsistent(p)
    }

    // 4 ------------------------------------------------------------------------------------------
    @Test
    fun cancellationThenRecoveryDelivers() = runBlocking {
        val p = Phone()
        val (m1, sealed, _) = p.seal("cancelled encounter")
        val base = p.durableRx()
        val reached = CompletableDeferred<Unit>()
        p.hooks.stageGate = { reached.complete(Unit); CompletableDeferred<Unit>().await() }
        val job = launch(Dispatchers.Default) { p.ingest(frames(sealed)) }
        withTimeout(5_000) { reached.await() }
        job.cancel() // e.g. the 20 s encounter withTimeout
        withTimeout(5_000) { job.join() }
        p.hooks.stageGate = {}
        assertFalse(lock.isLocked)
        assertTrue(p.rows().isEmpty())
        assertEquals(setOf(idOf(sealed)), p.custodyIds())
        assertEquals(base, p.durableRx())
        assertConsistent(p)
        p.recovery.passNow()
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertTrue(p.custodyIds().isEmpty())
        assertEquals(base + 1, p.durableRx())
        assertConsistent(p)
    }

    // 5 ------------------------------------------------------------------------------------------
    @Test
    fun processRestartWithHeldBundleRecoversFromDurableState() = runBlocking {
        val p = Phone()
        val (m1, sealed, _) = p.seal("held across restart")
        val base = p.durableRx()
        p.hooks.failSessionUpsertOnce = true
        p.ingest(frames(sealed))
        val heldRow = db.meshCustodyDao().all().single()
        p.restart()
        assertEquals(base, p.durableRx())
        assertEquals(MeshCustodyRecovery.PassResult.RAN, p.recovery.passNow())
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertEquals(base + 1, p.durableRx())
        assertConsistent(p)
        assertTrue(p.custodyIds().isEmpty())
        assertEquals(2, heldRow.hopCount)
    }

    // 6 ------------------------------------------------------------------------------------------
    @Test
    fun transitOnlyBundleIsBoundedAndKeptUnchangedForForwarding() = runBlocking {
        val p = Phone()
        val carol = Phone("contact-carol", "conversation-carol", RxMls()) // unrelated pair
        val (_, foreign, ct) = carol.seal("for someone else")
        val base = p.durableRx()
        p.ingest(frames(foreign))
        val rowBefore = db.meshCustodyDao().all().single { it.id == idOf(foreign) }
        repeat(MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW + 3) { p.recovery.passNow() }
        // 1 courier offer + MAX_ATTEMPTS_PER_ROW recovery offers, never more.
        assertEquals(1 + MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW, p.deliverCalls)
        assertEquals(MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW, p.recovery.stats().deliverCalls)
        val rowAfter = db.meshCustodyDao().all().single { it.id == idOf(foreign) }
        assertEquals(rowBefore.hopCount, rowAfter.hopCount)
        assertEquals(rowBefore.forwardedCount, rowAfter.forwardedCount)
        assertEquals(rowBefore.emergency, rowAfter.emergency)
        assertEquals(rowBefore.expireAtMs, rowAfter.expireAtMs)
        assertArrayEquals(rowBefore.payload, rowAfter.payload)
        // Forwarding unchanged: still offered to the next carrier.
        val offered = p.engine.offers(emptyList()).map { BundleCodec.decode(it) }
        assertTrue(offered.any { (it as? com.codeyantra.armorchat.core.mesh.BundleDecode.Ok)?.bundle?.idHex == idOf(foreign) })
        assertTrue(p.rows().isEmpty())
        assertEquals(base, p.durableRx())
        assertConsistent(p)
        assertNull("no successful decrypt of the foreign ciphertext on Bob's phone", bobMls.decrypts[ct])
    }

    // 7 ------------------------------------------------------------------------------------------
    @Test
    fun duplicateIncomingWhileHeldIsNotOfferedAndRecoveryDeliversOnce() = runBlocking {
        val p = Phone()
        val (m1, sealed, ct) = p.seal("duplicate while held")
        p.hooks.failSessionUpsertOnce = true
        p.ingest(frames(sealed))
        val callsAfterFirst = p.deliverCalls
        p.ingest(frames(sealed, hop = 3))
        assertEquals("Duplicate is never offered", callsAfterFirst, p.deliverCalls)
        assertEquals(2, db.meshCustodyDao().all().single().hopCount)
        p.recovery.passNow()
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertEquals(2, bobMls.decrypts[ct]) // failed commit (rolled back) + recovery
        assertTrue(p.custodyIds().isEmpty())
        assertConsistent(p)
    }

    // 8 ------------------------------------------------------------------------------------------
    @Test
    fun alreadyCommittedInboxRowAfterRestartIsSkippedWithoutDecrypt() = runBlocking {
        val p = Phone()
        val (m1, sealed, ct) = p.seal("crash after commit, before custody drop")
        val base = p.durableRx()
        // Commit succeeds; the process dies before dropDelivered.
        p.ingest(frames(sealed), drop = { })
        assertEquals(listOf(m1), p.rows())
        assertEquals(setOf(idOf(sealed)), p.custodyIds())
        assertEquals(base + 1, p.durableRx())
        p.restart()
        val callsBefore = p.deliverCalls
        repeat(3) { p.recovery.passNow() }
        assertEquals("no delivery, no trial decrypt", callsBefore, p.deliverCalls)
        assertEquals(1, bobMls.decrypts[ct])
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertEquals("no second MLS advance", base + 1, p.durableRx())
        assertEquals(1, p.recovery.stats().skippedStored)
        assertEquals("row kept (forwarding unchanged) until its deadline", setOf(idOf(sealed)), p.custodyIds())
        p.load()
        assertConsistent(p)
    }

    // 8b (evidence for why the durable pre-check exists) ------------------------------------------
    @Test
    fun withoutPreCheckFakeEngineWouldDecryptReplayAgainAfterRestart() = runBlocking {
        val p = Phone()
        val (m1, sealed, ct) = p.seal("replay on the fake engine")
        p.ingest(frames(sealed), drop = { })
        val afterFirst = p.durableRx()
        p.restart()
        p.deliver(sealed) // bypassing recovery: what an unguarded retry would do
        assertEquals("JVM fake engine re-decrypts a consumed ciphertext (native: unverified)", 2, bobMls.decrypts[ct])
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertTrue(p.durableRx() > afterFirst)
    }

    // 9 ------------------------------------------------------------------------------------------
    @Test
    fun successThenReplayHeldAfterRestartIsSkippedByRecovery() = runBlocking {
        val p = Phone()
        val (m1, sealed, ct) = p.seal("delivered then replayed")
        p.ingest(frames(sealed))
        assertTrue(p.custodyIds().isEmpty())
        val afterFirst = p.durableRx()
        p.restart()
        // A replayed copy arrives while relay holds the mutex: admitted, offer times out, held.
        p.hooks.lockWaitMs = 50
        lock.lock()
        try { p.ingest(frames(sealed, hop = 2)) } finally { lock.unlock() }
        p.hooks.lockWaitMs = MeshDelivery.MESH_LOCK_WAIT_MS
        assertEquals(setOf(idOf(sealed)), p.custodyIds())
        repeat(3) { p.recovery.passNow() }
        assertEquals(1, bobMls.decrypts[ct])
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertEquals(afterFirst, p.durableRx())
    }

    // 10 -----------------------------------------------------------------------------------------
    @Test
    fun concurrentRelaySyncAndRecoveryDeliverOnceWithoutExtraDecrypt() = runBlocking {
        val p = Phone()
        val (m1, sealed, ct) = p.seal("relay and mesh race") // also posted to the relay mailbox
        val base = p.durableRx()
        p.hooks.failSessionUpsertOnce = true
        p.ingest(frames(sealed))
        assertEquals(setOf(idOf(sealed)), p.custodyIds())
        assertEquals(1, bobMls.decrypts[ct])
        val relayHolding = CompletableDeferred<Unit>()
        val relayRelease = CompletableDeferred<Unit>()
        val relay = launch(Dispatchers.Default) {
            lock.withLock { // syncContact's critical section (SIMULATED wrapper, real orchestrator.sync)
                relayHolding.complete(Unit)
                relayRelease.await()
                p.load()
                p.bobOrch.sync(p.alice.pin) { r ->
                    MeshInboxCommit.inboundRow(conversationId, r, nowMs)?.let { e ->
                        if (db.messageDao().findById(e.id) == null) {
                            db.messageDao().insert(e)
                            p.notifications += e.id
                        }
                    }
                }
                db.contactSessionDao().upsert(p.sessionRow(p.bobOrch.persist(p.alice.pin)!!))
            }
        }
        withTimeout(5_000) { relayHolding.await() }
        // Recovery passes the durable pre-check (nothing stored yet) and then waits on the mutex.
        val pass = async(Dispatchers.Default) { p.recovery.passNow() }
        kotlinx.coroutines.delay(200)
        relayRelease.complete(Unit)
        withTimeout(10_000) { relay.join() }
        assertEquals(MeshCustodyRecovery.PassResult.RAN, withTimeout(15_000) { pass.await() })
        assertEquals(listOf(m1), p.rows())
        assertEquals(listOf(m1), p.notifications)
        assertEquals("seen store / pre-check: no extra decrypt", 2, bobMls.decrypts[ct])
        assertEquals(base + 1, p.durableRx())
        assertConsistent(p)
        assertTrue(p.custodyIds().isEmpty())
    }

    // 11 -----------------------------------------------------------------------------------------
    @Test
    fun recoveryDoesNotRunDuringEncounter() = runBlocking {
        val p = Phone()
        val (m1, sealed, _) = p.seal("encounter gate")
        p.hooks.failSessionUpsertOnce = true
        p.ingest(frames(sealed))
        val calls = p.deliverCalls
        p.recovery.encounterStarted()
        assertEquals(MeshCustodyRecovery.PassResult.DEFERRED_ENCOUNTER, p.recovery.passNow())
        assertEquals(calls, p.deliverCalls)
        assertEquals(setOf(idOf(sealed)), p.custodyIds())
        p.recovery.encounterEnded()
        assertEquals(MeshCustodyRecovery.PassResult.RAN, p.recovery.passNow())
        assertEquals(listOf(m1), p.rows())
        assertTrue(p.custodyIds().isEmpty())
    }

    // 12 -----------------------------------------------------------------------------------------
    @Test
    fun candidateLimitPerPassWithRealCustody() = runBlocking {
        val p = Phone()
        val carol = Phone("contact-carol", "conversation-carol", RxMls())
        val ids = (0 until 6).map { i ->
            val (_, foreign, _) = carol.seal("transit $i")
            assertTrue(p.engine.ingest(MeshFrames.decodeBundles(frames(foreign)).single(), RadioPath.BLE) is MeshAdmission.Admitted)
            idOf(foreign)
        }
        p.recovery.passNow()
        assertEquals(MeshCustodyRecovery.MAX_CANDIDATES_PER_PASS, p.deliverCalls)
        assertEquals(ids.toSet(), p.custodyIds())
    }

    // 13 -----------------------------------------------------------------------------------------
    @Test
    fun ownOutboundSealedRowsAreNeverCandidates() = runBlocking {
        val p = Phone()
        // Bob's own outbound held for carriers (holdSealed: hopCount 0).
        val mine = "{\"bob-own-sealed\":1}".toByteArray(Charsets.UTF_8)
        p.engine.holdSealed(mine, emergency = true)
        assertEquals(setOf(idOf(mine)), p.custodyIds())
        assertTrue(p.engine.recoveryCandidates().isEmpty())
        p.recovery.passNow()
        assertEquals(0, p.deliverCalls)
        assertEquals(setOf(idOf(mine)), p.custodyIds())
    }

    // 14 -----------------------------------------------------------------------------------------
    @Test
    fun recoveryKeepsDealRefusalAndIsBounded() = runBlocking {
        val p = Phone()
        var dealCalls = 0
        p.bobOrch.link(p.alice.pin)!!.receipts.dealInbound = { dealCalls++ }
        val aliceKey = Base64Url.decode(p.alice.pin)
        val bobKey = Base64Url.decode(p.bob.pin)
        val records = p.aliceOrch.link(p.bob.pin)!!.receipts.sendDealPayload(
            DealEnvelopes.encode(
                DealEnvelope(
                    event = DealWireEvent.PROPOSAL,
                    envelopeId = DealIds.generate(),
                    dealId = DealIds.generate(),
                    versionId = DealIds.generate(),
                    senderId = ArmorChatId.derive(aliceKey),
                    recipientId = ArmorChatId.derive(bobKey),
                    senderKey = aliceKey,
                    recipientKey = bobKey,
                    conversationId = conversationId,
                    createdAtEpochMs = nowMs,
                    payload = ByteArray(96) { (it % 7).toByte() },
                ),
            ),
        )
        assertTrue(records.isNotEmpty())
        val base = p.durableRx()
        val sessionBefore = db.contactSessionDao().find(contactId)!!.mlsSnapshot!!.toList()
        val payloads = records.map { it.envelopeCanonical!!.toByteArray(Charsets.UTF_8) }
        payloads.forEach { p.ingest(frames(it)) }
        val passes = MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW *
            ((payloads.size + MeshCustodyRecovery.MAX_CANDIDATES_PER_PASS - 1) / MeshCustodyRecovery.MAX_CANDIDATES_PER_PASS) + 2
        repeat(passes) { p.recovery.passNow() }
        assertEquals(0, dealCalls)
        assertTrue(p.rows().isEmpty())
        assertTrue(p.notifications.isEmpty())
        assertEquals(base, p.durableRx())
        assertEquals(sessionBefore, db.contactSessionDao().find(contactId)!!.mlsSnapshot!!.toList())
        assertConsistent(p)
        assertEquals(payloads.map { idOf(it) }.toSet(), p.custodyIds())
        payloads.forEach { assertEquals(MeshCustodyRecovery.MAX_ATTEMPTS_PER_ROW, p.recovery.attemptsFor(idOf(it))) }
    }

    // 15 -----------------------------------------------------------------------------------------
    @Test
    fun recoveryAttemptCancelledThenLaterPassDelivers() = runBlocking {
        val p = Phone()
        val (m1, sealed, _) = p.seal("recovery attempt cancelled")
        p.hooks.failSessionUpsertOnce = true
        p.ingest(frames(sealed))
        val reached = CompletableDeferred<Unit>()
        p.hooks.stageGate = { reached.complete(Unit); CompletableDeferred<Unit>().await() }
        val job = launch(Dispatchers.Default) { p.recovery.passNow() }
        withTimeout(5_000) { reached.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        p.hooks.stageGate = {}
        assertTrue(p.rows().isEmpty())
        assertEquals(setOf(idOf(sealed)), p.custodyIds())
        assertEquals(1, p.recovery.attemptsFor(idOf(sealed)))
        assertConsistent(p)
        p.recovery.passNow()
        assertEquals(listOf(m1), p.rows())
        assertTrue(p.custodyIds().isEmpty())
        assertConsistent(p)
    }
}
