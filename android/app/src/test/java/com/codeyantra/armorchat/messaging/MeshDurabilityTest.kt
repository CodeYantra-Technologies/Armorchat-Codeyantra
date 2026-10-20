package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.mesh.BundleCodec
import com.codeyantra.armorchat.core.mesh.CustodyBundle
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshCourier
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
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-1F: durability of the MLS session after a successful mesh delivery, and cancellation.
 *
 * REAL production code: SessionOrchestrator.offerMesh / persist / restore, ReceiptMessagingPeer,
 * TwoPhonePeer + MlsEngine snapshot/restore, MeshDelivery (the code ChatMessagingService.deliverMesh
 * calls), MeshCourier.ingest, EmergencyMeshEngine on in-memory Room.
 *
 * SIMULATED (ChatMessagingService is not constructible on the JVM):
 *  - [Durable] stands in for the contact_sessions row: commit() stores the real
 *    SessionOrchestrator.persist(pin) output (what ChatMessagingService.persist(contact) upserts);
 *    load() is loadIfNeeded/restoreRow, i.e. the real SessionOrchestrator.restore of that row.
 *  - [Inbox] models persistInbound's messageDao.findById -> insert.
 *  - MLS is FakeMls plus an "rx" decrypt counter (as in MeshRelayConcurrencyTest). These tests are
 *    NOT proof of native mls-rs behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshDurabilityTest {

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

    private class RxMls {
        val decryptsByCiphertext = mutableMapOf<String, Int>()

        fun transact(state: ByteArray, request: String): ByteArray {
            val out = FakeMls.transact(state, request)
            val req = CanonicalJson.parseObject(request)
            if (req["op"] != "Decrypt") return out
            val ct = req["ciphertext"] as String
            decryptsByCiphertext[ct] = (decryptsByCiphertext[ct] ?: 0) + 1
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

    private class Inbox {
        val inserted = mutableListOf<String>()
        var gate: CompletableDeferred<Unit>? = null
        var reached: CompletableDeferred<Unit>? = null
        suspend fun persist(id: String) {
            if (id in inserted) return
            reached?.complete(Unit)
            gate?.await()
            inserted += id
        }
    }

    private var nowMs = 1_800_000_000_000L
    private val origin = "https://relay-mesh-1f.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-1f", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val bobMls = RxMls()
    private val databases = mutableListOf<ArmorChatDatabase>()
    private val lock = Mutex()

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

    private inner class Pair2 {
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceOrch = orch(FakeMls::transact)
        var bobOrch = orch(bobMls::transact)

        /** contact_sessions row for Alice on Bob's phone (SIMULATED table, real persist output). */
        var saved: PersistedLink? = null
        var failNextCommit = false
        var commits = 0

        init {
            runBlocking {
                aliceOrch.provision(alice, bob.pin)
                bobOrch.provision(bob, alice.pin)
                aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
                confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
                check(bobOrch.sessionView(bob.pin, alice.pin).composerAllowed)
                saved = bobOrch.persist(alice.pin) // syncContact persisted the established session
            }
        }

        /** ChatMessagingService.persist(contact). */
        fun commit(pin: String) {
            if (failNextCommit) {
                failNextCommit = false
                throw IOException("simulated contact_sessions failure")
            }
            saved = bobOrch.persist(pin)
            commits++
        }

        /** loadIfNeeded -> restoreRow -> SessionOrchestrator.restore. */
        fun load() {
            if (bobOrch.link(alice.pin) != null) return
            saved?.let { bobOrch.restore(bob, it, bob.pin) }
        }

        /** Process death: in-memory sessions, seen store and MLS state are gone. */
        fun restart() {
            bobOrch = orch(bobMls::transact)
        }

        fun rx(): Long = rxOf(bobOrch.link(alice.pin)?.peer?.snapshot())
        fun durableRx(): Long = rxOf(saved?.mlsSnapshot)
        fun accepted(id: String) = bobOrch.link(alice.pin)?.receipts?.inbound(id)?.accepted == true

        fun seal(text: String): Triple<String, ByteArray, String> = runBlocking {
            val r = aliceOrch.sendText(bob.pin, text)
            val canonical = r.envelopeCanonical!!
            val ct = org.json.JSONObject(canonical).getString("encrypted_payload")
            Triple(r.messageId, canonical.toByteArray(Charsets.UTF_8), ct)
        }
    }

    /** What ChatMessagingService.deliverMesh runs (lambdas mirror its production wiring). */
    private suspend fun deliverMesh(
        p: Pair2,
        payload: ByteArray,
        inbox: Inbox,
        clockMs: () -> Long = { System.nanoTime() / 1_000_000 },
        report: (Long, Long, MeshDelivery.Outcome) -> Unit = { _, _, _ -> },
        lockWaitMs: Long = MeshDelivery.MESH_LOCK_WAIT_MS,
    ): Boolean = MeshDelivery.deliverDurable(
        lock = lock,
        payload = payload,
        load = { p.load() },
        checkpoint = { p.bobOrch.meshCheckpoint() },
        rollback = { p.bobOrch.restoreMeshCheckpoint(it) },
        offer = { raw, cb -> p.bobOrch.offerMesh(raw, cb) },
        persistInbound = { _, r -> inbox.persist(r.messageId) },
        commit = { pin -> p.commit(pin) },
        clockMs = clockMs,
        report = report,
        lockWaitMs = lockWaitMs,
    )

    @After
    fun close() {
        databases.forEach { it.close() }
    }

    // 1 ------------------------------------------------------------------------------------------
    @Test
    fun successfulMeshDeliveryPersistsMlsSessionBeforeReportingSuccess() = runBlocking {
        val p = Pair2()
        val (m1, sealed1, _) = p.seal("one")
        val rxBase = p.rx()
        assertEquals(rxBase, p.durableRx())
        val inbox = Inbox()
        assertTrue(deliverMesh(p, sealed1, inbox))
        assertEquals(listOf(m1), inbox.inserted)
        assertEquals(rxBase + 1L, p.rx())
        assertEquals("durable MLS session stale after reported mesh success", p.rx(), p.durableRx())
    }

    // 2 ------------------------------------------------------------------------------------------
    @Test
    fun meshProgressSurvivesProcessDeathAndNextMessageDecrypts() = runBlocking {
        val p = Pair2()
        val (m1, sealed1, _) = p.seal("before death")
        val (m2, sealed2, _) = p.seal("after restart")
        val rxBase = p.rx()
        val inbox = Inbox()
        assertTrue(deliverMesh(p, sealed1, inbox))
        p.restart()
        p.load()
        assertEquals("MLS progress lost across restart", rxBase + 1L, p.rx())
        assertTrue(deliverMesh(p, sealed2, inbox))
        assertEquals(listOf(m1, m2), inbox.inserted)
        assertEquals(rxBase + 2L, p.durableRx())
    }

    // 3 ------------------------------------------------------------------------------------------
    @Test
    fun coldStartMeshDeliveryLoadsSavedSessionBeforeOffering() = runBlocking {
        val p = Pair2()
        val (m1, sealed1, _) = p.seal("cold start")
        val rxBase = p.durableRx()
        p.restart() // no syncContact has run yet: no live link
        assertNull(p.bobOrch.link(p.alice.pin))
        val inbox = Inbox()
        assertTrue("cold-start mesh delivery must load the saved session", deliverMesh(p, sealed1, inbox))
        assertEquals(listOf(m1), inbox.inserted)
        assertEquals(rxBase + 1L, p.durableRx())
    }

    // 4 ------------------------------------------------------------------------------------------
    @Test
    fun mlsPersistFailureReportsFailureAndKeepsMemoryEqualToDurable() = runBlocking {
        val p = Pair2()
        val (m1, sealed1, ct1) = p.seal("persist fails")
        val rxBase = p.rx()
        val inbox = Inbox()
        p.failNextCommit = true
        assertFalse("delivery reported success although MLS persist failed", deliverMesh(p, sealed1, inbox))
        // Inbox row committed before the MLS persist (documented crash/failure window).
        assertEquals(listOf(m1), inbox.inserted)
        assertEquals("durable untouched", rxBase, p.durableRx())
        assertEquals("in-memory MLS must equal durable after persist failure", p.durableRx(), p.rx())
        assertFalse("in-memory accept rolled back with the MLS state", p.accepted(m1))
        // Retry (same ciphertext): re-decrypts from the restored state, row deduplicated by id.
        assertTrue(deliverMesh(p, sealed1, inbox))
        assertEquals("no duplicate inbox row", listOf(m1), inbox.inserted)
        assertEquals("exactly one net MLS advance", rxBase + 1L, p.rx())
        assertEquals(p.rx(), p.durableRx())
        assertEquals(2, bobMls.decryptsByCiphertext[ct1])
        assertTrue(p.accepted(m1))
    }

    // 5 ------------------------------------------------------------------------------------------
    @Test
    fun cancellationDuringInboxPersistPropagatesAndLeavesNoState() = runBlocking {
        val p = Pair2()
        val (_, sealed1, _) = p.seal("cancel me")
        val rxBase = p.rx()
        val inbox = Inbox().apply {
            gate = CompletableDeferred()
            reached = CompletableDeferred()
        }
        val outcome = CompletableDeferred<String>()
        val job = launch {
            try {
                outcome.complete("returned:" + deliverMesh(p, sealed1, inbox))
            } catch (c: CancellationException) {
                outcome.complete("cancelled")
                throw c
            }
        }
        withTimeout(5_000) { inbox.reached!!.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals("cancellation converted into an ordinary decrypt failure", "cancelled", outcome.await())
        assertFalse("mutex released after cancellation", lock.isLocked)
        assertTrue(inbox.inserted.isEmpty())
        assertEquals(rxBase, p.rx())
        assertEquals(rxBase, p.durableRx())
        assertEquals(0, p.commits)
    }

    // 6 ------------------------------------------------------------------------------------------
    @Test
    fun cancelledCourierDeliveryKeepsCustodyRowAndCommitsNothing() = runBlocking {
        val p = Pair2()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArmorChatDatabase::class.java)
            .allowMainThreadQueries().build().also { databases += it }
        val engine = EmergencyMeshEngine(RuntimeEnvironment.getApplication(), db.meshCustodyDao(), AppClock { nowMs })
        val (_, sealed1, _) = p.seal("courier cancel")
        val rxBase = p.rx()
        val id = MeshIds.idFor(sealed1)
        val frames = MeshFrames.encodeBundles(
            listOf(
                BundleCodec.encode(
                    CustodyBundle(
                        id = id,
                        createdAtMs = nowMs,
                        expireAtMs = MeshPolicy.expireAt(nowMs),
                        hopCount = 0,
                        emergency = true,
                        payload = sealed1,
                    ),
                ),
            ),
        )
        val inbox = Inbox().apply {
            gate = CompletableDeferred()
            reached = CompletableDeferred()
        }
        var dropped = 0
        val outcome = CompletableDeferred<String>()
        val job = launch {
            try {
                MeshCourier.ingest(
                    bytes = frames,
                    path = RadioPath.BLE,
                    store = { encoded, via -> engine.ingest(encoded, via) },
                    deliver = { payload -> deliverMesh(p, payload, inbox) },
                    dropDelivered = { idHex -> dropped++; engine.dropDelivered(idHex) },
                )
                outcome.complete("completed")
            } catch (c: CancellationException) {
                outcome.complete("cancelled")
                throw c
            }
        }
        withTimeout(5_000) { inbox.reached!!.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals("cancelled", outcome.await())
        assertEquals(0, dropped)
        assertEquals(setOf(MeshIds.hex(id)), db.meshCustodyDao().all().map { it.id }.toSet())
        assertTrue(inbox.inserted.isEmpty())
        assertEquals(rxBase, p.rx())
        assertFalse(lock.isLocked)
    }

    // 7 ------------------------------------------------------------------------------------------
    @Test
    fun undecryptableMeshPayloadDoesNotPersistOrAdvance() = runBlocking {
        val p = Pair2()
        val other = Pair2()
        val (_, foreign, _) = other.seal("not for bob")
        val rxBase = p.rx()
        val inbox = Inbox()
        assertFalse(deliverMesh(p, foreign, inbox))
        assertEquals(0, p.commits)
        assertEquals(rxBase, p.rx())
        assertEquals(rxBase, p.durableRx())
        assertTrue(inbox.inserted.isEmpty())
    }


    // 8-11: lock latency (Mesh-1F Step 4, re-baselined by Mesh-1G') -------------------------------
    /**
     * SIMULATED contention in virtual time: a relay sync holds the messaging mutex for [holdMs]
     * (standing in for blocking relay HTTP calls; production uses OkHttp execute() with a 30 s
     * callTimeout, which is real-thread blocking and NOT virtual), while a BLE encounter offers a
     * mesh bundle under NearbyMeshRadio's withTimeout(20_000) budget. Virtual time says nothing
     * about real BLE/OkHttp timing.
     */
    private class Measured(
        val waited: Long?,
        val delivered: Boolean,
        val outcome: MeshDelivery.Outcome?,
        val encounterExpired: Boolean,
        val inbox: Inbox,
        val durableRx: Long,
        val rxBase: Long,
    )

    private fun measure(holdMs: Long, lockWaitMs: Long = MeshDelivery.MESH_LOCK_WAIT_MS): Measured {
        val p = Pair2()
        val (_, sealed1, _) = p.seal("latency $holdMs")
        val rxBase = p.rx()
        val inbox = Inbox()
        var waited: Long? = null
        var delivered = false
        var expired = false
        var outcome: MeshDelivery.Outcome? = null
        runTest {
            val clock = { testScheduler.currentTime }
            val relay = launch { if (holdMs > 0) lock.withLock { delay(holdMs) } }
            testScheduler.runCurrent()
            check(lock.isLocked == holdMs > 0)
            val requested = clock()
            val r = withTimeoutOrNull(ENCOUNTER_BUDGET_MS) {
                deliverMesh(p, sealed1, inbox, clock, { w, _, o -> waited = w; outcome = o }, lockWaitMs)
            }
            expired = r == null
            delivered = r ?: false
            if (expired && waited == null) waited = clock() - requested // encounter expired while queued
            relay.join()
            assertFalse("mutex released", lock.isLocked)
        }
        println("MESH1GP_LATENCY hold_ms=$holdMs lock_wait_ms=$lockWaitMs mesh_wait_ms=$waited delivered=$delivered outcome=$outcome encounter_expired=$expired")
        if (!delivered) {
            assertTrue(inbox.inserted.isEmpty())
            assertEquals(rxBase, p.rx())
            assertEquals(rxBase, p.durableRx())
            assertEquals(0, p.commits)
        }
        return Measured(waited, delivered, outcome, expired, inbox, p.durableRx(), rxBase)
    }

    @Test
    fun relayHold500msMeshDeliversWithinBudget() {
        val m = measure(500)
        assertTrue(m.delivered)
        assertEquals(500L, m.waited)
        assertEquals(MeshDelivery.Outcome.DELIVERED, m.outcome)
        assertEquals(1, m.inbox.inserted.size)
    }

    @Test
    fun relayHold5sMeshDeliversWithinBudget() {
        val m = measure(5_000)
        assertTrue(m.delivered)
        assertEquals(5_000L, m.waited)
        assertEquals(MeshDelivery.Outcome.DELIVERED, m.outcome)
    }

    /** Mesh-1F: encounter budget cancelled the queued wait at 20 s. Mesh-1G': lock timeout at 12 s. */
    @Test
    fun relayHold25sTimesOutAtLockBoundBeforeEncounterBudget() {
        val m = measure(25_000)
        assertFalse("mesh must not deliver while the relay holds the lock past the bound", m.delivered)
        assertEquals(MeshDelivery.Outcome.LOCK_TIMEOUT, m.outcome)
        assertEquals(MeshDelivery.MESH_LOCK_WAIT_MS, m.waited)
        assertFalse("clean typed timeout, not encounter cancellation", m.encounterExpired)
    }

    @Test
    fun relayHold35sTimesOutAtLockBoundBeforeEncounterBudget() {
        val m = measure(35_000)
        assertFalse(m.delivered)
        assertEquals(MeshDelivery.Outcome.LOCK_TIMEOUT, m.outcome)
        assertEquals(MeshDelivery.MESH_LOCK_WAIT_MS, m.waited)
        assertFalse(m.encounterExpired)
    }

    // 12-22: Mesh-1G' bounded lock acquisition ----------------------------------------------------
    @Test
    fun lockAvailableImmediatelyDeliversWithZeroWait() {
        val m = measure(0)
        assertTrue(m.delivered)
        assertEquals(0L, m.waited)
        assertEquals(MeshDelivery.Outcome.DELIVERED, m.outcome)
        assertEquals(m.rxBase + 1L, m.durableRx)
    }

    @Test
    fun lockReleasedBeforeTimeoutDelivers() {
        val m = measure(11_000)
        assertTrue(m.delivered)
        assertEquals(11_000L, m.waited)
        assertEquals(m.rxBase + 1L, m.durableRx)
    }

    @Test
    fun boundaryJustBelowDelivers() {
        val m = measure(MeshDelivery.MESH_LOCK_WAIT_MS - 1)
        assertTrue(m.delivered)
        assertEquals(MeshDelivery.MESH_LOCK_WAIT_MS - 1, m.waited)
    }

    /**
     * Release and timeout due at the same virtual instant. kotlinx-coroutines-test runs the timeout
     * (scheduled earlier) before the resumed waiter; the cancelled lock() releases the lock it was
     * just handed (prompt cancellation guarantee). Documented outcome: LOCK_TIMEOUT, no mutation.
     */
    @Test
    fun boundaryExactlyAtBoundIsTimeoutWithLockReleased() {
        val m = measure(MeshDelivery.MESH_LOCK_WAIT_MS)
        assertFalse(m.delivered)
        assertEquals(MeshDelivery.Outcome.LOCK_TIMEOUT, m.outcome)
        assertEquals(MeshDelivery.MESH_LOCK_WAIT_MS, m.waited)
    }

    @Test
    fun boundaryJustAboveTimesOut() {
        val m = measure(MeshDelivery.MESH_LOCK_WAIT_MS + 1)
        assertFalse(m.delivered)
        assertEquals(MeshDelivery.Outcome.LOCK_TIMEOUT, m.outcome)
        assertEquals(MeshDelivery.MESH_LOCK_WAIT_MS, m.waited)
    }

    @Test
    fun parentCancellationWhileWaitingPropagatesAsCancellationNotTimeout() {
        val p = Pair2()
        val (_, sealed1, _) = p.seal("cancel while queued")
        val rxBase = p.rx()
        val inbox = Inbox()
        val reports = mutableListOf<MeshDelivery.Outcome>()
        var result = "none"
        runTest {
            val relay = launch { lock.withLock { delay(30_000) } }
            testScheduler.runCurrent()
            val job = launch {
                try {
                    result = "returned:" + deliverMesh(p, sealed1, inbox, { testScheduler.currentTime }, { _, _, o -> reports += o })
                } catch (c: CancellationException) {
                    result = "cancelled:" + c.javaClass.simpleName
                    throw c
                }
            }
            testScheduler.advanceTimeBy(5_000)
            testScheduler.runCurrent()
            job.cancel()
            job.join()
            assertTrue(job.isCancelled)
            assertTrue("relay still holds the lock", lock.isLocked)
            relay.join()
            assertFalse("mutex released", lock.isLocked)
            assertTrue("lock usable afterwards", lock.tryLock())
            lock.unlock()
        }
        assertTrue(result, result.startsWith("cancelled:"))
        assertFalse("not a TimeoutCancellationException", result.contains("Timeout"))
        assertTrue("no LOCK_TIMEOUT/other outcome reported for a cancelled wait", reports.isEmpty())
        assertTrue(inbox.inserted.isEmpty())
        assertEquals(rxBase, p.rx())
        assertEquals(rxBase, p.durableRx())
        assertEquals(0, p.commits)
    }

    @Test
    fun cancellationAfterContendedAcquisitionRollsBackRethrowsAndReleases() {
        val p = Pair2()
        val (_, sealed1, _) = p.seal("cancel after acquire")
        val rxBase = p.rx()
        val inbox = Inbox().apply { gate = CompletableDeferred(); reached = CompletableDeferred() }
        val reports = mutableListOf<MeshDelivery.Outcome>()
        var result = "none"
        runTest {
            val relay = launch { lock.withLock { delay(5_000) } }
            testScheduler.runCurrent()
            val job = launch {
                try {
                    result = "returned:" + deliverMesh(p, sealed1, inbox, { testScheduler.currentTime }, { _, _, o -> reports += o })
                } catch (c: CancellationException) {
                    result = "cancelled"
                    throw c
                }
            }
            relay.join()
            inbox.reached!!.await()
            assertTrue("mesh holds the lock", lock.isLocked)
            testScheduler.advanceTimeBy(60_000) // far past the lock bound: no timeout after acquisition
            testScheduler.runCurrent()
            assertTrue("no artificial timeout after acquisition", job.isActive)
            job.cancel()
            job.join()
            assertFalse("mutex released after cancellation", lock.isLocked)
        }
        assertEquals("cancelled", result)
        assertEquals(listOf(MeshDelivery.Outcome.CANCELLED), reports)
        assertTrue(inbox.inserted.isEmpty())
        assertEquals(rxBase, p.rx())
        assertEquals(rxBase, p.durableRx())
        assertEquals(0, p.commits)
    }

    @Test
    fun mutexReleasedAfterSuccessPersistFailureAndTimeout() = runBlocking {
        val p = Pair2()
        val (_, sealed1, _) = p.seal("ok")
        val (_, sealed2, _) = p.seal("persist fail")
        val inbox = Inbox()
        assertTrue(deliverMesh(p, sealed1, inbox))
        assertFalse(lock.isLocked)
        p.failNextCommit = true
        assertFalse(deliverMesh(p, sealed2, inbox))
        assertFalse(lock.isLocked)
        val other = Pair2()
        val (_, foreign, _) = other.seal("declined")
        assertFalse(deliverMesh(p, foreign, inbox))
        assertFalse(lock.isLocked)
        lock.lock()
        val outcomes = mutableListOf<MeshDelivery.Outcome>()
        assertFalse(deliverMesh(p, sealed2, inbox, report = { _, _, o -> outcomes += o }, lockWaitMs = 50))
        assertEquals(listOf(MeshDelivery.Outcome.LOCK_TIMEOUT), outcomes)
        assertTrue("timeout must not release a lock it never owned", lock.isLocked)
        lock.unlock()
        assertTrue(lock.tryLock())
        lock.unlock()
    }

    @Test
    fun lockTimeoutViaCourierKeepsCustodyRowAndDoesNotDrop() = runBlocking {
        val p = Pair2()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArmorChatDatabase::class.java)
            .allowMainThreadQueries().build().also { databases += it }
        val engine = EmergencyMeshEngine(RuntimeEnvironment.getApplication(), db.meshCustodyDao(), AppClock { nowMs })
        val (_, sealed1, ct1) = p.seal("courier lock timeout")
        val rxBase = p.rx()
        val id = MeshIds.idFor(sealed1)
        val frames = MeshFrames.encodeBundles(
            listOf(
                BundleCodec.encode(
                    CustodyBundle(
                        id = id,
                        createdAtMs = nowMs,
                        expireAtMs = MeshPolicy.expireAt(nowMs),
                        hopCount = 0,
                        emergency = true,
                        payload = sealed1,
                    ),
                ),
            ),
        )
        val inbox = Inbox()
        var dropped = 0
        val outcomes = mutableListOf<MeshDelivery.Outcome>()
        lock.lock() // relay sync holding the messaging mutex
        try {
            MeshCourier.ingest(
                bytes = frames,
                path = RadioPath.BLE,
                store = { encoded, via -> engine.ingest(encoded, via) },
                deliver = { payload -> deliverMesh(p, payload, inbox, report = { _, _, o -> outcomes += o }, lockWaitMs = 50) },
                dropDelivered = { idHex -> dropped++; engine.dropDelivered(idHex) },
            )
        } finally {
            lock.unlock()
        }
        assertEquals(listOf(MeshDelivery.Outcome.LOCK_TIMEOUT), outcomes)
        assertEquals(0, dropped)
        assertEquals(setOf(MeshIds.hex(id)), db.meshCustodyDao().all().map { it.id }.toSet())
        assertTrue(inbox.inserted.isEmpty())
        assertEquals(rxBase, p.rx())
        assertEquals(rxBase, p.durableRx())
        assertEquals(0, p.commits)
        assertNull("no decrypt attempted on lock timeout", bobMls.decryptsByCiphertext[ct1])
        assertFalse(lock.isLocked)
    }

    private companion object {
        const val ENCOUNTER_BUDGET_MS = 20_000L // NearbyMeshRadio.kt withTimeout(20_000)
    }
}
