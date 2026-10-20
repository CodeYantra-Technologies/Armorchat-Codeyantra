package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshFrames
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
import java.util.Collections
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-1K-M (P1): receiver duplicate classification on the Mesh inbound path.
 *
 * REAL production code: MeshDelivery.deliverDurable (lock, pre-check, checkpoint/rollback, outcome
 * report) and MeshDelivery.durableState / outerCiphertextDigest; MeshInboxCommit.commit and
 * Result.outcome on in-memory Room (Robolectric SQLite transactions); MessageDao.findById;
 * SessionOrchestrator.offerMesh/meshCheckpoint/restoreMeshCheckpoint/persist/restore;
 * ReceiptMessagingPeer.processLeased (seen store, receipt queue); EmergencyMeshEngine.ingest/
 * offers/holdSealed/dropDelivered on in-memory `mesh_custody`; MeshCourier.ingest (the exact code
 * EmergencyMeshService.ingest calls).
 *
 * SIMULATED (stated explicitly):
 *  - ChatMessagingService is not constructible on the JVM (Hilt, Keystore, notifications), so
 *    [wiring] mirrors the deliverMesh lambdas line for line, including the new `durable` and
 *    `committedAs` arguments. Contact/conversation lookup is a constant; Keystore encryption of the
 *    contact_sessions row is identity (same as MeshAtomicPersistenceTest).
 *  - MLS is FakeMls + an rx counter (RxMls): NOT evidence of native mls-rs replay behaviour.
 *  - "Restart" = fresh SessionOrchestrator over the same open Room database; NOT process death.
 *  - MeshCustodyRecovery's scheduler is not run; its deliver + dropDelivered pair is called directly.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshDuplicateClassificationTest {

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

    /** FakeMls plus a per-ciphertext decrypt counter and an rx counter in the state. */
    private class RxMls {
        val decrypts: MutableMap<String, Int> = Collections.synchronizedMap(mutableMapOf())
        fun transact(state: ByteArray, request: String): ByteArray {
            val out = FakeMls.transact(state, request)
            val req = CanonicalJson.parseObject(request)
            if (req["op"] != "Decrypt") return out
            val ct = req["ciphertext"] as String
            synchronized(decrypts) { decrypts[ct] = (decrypts[ct] ?: 0) + 1 }
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
    private val origin = "https://relay-mesh-1km.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-1km", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val bobMls = RxMls()
    private val lock = Mutex()
    private val databases = mutableListOf<ArmorChatDatabase>()
    private fun newDb(): ArmorChatDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArmorChatDatabase::class.java)
            .allowMainThreadQueries().build().also { databases += it }
    private val db: ArmorChatDatabase = newDb()

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
    private val outcomes: MutableList<MeshDelivery.Outcome> = Collections.synchronizedList(mutableListOf())

    private class Hooks {
        var failSessionBuild = false
        var findById: (suspend (String) -> MessageEntity?)? = null
        var beforeInsert: suspend (MessageEntity) -> Unit = {}
        var beforeSessionUpsert: suspend (ContactSessionEntity) -> Unit = {}
        var stageGate: suspend () -> Unit = {}
    }

    private inner class Harness {
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceOrch = orch(FakeMls::transact)
        var bobOrch = orch(bobMls::transact)
        val hooks = Hooks()
        val notifications: MutableList<String> = Collections.synchronizedList(mutableListOf())

        init {
            runBlocking {
                aliceOrch.provision(alice, bob.pin)
                bobOrch.provision(bob, alice.pin)
                aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
                confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
                db.conversationDao().insert(ConversationEntity(conversationId, contactId, "Alice", nowMs))
                db.contactSessionDao().upsert(sessionRow(bobOrch.persist(alice.pin)!!))
            }
        }

        private val savedMailbox = bobOrch.link(alice.pin)?.peer?.mailbox

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

        fun rowToLink(row: ContactSessionEntity) = PersistedLink(
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

        fun restart() {
            bobOrch = orch(bobMls::transact)
        }

        fun link() = bobOrch.link(alice.pin)
        fun rx(): Long = rxOf(link()?.peer?.snapshot())
        suspend fun sessionRowNow(): ContactSessionEntity = db.contactSessionDao().find(contactId)!!
        suspend fun durableRx(): Long = rxOf(sessionRowNow().mlsSnapshot)
        suspend fun rows(): List<MessageEntity> = db.messageDao().observeAllMessages().first().filter { !it.isOutgoing }
        suspend fun rowIds(): List<String> = rows().map { it.id }
        fun receiptCount(): Int = link()?.receipts?.store?.snapshot()?.receiptOutbox?.size ?: 0

        /** messageId, envelope bytes, outer ciphertext (b64). */
        fun seal(text: String): Triple<String, ByteArray, String> = runBlocking {
            val r = aliceOrch.sendText(bob.pin, text)
            val canonical = r.envelopeCanonical!!
            Triple(r.messageId, canonical.toByteArray(Charsets.UTF_8), JSONObject(canonical).getString("encrypted_payload"))
        }

        suspend fun deliver(payload: ByteArray): Boolean = wiring(this, payload)
    }

    // WIRING: mirrors ChatMessagingService.deliverMesh after Mesh-1K-M -------------------------------
    private suspend fun wiring(h: Harness, payload: ByteArray): Boolean {
        val staged = mutableListOf<MeshInboxCommit.Staged>()
        var committed: List<MeshInboxCommit.Staged> = emptyList()
        var commitOutcome = MeshDelivery.Outcome.DELIVERED
        val ok = MeshDelivery.deliverDurable(
            lock = lock,
            payload = payload,
            durable = {
                val find = h.hooks.findById ?: { id -> db.messageDao().findById(id) }
                MeshDelivery.durableState(payload, find)
            },
            committedAs = { commitOutcome },
            load = { h.load() },
            checkpoint = { staged.clear(); h.bobOrch.meshCheckpoint() },
            rollback = { staged.clear(); h.bobOrch.restoreMeshCheckpoint(it) },
            offer = { raw, cb -> h.bobOrch.offerMesh(raw, cb) },
            persistInbound = { pin, r ->
                h.hooks.stageGate()
                MeshInboxCommit.inboundRow(conversationId, r, nowMs)?.let { e ->
                    staged += MeshInboxCommit.Staged(pin, e, "Alice") { h.notifications += e.id }
                }
            },
            commit = { pin ->
                if (h.hooks.failSessionBuild) throw IOException("simulated pre-tx failure")
                val session = h.sessionRow(h.bobOrch.persist(pin)!!)
                val result = MeshInboxCommit(db, h.hooks.beforeInsert, h.hooks.beforeSessionUpsert)
                    .commit(staged.filter { it.peerPin == pin }, session)
                committed = result.inserted
                commitOutcome = result.outcome()
            },
            report = { _, _, o -> outcomes += o },
        )
        if (ok) MeshInboxCommit.runAfterCommit(committed)
        return ok
    }

    @After
    fun close() {
        databases.forEach { it.close() }
    }

    private fun withOuterId(payload: ByteArray, id: Any?): ByteArray {
        val o = JSONObject(String(payload, Charsets.UTF_8))
        if (id == null) o.remove("message_id") else o.put("message_id", id)
        return o.toString().toByteArray(Charsets.UTF_8)
    }

    // 1 First message -> DELIVERED ---------------------------------------------------------------
    @Test
    fun firstMessageIsDelivered() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("first")
        val base = h.durableRx()
        assertTrue(h.deliver(s1))
        assertEquals(listOf(MeshDelivery.Outcome.DELIVERED), outcomes)
        assertEquals(listOf(m1), h.rowIds())
        assertEquals(1, bobMls.decrypts[ct1])
        assertEquals(base + 1, h.durableRx())
        assertEquals(h.durableRx(), h.rx())
        assertEquals(1, h.receiptCount())
        assertEquals(listOf(m1), h.notifications)
    }

    // 2-5 Same again -> DUPLICATE; one row; no receipt, no MLS mutation, no decrypt -------------
    @Test
    fun sameEnvelopeAgainIsDuplicateWithoutDecryptReceiptOrMlsWrite() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("twice")
        assertTrue(h.deliver(s1))
        val durableBefore = h.sessionRowNow()
        val memMlsBefore = h.link()!!.peer.snapshot()
        val storeBefore = h.link()!!.receipts.store.snapshot()
        val rowBefore = db.messageDao().findById(m1)!!
        nowMs += 60_000 // any contact_sessions upsert would now change updatedAtEpochMs
        repeat(3) { assertTrue("duplicate keeps the receiver custody-cleanup result", h.deliver(s1)) }
        assertEquals(
            listOf(MeshDelivery.Outcome.DELIVERED) + List(3) { MeshDelivery.Outcome.DUPLICATE },
            outcomes,
        )
        assertFalse(outcomes.drop(1).contains(MeshDelivery.Outcome.DELIVERED))
        assertEquals(listOf(m1), h.rowIds())
        assertEquals(rowBefore, db.messageDao().findById(m1))
        assertEquals("no second MLS decrypt", 1, bobMls.decrypts[ct1])
        assertSame("receipt store never touched", storeBefore, h.link()!!.receipts.store.snapshot())
        assertEquals(1, h.receiptCount())
        assertArrayEquals("in-memory MLS unchanged", memMlsBefore, h.link()!!.peer.snapshot())
        val durableAfter = h.sessionRowNow()
        assertArrayEquals("durable MLS unchanged", durableBefore.mlsSnapshot, durableAfter.mlsSnapshot)
        assertEquals("no contact_sessions upsert", durableBefore.updatedAtEpochMs, durableAfter.updatedAtEpochMs)
        assertEquals(listOf(m1), h.notifications)
        assertFalse(lock.isLocked)
    }

    // 6 Concurrent duplicate arrivals ------------------------------------------------------------
    @Test
    fun concurrentDuplicateArrivalsYieldOneDeliveredRestDuplicate() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("concurrent")
        val base = h.durableRx()
        val start = CompletableDeferred<Unit>()
        val jobs = List(6) { async(Dispatchers.Default) { start.await(); h.deliver(s1) } }
        start.complete(Unit)
        val results = withTimeout(20_000) { jobs.awaitAll() }
        assertTrue(results.all { it })
        assertEquals(1, outcomes.count { it == MeshDelivery.Outcome.DELIVERED })
        assertEquals(5, outcomes.count { it == MeshDelivery.Outcome.DUPLICATE })
        assertEquals(listOf(m1), h.rowIds())
        assertEquals(1, bobMls.decrypts[ct1])
        assertEquals(base + 1, h.durableRx())
        assertEquals(h.durableRx(), h.rx())
        assertEquals(1, h.receiptCount())
        assertEquals(listOf(m1), h.notifications)
        assertFalse(lock.isLocked)
    }

    // 7a DB failure in the durable pre-check (before anything) -----------------------------------
    @Test
    fun databaseFailureInPreCheckTouchesNothingThenDelivers() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("precheck failure")
        val base = h.durableRx()
        h.hooks.findById = { throw IOException("simulated messages read failure") }
        assertFalse(h.deliver(s1))
        assertEquals(listOf(MeshDelivery.Outcome.PERSIST_FAILED), outcomes)
        assertNull("no decrypt when the pre-check failed", bobMls.decrypts[ct1])
        assertTrue(h.rowIds().isEmpty())
        assertEquals(base, h.durableRx())
        assertFalse(lock.isLocked)
        h.hooks.findById = null
        assertTrue(h.deliver(s1))
        assertEquals(MeshDelivery.Outcome.DELIVERED, outcomes.last())
        assertEquals(listOf(m1), h.rowIds())
    }

    // 7b DB failure before the transaction commits ------------------------------------------------
    @Test
    fun databaseFailureBeforeCommitIsNotDuplicateOnRetry() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("pre-tx failure")
        val base = h.durableRx()
        h.hooks.failSessionBuild = true
        assertFalse(h.deliver(s1))
        h.hooks.failSessionBuild = false
        h.hooks.beforeInsert = { throw IOException("simulated insert failure") }
        assertFalse(h.deliver(s1))
        assertTrue(h.rowIds().isEmpty())
        assertEquals(base, h.durableRx())
        assertEquals(h.durableRx(), h.rx())
        assertEquals(0, h.receiptCount())
        h.hooks.beforeInsert = {}
        assertTrue(h.deliver(s1))
        assertEquals(
            listOf(MeshDelivery.Outcome.PERSIST_FAILED, MeshDelivery.Outcome.PERSIST_FAILED, MeshDelivery.Outcome.DELIVERED),
            outcomes,
        )
        assertEquals(listOf(m1), h.rowIds())
        assertEquals(base + 1, h.durableRx())
        assertEquals(1, h.receiptCount())
        assertEquals(listOf(m1), h.notifications)
    }

    // 8 Failure after custody admission but before durable commit (real engine + courier) --------
    @Test
    fun failureAfterAdmissionKeepsCustodyThenDeliversOnceAndLaterCopiesAreDuplicate() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("admitted then failed")
        val app = RuntimeEnvironment.getApplication()
        val aliceDb = newDb()
        val aliceEngine = EmergencyMeshEngine(app, aliceDb.meshCustodyDao(), AppClock { nowMs })
        val bobEngine = EmergencyMeshEngine(app, db.meshCustodyDao(), AppClock { nowMs })
        aliceEngine.holdSealed(s1, emergency = true)
        val frames = MeshFrames.encodeBundles(aliceEngine.offers(emptyList()))
        var deliverCalls = 0
        suspend fun bobReceives() = MeshCourier.ingest(
            bytes = frames,
            path = RadioPath.BLE,
            store = { encoded, via -> bobEngine.ingest(encoded, via) },
            deliver = { payload -> deliverCalls++; h.deliver(payload) },
            dropDelivered = { idHex -> bobEngine.dropDelivered(idHex) },
        )
        val base = h.durableRx()
        h.hooks.beforeSessionUpsert = { throw IOException("simulated contact_sessions failure") }
        bobReceives()
        assertEquals(listOf(MeshDelivery.Outcome.PERSIST_FAILED), outcomes)
        assertEquals("custody copy kept after failed commit", 1, db.meshCustodyDao().all().size)
        assertTrue(h.rowIds().isEmpty())
        assertEquals(base, h.durableRx())
        assertEquals(h.durableRx(), h.rx())
        // A re-received copy while held is a custody Duplicate: never offered to messaging.
        bobReceives()
        assertEquals(1, deliverCalls)
        // Recovery's deliver + dropDelivered pair (scheduler SIMULATED: called directly).
        h.hooks.beforeSessionUpsert = {}
        val held = db.meshCustodyDao().all().single()
        assertTrue(h.deliver(held.payload))
        bobEngine.dropDelivered(held.id)
        assertEquals(MeshDelivery.Outcome.DELIVERED, outcomes.last())
        assertTrue(db.meshCustodyDao().all().isEmpty())
        // Repeated encounters after delivery (the Mesh-1K-J storm): admitted again, DUPLICATE, dropped.
        repeat(3) { bobReceives() }
        assertEquals(4, deliverCalls)
        assertEquals(List(3) { MeshDelivery.Outcome.DUPLICATE }, outcomes.takeLast(3))
        assertTrue(db.meshCustodyDao().all().isEmpty())
        assertEquals(listOf(m1), h.rowIds())
        assertEquals(base + 1, h.durableRx())
        assertEquals("one failed (rolled back) + one committed decrypt, none for duplicates", 2, bobMls.decrypts[ct1])
        assertEquals(1, h.receiptCount())
        // Sender custody is never touched by the receiver (no ACK in P1).
        assertEquals(1, aliceDb.meshCustodyDao().all().size)
    }

    // 9 Restart followed by duplicate ------------------------------------------------------------
    @Test
    fun restartThenDuplicateIsDuplicateWithoutDecryptOrReceipt() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("restart dup")
        assertTrue(h.deliver(s1))
        val durableBefore = h.sessionRowNow()
        h.restart() // seen store + receipt queue are memory-only and now empty
        nowMs += 60_000
        assertTrue(h.deliver(s1))
        assertEquals(listOf(MeshDelivery.Outcome.DELIVERED, MeshDelivery.Outcome.DUPLICATE), outcomes)
        assertEquals(1, bobMls.decrypts[ct1])
        assertNull("pre-check answers before any session load", h.link())
        assertEquals(0, h.receiptCount())
        val durableAfter = h.sessionRowNow()
        assertArrayEquals(durableBefore.mlsSnapshot, durableAfter.mlsSnapshot)
        assertEquals(durableBefore.updatedAtEpochMs, durableAfter.updatedAtEpochMs)
        assertEquals(listOf(m1), h.rowIds())
        assertEquals(listOf(m1), h.notifications)
        // A new message after the restart still delivers normally.
        val (m2, s2, _) = h.seal("after restart")
        assertTrue(h.deliver(s2))
        assertEquals(MeshDelivery.Outcome.DELIVERED, outcomes.last())
        assertEquals(listOf(m1, m2), h.rowIds())
        assertEquals(h.durableRx(), h.rx())
    }

    // 10 Malformed message id ---------------------------------------------------------------------
    @Test
    fun malformedOuterMessageIdIsDeclinedAndNeverDelivered() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("malformed")
        val base = h.durableRx()
        val variants = listOf(
            ByteArray(0),
            "not json".toByteArray(),
            "{".toByteArray(),
            withOuterId(s1, null),
            withOuterId(s1, ""),
            withOuterId(s1, 12345),
            withOuterId(s1, "x'; DROP TABLE messages; --"),
            withOuterId(s1, m1 + "-other"),
        )
        variants.forEach { assertFalse(h.deliver(it)) }
        assertTrue(outcomes.none { it.inInbox })
        assertTrue(h.rowIds().isEmpty())
        assertEquals(base, h.durableRx())
        assertEquals(h.durableRx(), h.rx())
        assertEquals(0, h.receiptCount())
        assertTrue(h.notifications.isEmpty())
        // The genuine envelope is unaffected.
        assertTrue(h.deliver(s1))
        assertEquals(MeshDelivery.Outcome.DELIVERED, outcomes.last())
        assertEquals(listOf(m1), h.rowIds())
    }

    // 11 Wrong session / peer binding --------------------------------------------------------------
    @Test
    fun envelopeFromAnotherSessionIsNeverDeliveredNorDuplicate() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("bob's real message")
        assertTrue(h.deliver(s1))
        // Carol <-> Dave: a session Bob is not part of.
        val carol = TinkSigner()
        val dave = TinkSigner()
        val carolOrch = orch(FakeMls::transact)
        val daveOrch = orch(FakeMls::transact)
        carolOrch.provision(carol, dave.pin)
        daveOrch.provision(dave, carol.pin)
        carolOrch.importPeerRoute(daveOrch.localRouteQr(carol.pin)!!, carol)
        confirmSharedMls(carolOrch, daveOrch, carol.pin, dave.pin)
        val foreign = carolOrch.sendText(dave.pin, "for dave").envelopeCanonical!!.toByteArray(Charsets.UTF_8)
        val foreignCt = JSONObject(String(foreign, Charsets.UTF_8)).getString("encrypted_payload")
        val durableBefore = h.sessionRowNow()
        assertFalse(h.deliver(foreign))
        assertEquals(MeshDelivery.Outcome.DECLINED, outcomes.last())
        // Same foreign ciphertext relabelled with Bob's delivered message id: CONFLICT, no decrypt.
        val decryptsBefore = bobMls.decrypts[foreignCt]
        assertFalse(h.deliver(withOuterId(foreign, m1)))
        assertEquals(MeshDelivery.Outcome.CONFLICT, outcomes.last())
        assertEquals(decryptsBefore, bobMls.decrypts[foreignCt])
        assertEquals(listOf(m1), h.rowIds())
        assertEquals("bob's real message", db.messageDao().findById(m1)!!.localPlaintext)
        assertArrayEquals(durableBefore.mlsSnapshot, h.sessionRowNow().mlsSnapshot)
        assertEquals(h.durableRx(), h.rx())
        assertEquals(listOf(m1), h.notifications)
    }

    // 12 Existing message id with a conflicting envelope --------------------------------------------
    @Test
    fun existingIdWithConflictingEnvelopeIsRejectedNotOverwritten() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("original")
        val (m2, s2, ct2) = h.seal("second, relabelled")
        assertTrue(h.deliver(s1))
        val rowBefore = db.messageDao().findById(m1)!!
        val durableBefore = h.sessionRowNow()
        val receiptsBefore = h.receiptCount()
        // Genuine ciphertext of m2 under m1's outer id.
        assertFalse(h.deliver(withOuterId(s2, m1)))
        assertEquals(MeshDelivery.Outcome.CONFLICT, outcomes.last())
        assertNull("conflict must not trial-decrypt", bobMls.decrypts[ct2])
        assertEquals(rowBefore, db.messageDao().findById(m1))
        assertArrayEquals(durableBefore.mlsSnapshot, h.sessionRowNow().mlsSnapshot)
        assertEquals(receiptsBefore, h.receiptCount())
        // The relabel did not poison m2 itself.
        assertTrue(h.deliver(s2))
        assertEquals(MeshDelivery.Outcome.DELIVERED, outcomes.last())
        assertEquals(setOf(m1, m2), h.rowIds().toSet())
        assertEquals(2, h.rowIds().size)
        assertEquals(listOf(m1, m2), h.notifications)
    }

    @Test
    fun preExistingRowWithDifferentDigestOrOwnOutgoingRowIsConflict() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("stale row")
        val (m2, s2, ct2) = h.seal("own id")
        val base = h.durableRx()
        db.messageDao().insert(
            MeshInboxCommit.inboundRow(
                conversationId,
                InboundRecord(m1, "s", 0, "d", h.alice.pin, 1, "dev", InnerKind.TEXT, "earlier copy", true),
                nowMs - 1,
            )!!,
        )
        db.messageDao().insert(
            MeshInboxCommit.inboundRow(
                conversationId,
                InboundRecord(m2, "s", 0, "", h.alice.pin, 1, "dev", InnerKind.TEXT, "mine", true),
                nowMs - 1,
            )!!.copy(isOutgoing = true, receiptCiphertextSha256 = null),
        )
        assertFalse(h.deliver(s1))
        assertFalse(h.deliver(s2))
        assertEquals(listOf(MeshDelivery.Outcome.CONFLICT, MeshDelivery.Outcome.CONFLICT), outcomes)
        assertNull(bobMls.decrypts[ct1])
        assertNull(bobMls.decrypts[ct2])
        assertEquals("earlier copy", db.messageDao().findById(m1)!!.localPlaintext)
        assertTrue(db.messageDao().findById(m2)!!.isOutgoing)
        assertEquals(base, h.durableRx())
        assertTrue(h.notifications.isEmpty())
    }

    // Post-commit classification on the real Room transaction ---------------------------------------
    @Test
    fun commitResultClassifiesNewDuplicateAndConflictWithoutOverwriting() = runBlocking {
        Harness()
        val rec = InboundRecord("m-commit", "s", 0, "digest-a", "pin", 1, "dev", InnerKind.TEXT, "one", true)
        fun staged(r: InboundRecord) = MeshInboxCommit.Staged("pin", MeshInboxCommit.inboundRow(conversationId, r, nowMs)!!, "Alice") {}
        val commit = MeshInboxCommit(db)
        assertEquals(MeshDelivery.Outcome.DELIVERED, commit.commit(listOf(staged(rec)), null).outcome())
        val same = commit.commit(listOf(staged(rec.copy(text = "replay"))), null)
        assertEquals(MeshDelivery.Outcome.DUPLICATE, same.outcome())
        assertEquals(1, same.existing)
        assertEquals(0, same.conflicts)
        val other = commit.commit(listOf(staged(rec.copy(ciphertextSha256 = "digest-b", text = "forged"))), null)
        assertEquals(MeshDelivery.Outcome.CONFLICT, other.outcome())
        assertEquals(1, other.conflicts)
        assertEquals("one", db.messageDao().findById("m-commit")!!.localPlaintext)
        assertEquals(MeshDelivery.Outcome.DELIVERED, commit.commit(emptyList(), null).outcome())
    }

    // Pre-check is pure over the outer envelope ------------------------------------------------------
    @Test
    fun durableStateIgnoresUnusableEnvelopes() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("pure")
        val row = MeshInboxCommit.inboundRow(
            conversationId,
            InboundRecord(m1, "s", 0, MeshDelivery.outerCiphertextDigest(s1)!!, h.alice.pin, 1, "dev", InnerKind.TEXT, "t", true),
            nowMs,
        )!!
        val find: suspend (String) -> MessageEntity? = { if (it == m1) row else null }
        assertEquals(MeshDelivery.Durable.SAME, MeshDelivery.durableState(s1, find))
        assertEquals(MeshDelivery.Durable.NONE, MeshDelivery.durableState(ByteArray(0), find))
        assertEquals(MeshDelivery.Durable.NONE, MeshDelivery.durableState("[]".toByteArray(), find))
        assertEquals(MeshDelivery.Durable.NONE, MeshDelivery.durableState(withOuterId(s1, null), find))
        val noCt = JSONObject(String(s1, Charsets.UTF_8)).apply { remove("encrypted_payload") }.toString().toByteArray()
        assertEquals(MeshDelivery.Durable.NONE, MeshDelivery.durableState(noCt, find))
        val badCt = JSONObject(String(s1, Charsets.UTF_8)).apply { put("encrypted_payload", "") }.toString().toByteArray()
        assertEquals(MeshDelivery.Durable.NONE, MeshDelivery.durableState(badCt, find))
        assertEquals(MeshDelivery.Durable.NONE, MeshDelivery.durableState(s1) { null })
    }
}
