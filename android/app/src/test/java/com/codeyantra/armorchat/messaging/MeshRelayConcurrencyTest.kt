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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-1D-A: relay sync vs mesh delivery on one phone (Bob).
 *
 * REAL production code: SessionOrchestrator.sync / offerMesh, ReceiptMessagingPeer.processLeased,
 * TwoPhonePeer + MlsEngine snapshot/restore, MeshDelivery (the exact code
 * ChatMessagingService.deliverMesh calls), MeshCourier.ingest, EmergencyMeshEngine on in-memory Room.
 *
 * SIMULATED (ChatMessagingService itself is not constructible on the JVM):
 *  - [relayLock] stands in for ChatMessagingService.mutex; [relaySync] mirrors syncContact's
 *    `mutex.withLock { orchestrator.sync(pin) { persistInbound(..) } }`.
 *  - [Table] models ChatMessagingService.persistInbound's `messageDao.findById` -> `insert` pair,
 *    with the Room suspension between them made explicit by a gate.
 *  - MLS is FakeMls wrapped by [RxMls], which adds an "rx" counter to the engine state on every
 *    successful Decrypt so that a stale snapshot restore becomes observable. Native mls-rs advances
 *    its secret tree on decrypt too, but these tests are NOT proof of native MLS behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshRelayConcurrencyTest {

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

    /** FakeMls plus an observable decrypt counter in the engine state. Test double only. */
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
                mapOf(
                    "output" to staged["output"],
                    "state" to Base64Url.encode(CanonicalJson.of(st).toByteArray()),
                ),
            ).toByteArray()
        }
    }

    /** persistInbound's findById -> insert, with an optional gate between them. */
    private class Table {
        val inserted = mutableListOf<String>()
        suspend fun persist(id: String, gate: CompletableDeferred<Unit>?, reached: CompletableDeferred<Unit>?) {
            if (id in inserted) return
            reached?.complete(Unit)
            gate?.await()
            inserted += id
        }
    }

    private var nowMs = 1_800_000_000_000L
    private val origin = "https://relay-mesh-1d.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-1d", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val bobMls = RxMls()
    private val databases = mutableListOf<ArmorChatDatabase>()

    /** ChatMessagingService.mutex stand-in. */
    private val relayLock = Mutex()

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

    private inner class Pair2 {
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceOrch = orch(FakeMls::transact)
        val bobOrch = orch(bobMls::transact)

        init {
            runBlocking {
                aliceOrch.provision(alice, bob.pin)
                bobOrch.provision(bob, alice.pin)
                aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
                confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
                check(bobOrch.sessionView(bob.pin, alice.pin).composerAllowed)
            }
        }

        fun rx(): Long {
            val snap = bobOrch.link(alice.pin)!!.peer.snapshot()
            return (CanonicalJson.parseObject(String(snap, Charsets.UTF_8))["rx"] as? Long) ?: 0L
        }

        fun accepted(id: String) = bobOrch.link(alice.pin)!!.receipts.inbound(id)?.accepted == true

        /** Alice seals a text (also posted to the relay); returns id, canonical envelope, ciphertext. */
        fun seal(text: String): Triple<String, ByteArray, String> = runBlocking {
            val r = aliceOrch.sendText(bob.pin, text)
            val canonical = r.envelopeCanonical!!
            val ct = org.json.JSONObject(canonical).getString("encrypted_payload")
            Triple(r.messageId, canonical.toByteArray(Charsets.UTF_8), ct)
        }
    }

    /** syncContact's critical section (SIMULATED wrapper around the real orchestrator.sync). */
    private suspend fun relaySync(p: Pair2, persist: suspend (InboundRecord) -> Unit) {
        relayLock.withLock { p.bobOrch.sync(p.alice.pin, persist) }
    }

    /** Exactly what ChatMessagingService.deliverMesh runs, with [relayLock] as its mutex. */
    private suspend fun meshDeliver(
        p: Pair2,
        payload: ByteArray,
        persist: suspend (String, InboundRecord) -> Unit,
    ): Boolean = MeshDelivery.deliverSerialized(
        relayLock,
        payload,
        { raw, cb -> p.bobOrch.offerMesh(raw, cb) },
        persist,
    )

    /** Lets a launched job run until it suspends; returns true if it finished. */
    private suspend fun settle(job: Job): Boolean = withTimeoutOrNull(300) { job.join(); true } ?: false

    @After
    fun close() {
        databases.forEach { it.close() }
    }

    // 1 ------------------------------------------------------------------------------------------
    @Test
    fun meshPersistFailureDoesNotRollBackConcurrentRelayProgress() = runBlocking {
        val p = Pair2()
        val (m1, sealed1, _) = p.seal("one")
        val (m2, _, _) = p.seal("two")
        val (m3, _, _) = p.seal("three")
        val rxBase = p.rx() // session setup already performed decrypts
        val table = Table()
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val mesh = async {
            runCatching { meshDeliver(p, sealed1) { _, r -> table.persist(r.messageId, gate, reached) } }
        }
        withTimeout(5_000) { reached.await() }
        val relay = launch { relaySync(p) { r -> table.persist(r.messageId, null, null) } }
        settle(relay)
        gate.completeExceptionally(IOException("simulated Room failure"))
        withTimeout(10_000) { mesh.await(); relay.join() }
        // Relay decrypted m2 and m3; nothing may restore an MLS snapshot older than that progress.
        assertTrue("m2 accepted", p.accepted(m2))
        assertTrue("m3 accepted", p.accepted(m3))
        assertTrue("m1 accepted", p.accepted(m1))
        assertEquals("MLS state lost relay progress (stale restore)", rxBase + 3L, p.rx())
        assertEquals(setOf(m1, m2, m3), table.inserted.toSet())
        assertEquals(3, table.inserted.size)
    }

    // 2 ------------------------------------------------------------------------------------------
    @Test
    fun sameCiphertextViaMeshAndRelayPersistsAndDecryptsOnce() = runBlocking {
        val p = Pair2()
        val (m1, sealed1, ct1) = p.seal("same")
        val rxBase = p.rx()
        val table = Table()
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        val mesh = async { meshDeliver(p, sealed1) { _, r -> table.persist(r.messageId, gate, reached) } }
        withTimeout(5_000) { reached.await() }
        val relay = launch { relaySync(p) { r -> table.persist(r.messageId, null, null) } }
        settle(relay)
        gate.complete(Unit)
        withTimeout(10_000) { assertTrue(mesh.await()); relay.join() }
        assertEquals("duplicate inbox insert", listOf(m1), table.inserted)
        assertEquals("double decrypt", 1, bobMls.decryptsByCiphertext[ct1])
        assertEquals(rxBase + 1L, p.rx())
        assertTrue(p.accepted(m1))
    }

    // 3 ------------------------------------------------------------------------------------------
    @Test
    fun relayPersistFailureDoesNotRollBackConcurrentMeshProgress() = runBlocking {
        val p = Pair2()
        val (ma, sealedA, _) = p.seal("first")
        val (mb, sealedB, _) = p.seal("second")
        val rxBase = p.rx()
        val table = Table()
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<String>()
        var gatedId: String? = null
        // The relay gates (then fails) on whichever record it reaches first.
        val relay = launch {
            relaySync(p) { r ->
                if (gatedId == null) {
                    gatedId = r.messageId
                    reached.complete(r.messageId)
                    gate.await()
                }
                table.persist(r.messageId, null, null)
            }
        }
        val first = withTimeout(5_000) { reached.await() }
        // Mesh carries the OTHER message, which the relay has not decrypted yet.
        val meshId = if (first == ma) mb else ma
        val meshSealed = if (first == ma) sealedB else sealedA
        val rxAtGate = p.rx()
        val mesh = async { meshDeliver(p, meshSealed) { _, r -> table.persist(r.messageId, null, null) } }
        settle(mesh)
        gate.completeExceptionally(IOException("simulated Room failure"))
        withTimeout(10_000) { relay.join(); mesh.await() }
        assertTrue("mesh message accepted", p.accepted(meshId))
        assertTrue(meshId in table.inserted)
        assertEquals(rxBase + 1L, rxAtGate)
        // The relay's failed record is rolled back to its own snapshot; the mesh message's single
        // accepted decrypt must survive it.
        assertEquals("MLS state lost mesh progress (stale restore)", rxBase + 1L, p.rx())
    }

    // 4 ------------------------------------------------------------------------------------------
    @Test
    fun meshDecryptFailureWhileRelayMidSyncKeepsRelayProgress() = runBlocking {
        val p = Pair2()
        val other = Pair2() // unrelated conversation: its ciphertext cannot decrypt on Bob
        val (_, foreign, _) = other.seal("not for bob")
        val (m2, _, _) = p.seal("two")
        val (m3, _, _) = p.seal("three")
        val rxBefore = p.rx()
        val table = Table()
        val gate = CompletableDeferred<Unit>()
        val reached = CompletableDeferred<Unit>()
        var gated = false
        val relay = launch {
            relaySync(p) { r ->
                if (!gated) {
                    gated = true
                    reached.complete(Unit)
                    gate.await()
                }
                table.persist(r.messageId, null, null)
            }
        }
        withTimeout(5_000) { reached.await() }
        val mesh = async { meshDeliver(p, foreign) { _, r -> table.persist(r.messageId, null, null) } }
        settle(mesh)
        gate.complete(Unit)
        withTimeout(10_000) {
            relay.join()
            assertFalse(mesh.await())
        }
        assertTrue(p.accepted(m2))
        assertTrue(p.accepted(m3))
        assertEquals(rxBefore + 2L, p.rx())
        assertEquals(listOf(m2, m3), table.inserted)
    }

    // 5 ------------------------------------------------------------------------------------------
    @Test
    fun courierDeliveryWaitsForMessagingLockWithoutDeadlockAgainstHoldSealed() = runBlocking {
        val p = Pair2()
        val db = Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArmorChatDatabase::class.java)
            .allowMainThreadQueries().build().also { databases += it }
        val engine = EmergencyMeshEngine(RuntimeEnvironment.getApplication(), db.meshCustodyDao(), AppClock { nowMs })
        val (m1, sealed1, _) = p.seal("via courier")
        val (_, sealedOut, _) = p.seal("outgoing while relay down")
        val frames = MeshFrames.encodeBundles(
            listOf(
                BundleCodec.encode(
                    CustodyBundle(
                        id = MeshIds.idFor(sealed1),
                        createdAtMs = nowMs,
                        expireAtMs = MeshPolicy.expireAt(nowMs),
                        hopCount = 0,
                        emergency = true,
                        payload = sealed1,
                    ),
                ),
            ),
        )
        val table = Table()
        val holderIn = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        // Lock order of ChatMessagingService.sendText's fallback: messaging mutex -> engine mutex.
        val holder = launch {
            relayLock.withLock {
                holderIn.complete(Unit)
                release.await()
                engine.holdSealed(sealedOut, emergency = true)
            }
        }
        withTimeout(5_000) { holderIn.await() }
        // EmergencyMeshService.ingest: engine.ingest (engine mutex released) -> deliverMesh -> dropDelivered.
        val courier = launch {
            MeshCourier.ingest(
                bytes = frames,
                path = RadioPath.BLE,
                store = { encoded, via -> engine.ingest(encoded, via) },
                deliver = { payload -> meshDeliver(p, payload) { _, r -> table.persist(r.messageId, null, null) } },
                dropDelivered = { idHex -> engine.dropDelivered(idHex) },
            )
        }
        settle(courier)
        release.complete(Unit)
        withTimeout(10_000) { holder.join(); courier.join() }
        assertEquals(listOf(m1), table.inserted)
        val ids = db.meshCustodyDao().all().map { it.id }.toSet()
        assertEquals(setOf(MeshIds.hex(MeshIds.idFor(sealedOut))), ids)
    }
}
