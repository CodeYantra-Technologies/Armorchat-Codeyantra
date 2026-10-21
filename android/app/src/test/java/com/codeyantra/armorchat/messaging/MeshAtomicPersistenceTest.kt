package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.json.CanonicalJson
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
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
 * Mesh-1H-1: inbox row + contact_sessions MLS snapshot atomicity on the Mesh inbound path.
 *
 * REAL: Room (in-memory ArmorChatDatabase under Robolectric: real SQLite transactions, FK checks),
 * MessageDao/ConversationDao/ContactSessionDao, SessionOrchestrator.offerMesh/persist/restore,
 * ReceiptMessagingPeer, MeshDelivery.deliverDurable.
 * SIMULATED: ChatMessagingService is not constructible on the JVM; [Harness.wiring] mirrors the
 * deliverMesh lambdas. Keystore encryption (RelayCredentialsKek) is replaced by an identity
 * function. MLS is FakeMls + rx counter: NOT proof of native mls-rs behaviour. "Restart" is a fresh
 * orchestrator over the same open Room database: NOT proof of real process-death / fsync behaviour.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshAtomicPersistenceTest {

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
    private val origin = "https://relay-mesh-1h1.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-1h1", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val bobMls = RxMls()
    private val lock = Mutex()
    private val db: ArmorChatDatabase =
        Room.inMemoryDatabaseBuilder(RuntimeEnvironment.getApplication(), ArmorChatDatabase::class.java).build()

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

    /** Test seams; production passes none. */
    private class Hooks {
        var failSessionBuild = false
        var stageGate: suspend () -> Unit = {}
        var beforeInsert: suspend (MessageEntity) -> Unit = {}
        var beforeSessionUpsert: suspend (ContactSessionEntity) -> Unit = {}
        var afterCommitEffect: suspend () -> Unit = {}
    }

    private inner class Harness {
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceOrch = orch(FakeMls::transact)
        var bobOrch = orch(bobMls::transact)
        val hooks = Hooks()
        val events = mutableListOf<String>()
        val notifications = mutableListOf<String>()

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

        /** ChatMessagingService.persist(contact)'s row mapping; Keystore encrypt = identity (SIMULATED). */
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

        private val savedMailbox = bobOrch.link(alice.pin)?.peer?.mailbox

        /** loadIfNeeded -> restoreRow from the REAL Room row. */
        suspend fun load() {
            if (bobOrch.link(alice.pin) != null) return
            val row = db.contactSessionDao().find(contactId) ?: return
            bobOrch.restore(bob, rowToLink(row), bob.pin)
        }

        fun restart() {
            bobOrch = orch(bobMls::transact)
        }

        fun rx(): Long = rxOf(bobOrch.link(alice.pin)?.peer?.snapshot())
        suspend fun durableRx(): Long = rxOf(db.contactSessionDao().find(contactId)?.mlsSnapshot)
        suspend fun rows(): List<String> = db.messageDao().observeAllMessages().first().filter { !it.isOutgoing }.map { it.id }

        fun seal(text: String): Triple<String, ByteArray, String> = runBlocking {
            val r = aliceOrch.sendText(bob.pin, text)
            val canonical = r.envelopeCanonical!!
            val ct = org.json.JSONObject(canonical).getString("encrypted_payload")
            Triple(r.messageId, canonical.toByteArray(Charsets.UTF_8), ct)
        }

        /** Mirrors ChatMessagingService.deliverMesh (see WIRING below). */
        suspend fun deliver(payload: ByteArray): Boolean = wiring(this, payload)
    }

    // WIRING ---------------------------------------------------------------------------------------
    private suspend fun wiring(h: Harness, payload: ByteArray): Boolean {
        // Mirrors ChatMessagingService.deliverMesh after Mesh-1H-1 (production MeshInboxCommit).
        val staged = mutableListOf<MeshInboxCommit.Staged>()
        var committed: List<MeshInboxCommit.Staged> = emptyList()
        val ok = MeshDelivery.deliverDurable(
            lock = lock,
            payload = payload,
            load = { h.load() },
            checkpoint = { staged.clear(); h.bobOrch.meshCheckpoint() },
            rollback = { staged.clear(); h.bobOrch.restoreMeshCheckpoint(it) },
            offer = { raw, cb -> h.bobOrch.offerMesh(raw, cb) },
            persistInbound = { pin, r ->
                h.hooks.stageGate()
                MeshInboxCommit.inboundRow(conversationId, r, nowMs)?.let { e ->
                    staged += MeshInboxCommit.Staged(pin, e, "Alice") {
                        h.notifications += e.id
                        h.events += "effect:${e.id}"
                        h.hooks.afterCommitEffect()
                    }
                }
            },
            commit = { pin ->
                if (h.hooks.failSessionBuild) throw IOException("simulated pre-tx failure")
                val session = h.sessionRow(h.bobOrch.persist(pin)!!)
                val result = MeshInboxCommit(db, h.hooks.beforeInsert, h.hooks.beforeSessionUpsert)
                    .commit(staged.filter { it.peerPin == pin }, session)
                h.events += "commit"
                committed = result.inserted
            },
        )
        if (ok) MeshInboxCommit.runAfterCommit(committed)
        return ok
    }

    @After
    fun close() {
        db.close()
    }

    private fun assertConsistent(h: Harness) = runBlocking {
        assertEquals("in-memory MLS must equal durable MLS", h.durableRx(), h.rx())
    }

    // 1 ------------------------------------------------------------------------------------------
    @Test
    fun failureBeforeTransactionCommitsNothing() = runBlocking {
        val h = Harness()
        val (m1, sealed, _) = h.seal("pre-tx failure")
        val base = h.durableRx()
        h.hooks.failSessionBuild = true
        assertFalse(h.deliver(sealed))
        assertTrue(h.rows().isEmpty())
        assertEquals(base, h.durableRx())
        assertConsistent(h)
        assertTrue(h.notifications.isEmpty())
        assertNull(h.bobOrch.link(h.alice.pin)!!.receipts.inbound(m1))
    }

    // 2 ------------------------------------------------------------------------------------------
    @Test
    fun failureDuringInboxInsertRollsBackBoth() = runBlocking {
        val h = Harness()
        val (_, sealed, _) = h.seal("insert failure")
        val base = h.durableRx()
        h.hooks.beforeInsert = { throw IOException("simulated insert failure") }
        assertFalse(h.deliver(sealed))
        assertTrue(h.rows().isEmpty())
        assertEquals(base, h.durableRx())
        assertConsistent(h)
        assertTrue(h.notifications.isEmpty())
    }

    // 3 ------------------------------------------------------------------------------------------
    @Test
    fun failureDuringMlsUpsertRollsBackInboxRow() = runBlocking {
        val h = Harness()
        val (_, sealed, _) = h.seal("upsert failure")
        val base = h.durableRx()
        h.hooks.beforeSessionUpsert = { throw IOException("simulated contact_sessions failure") }
        assertFalse(h.deliver(sealed))
        assertTrue("inbox row committed while MLS snapshot was not (partial commit)", h.rows().isEmpty())
        assertEquals(base, h.durableRx())
        assertConsistent(h)
        assertTrue("notification for an uncommitted delivery", h.notifications.isEmpty())
    }

    // 4 ------------------------------------------------------------------------------------------
    @Test
    fun successCommitsRowAndMlsTogetherThenNotifies() = runBlocking {
        val h = Harness()
        val (m1, sealed, _) = h.seal("atomic ok")
        val base = h.durableRx()
        assertTrue(h.deliver(sealed))
        assertEquals(listOf(m1), h.rows())
        assertEquals(base + 1, h.durableRx())
        assertConsistent(h)
        assertEquals(listOf(m1), h.notifications)
        assertEquals("touch", "Alice", db.conversationDao().findById(conversationId)?.title)
    }

    // 5 ------------------------------------------------------------------------------------------
    @Test
    fun restartOnSameRoomDatabaseContinuesFromCommittedSnapshot() = runBlocking {
        val h = Harness()
        val (m1, s1, _) = h.seal("before restart")
        val (m2, s2, _) = h.seal("after restart")
        val base = h.durableRx()
        assertTrue(h.deliver(s1))
        h.restart()
        assertTrue(h.deliver(s2))
        assertEquals(listOf(m1, m2), h.rows())
        assertEquals(base + 2, h.durableRx())
        assertConsistent(h)
    }

    // 6 ------------------------------------------------------------------------------------------
    @Test
    fun duplicateAfterRestartCreatesNoSecondRowOrNotificationAndNeverRegressesMls() = runBlocking {
        val h = Harness()
        val (m1, s1, ct1) = h.seal("dup after restart")
        assertTrue(h.deliver(s1))
        val afterFirst = h.durableRx()
        h.restart()
        h.deliver(s1) // FakeMls outcome for a replayed ciphertext is not native evidence
        assertEquals(listOf(m1), h.rows())
        assertEquals(listOf(m1), h.notifications)
        assertTrue("durable MLS regressed", h.durableRx() >= afterFirst)
        assertConsistent(h)
        assertNotNull(bobMls.decrypts[ct1])
    }

    // 7 ------------------------------------------------------------------------------------------
    @Test
    fun cancellationBeforeTransactionCommitsNothing() = runBlocking {
        val h = Harness()
        val (_, sealed, _) = h.seal("cancel pre-tx")
        val base = h.durableRx()
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        h.hooks.stageGate = { reached.complete(Unit); gate.await() }
        val outcome = CompletableDeferred<String>()
        val job = launch {
            try { outcome.complete("returned:" + h.deliver(sealed)) } catch (c: CancellationException) {
                outcome.complete("cancelled"); throw c
            }
        }
        withTimeout(5_000) { reached.await() }
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals("cancelled", outcome.await())
        assertFalse(lock.isLocked)
        assertTrue(h.rows().isEmpty())
        assertEquals(base, h.durableRx())
        assertConsistent(h)
        assertTrue(h.notifications.isEmpty())
    }

    // 8 ------------------------------------------------------------------------------------------
    @Test
    fun cancellationDuringTransactionStillCommitsBothAtomically() = runBlocking {
        val h = Harness()
        val (m1, sealed, _) = h.seal("cancel in tx")
        val base = h.durableRx()
        val reached = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        h.hooks.beforeSessionUpsert = { reached.complete(Unit); gate.await() }
        val job = launch(Dispatchers.Default) { runCatching { h.deliver(sealed) } }
        withTimeout(5_000) { reached.await() }
        job.cancel()
        gate.complete(Unit)
        withTimeout(5_000) { job.join() }
        assertFalse(lock.isLocked)
        assertEquals(listOf(m1), h.rows())
        assertEquals(base + 1, h.durableRx())
        assertConsistent(h)
    }

    // 9 ------------------------------------------------------------------------------------------
    @Test
    fun cancellationAfterCommitKeepsCommittedStateConsistent() = runBlocking {
        val h = Harness()
        val (m1, sealed, _) = h.seal("cancel after commit")
        val base = h.durableRx()
        val reached = CompletableDeferred<Unit>()
        h.hooks.afterCommitEffect = { reached.complete(Unit); CompletableDeferred<Unit>().await() }
        val job = launch { runCatching { h.deliver(sealed) } }
        withTimeout(5_000) { reached.await() }
        assertFalse("post-commit side effects must run outside the messaging mutex", lock.isLocked)
        job.cancel()
        withTimeout(5_000) { job.join() }
        assertEquals(listOf(m1), h.rows())
        assertEquals(base + 1, h.durableRx())
        assertConsistent(h)
    }

    // 10 -----------------------------------------------------------------------------------------
    @Test
    fun concurrentRelayHolderAndMeshDeliverySerializeWithoutLostUpdate() = runBlocking {
        val h = Harness()
        val (m1, sealed, _) = h.seal("mesh vs relay")
        val base = h.durableRx()
        val relayHolding = CompletableDeferred<Unit>()
        val relayRelease = CompletableDeferred<Unit>()
        val relay = launch(Dispatchers.Default) {
            lock.withLock {
                relayHolding.complete(Unit)
                relayRelease.await()
                // relay syncContact's persist(contact) of the (unchanged) session under the same lock
                db.contactSessionDao().upsert(h.sessionRow(h.bobOrch.persist(h.alice.pin)!!))
            }
        }
        withTimeout(5_000) { relayHolding.await() }
        val mesh = launch(Dispatchers.Default) { assertTrue(h.deliver(sealed)) }
        assertTrue(h.rows().isEmpty())
        relayRelease.complete(Unit)
        withTimeout(10_000) { relay.join(); mesh.join() }
        assertEquals(listOf(m1), h.rows())
        assertEquals(base + 1, h.durableRx())
        assertConsistent(h)
        assertFalse(lock.isLocked)
    }

    // 11 -----------------------------------------------------------------------------------------
    @Test
    fun noNotificationOrAttachmentEffectBeforeCommit() = runBlocking {
        val h = Harness()
        val (m1, sealed, _) = h.seal("ordering")
        var notificationsAtUpsert = -1
        h.hooks.beforeSessionUpsert = { notificationsAtUpsert = h.notifications.size }
        assertTrue(h.deliver(sealed))
        assertEquals("side effect ran before the transaction committed", 0, notificationsAtUpsert)
        assertEquals(listOf(m1), h.notifications)
        assertEquals(listOf("commit", "effect:$m1"), h.events)
    }

    // 12 -----------------------------------------------------------------------------------------
    @Test
    fun meshStillRefusesDealBeforeAnyInboxOrMlsMutation() = runBlocking {
        val h = Harness()
        var dealCalls = 0
        h.bobOrch.link(h.alice.pin)!!.receipts.dealInbound = { dealCalls++ }
        val aliceKey = Base64Url.decode(h.alice.pin)
        val bobKey = Base64Url.decode(h.bob.pin)
        val records = h.aliceOrch.link(h.bob.pin)!!.receipts.sendDealPayload(
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
        val base = h.durableRx()
        val sessionBefore = db.contactSessionDao().find(contactId)!!.mlsSnapshot!!.toList()
        records.forEach { assertFalse(h.deliver(it.envelopeCanonical!!.toByteArray(Charsets.UTF_8))) }
        assertEquals(0, dealCalls)
        assertTrue(h.rows().isEmpty())
        assertTrue(h.notifications.isEmpty())
        assertEquals(base, h.durableRx())
        assertEquals(sessionBefore, db.contactSessionDao().find(contactId)!!.mlsSnapshot!!.toList())
        assertConsistent(h)
    }
    // 13 -----------------------------------------------------------------------------------------
    @Test
    fun preExistingRowWithStaleMlsKeepsRowAndPersistsAdvancedMlsWithoutSecondNotification() = runBlocking {
        val h = Harness()
        val (m1, sealed, _) = h.seal("window-2 leftover")
        val base = h.durableRx()
        // Pre-1H-1 window-2 state: row committed earlier, MLS durable still at its pre-message state.
        db.messageDao().insert(
            MeshInboxCommit.inboundRow(
                conversationId,
                InboundRecord(m1, "s", 0, "d", h.alice.pin, 1, "dev", InnerKind.TEXT, "earlier copy", true),
                nowMs - 1,
            )!!,
        )
        assertTrue(h.deliver(sealed))
        assertEquals(listOf(m1), h.rows())
        assertEquals("earlier copy", db.messageDao().findById(m1)!!.localPlaintext)
        assertTrue("existing row must not notify again", h.notifications.isEmpty())
        assertEquals("advanced MLS that consumed the ciphertext must be durable", base + 1, h.durableRx())
        assertConsistent(h)
    }
}
