package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.mesh.BundleCodec
import com.codeyantra.armorchat.core.mesh.MeshAdmission
import com.codeyantra.armorchat.core.mesh.MeshRejection
import com.codeyantra.armorchat.core.mesh.CustodyBundle
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.MeshHoldResult
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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-1A / Mesh-1A.1 courier path on the JVM.
 *
 * REAL production code exercised: SessionOrchestrator (sendText, offerMesh, sync) with
 * ReceiptMessagingPeer/TwoPhonePeer on the in-memory relay, EmergencyMeshEngine (holdSealed,
 * offers, ingest, markForwarded, dropDelivered) on in-memory Room `mesh_custody`, CustodyLedger,
 * BundleCodec, MeshFrames, MeshCourier.ingest (the exact code EmergencyMeshService.ingest calls)
 * and MeshDelivery.deliver (the exact code ChatMessagingService.deliverMesh calls).
 *
 * SIMULATED / NOT COVERED (stated in each test where it matters):
 *  - ChatMessagingService.sendText's "relay not SENT -> holdSealed" branch: the test calls
 *    engine.holdSealed(record.envelopeCanonical) itself.
 *  - ChatMessagingService's persistInbound lambda (contact/conversation lookup + Room message
 *    insert): replaced by [Inbox], which records what offerMesh hands over.
 *  - BLE/Wi-Fi radio and MeshConversation phase exchange: bundles move as MeshFrames bytes.
 *  - MLS is FakeMls (the same fake the other messaging tests use), not the native engine.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class MeshCourierIntegrationTest {

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

    /** Stand-in for ChatMessagingService's persistInbound lambda (SIMULATED boundary). */
    private class Inbox {
        val calls = mutableListOf<Pair<String, InboundRecord>>()
        val persist: suspend (String, InboundRecord) -> Unit = { pin, record -> calls += pin to record }
        fun distinctMessages(): Set<String> = calls.map { it.second.messageId }.toSet()
    }

    private var nowMs = 1_800_000_000_000L
    private val origin = "https://relay-mesh.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-mesh", origin, RelayRole.BOOTSTRAP)))
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val factory = MessagingLinkFactory { signer, relay, clock, engine ->
        val peer = TwoPhonePeer(signer, relay, clock, FakeMls::transact, engine)
        LiveLink(peer, ReceiptMessagingPeer(peer, clock, jitter = { 0 }))
    }
    private val databases = mutableListOf<ArmorChatDatabase>()

    private fun orch() = SessionOrchestrator(
        registry = registry,
        provider = provider,
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = factory,
        nowSeconds = { nowMs / 1000 },
    )

    /** A phone's mesh side: real engine over its own in-memory chat database. */
    private inner class MeshNode {
        val db: ArmorChatDatabase = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            ArmorChatDatabase::class.java,
        ).allowMainThreadQueries().build().also { databases += it }
        val dao = db.meshCustodyDao()
        val engine = EmergencyMeshEngine(RuntimeEnvironment.getApplication(), dao, AppClock { nowMs })
        var deliverCalls = 0

        fun rows() = runBlocking { dao.all() }
        fun ids() = rows().map { it.id }.toSet()

        /** Everything this node would offer a peer, framed exactly as EmergencyMeshService does. */
        fun framedOffers(): ByteArray = runBlocking { MeshFrames.encodeBundles(engine.offers(emptyList())) }

        /** Same call EmergencyMeshService.ingest makes, with [orchestrator] behind deliverMesh. */
        fun receive(frames: ByteArray, orchestrator: SessionOrchestrator?, inbox: Inbox) = runBlocking {
            MeshCourier.ingest(
                bytes = frames,
                path = RadioPath.BLE,
                store = { encoded, via -> engine.ingest(encoded, via) },
                deliver = { payload ->
                    deliverCalls += 1
                    MeshDelivery.deliver(
                        payload,
                        { raw, persist -> orchestrator?.offerMesh(raw, persist) ?: false },
                        inbox.persist,
                    )
                },
                dropDelivered = { idHex -> engine.dropDelivered(idHex) },
            )
        }
    }

    private class Pair2(val aliceOrch: SessionOrchestrator, val bobOrch: SessionOrchestrator, val alice: TinkSigner, val bob: TinkSigner)

    private fun pair(): Pair2 = runBlocking {
        val aliceOrch = orch()
        val bobOrch = orch()
        val alice = TinkSigner()
        val bob = TinkSigner()
        aliceOrch.provision(alice, bob.pin)
        bobOrch.provision(bob, alice.pin)
        aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
        confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
        check(aliceOrch.sessionView(alice.pin, bob.pin).composerAllowed)
        check(bobOrch.sessionView(bob.pin, alice.pin).composerAllowed)
        Pair2(aliceOrch, bobOrch, alice, bob)
    }

    private fun Pair2.bobMls(): ByteArray = bobOrch.link(alice.pin)!!.peer.snapshot()
    private fun Pair2.bobTexts(): List<String> = bobOrch.link(alice.pin)!!.receipts.acceptedPlaintexts()

    /** Alice seals a text via the real orchestrator; returns the canonical relay envelope bytes. */
    private fun Pair2.sealText(text: String): Pair<String, ByteArray> = runBlocking {
        val record = aliceOrch.sendText(bob.pin, text)
        record.messageId to record.envelopeCanonical!!.toByteArray(Charsets.UTF_8)
    }

    private fun bundle(payload: ByteArray, createdAtMs: Long, hop: Int = 0): ByteArray = BundleCodec.encode(
        CustodyBundle(
            id = MeshIds.idFor(payload),
            createdAtMs = createdAtMs,
            expireAtMs = MeshPolicy.expireAt(createdAtMs),
            hopCount = hop,
            emergency = true,
            payload = payload,
        ),
    )

    @After
    fun close() {
        databases.forEach { it.close() }
    }

    // 1 ------------------------------------------------------------------------------------------
    @Test
    fun courierCarriesSealedTextAndRecipientReceivesItOnce() {
        val p = pair()
        val alice = MeshNode()
        val carrier = MeshNode()
        val bob = MeshNode()
        val inbox = Inbox()
        val (messageId, sealed) = p.sealText("mesh hello")
        // SIMULATED: ChatMessagingService would call holdSealed only when the relay did not report SENT.
        runBlocking { alice.engine.holdSealed(sealed, emergency = true) }
        val held = alice.rows().single()
        assertEquals(0, held.hopCount)
        assertEquals(MeshPolicy.expireAt(nowMs), held.expireAtMs)
        assertArrayEquals(sealed, held.payload)

        // Hop 1: carrier has no session with either side, so it only stores.
        val aliceFrames = alice.framedOffers()
        runBlocking { alice.engine.markForwarded(listOf(MeshIds.parseHex(held.id)), RadioPath.BLE) }
        carrier.receive(aliceFrames, orchestrator = null, inbox = Inbox())
        val carried = carrier.rows().single()
        assertEquals(held.id, carried.id)
        assertEquals(1, carried.hopCount)
        assertEquals(held.expireAtMs, carried.expireAtMs)

        // Hop 2: Bob receives through the real offerMesh path.
        nowMs += 60_000
        val bobMlsBefore = p.bobMls()
        bob.receive(carrier.framedOffers(), p.bobOrch, inbox)
        assertEquals(listOf("mesh hello"), p.bobTexts())
        assertEquals(1, inbox.calls.size)
        assertEquals(messageId, inbox.calls.single().second.messageId)
        assertEquals(p.alice.pin, inbox.calls.single().first)
        assertEquals("mesh hello", inbox.calls.single().second.text)
        // FakeMls decrypt does not change the snapshot bytes, so MLS movement is not asserted here;
        // the persisted messaging-store row is the evidence of acceptance.
        assertEquals("mesh hello", p.bobOrch.link(p.alice.pin)!!.receipts.inbound(messageId)!!.text)
        assertNotNull(bobMlsBefore)
        // Custody actually implemented: recipient drops only its own copy after delivery.
        assertTrue(bob.rows().isEmpty())
        // No ACK path exists: sender and carrier still hold the bundle until the 4 h deadline.
        assertEquals(setOf(held.id), alice.ids())
        assertEquals(1, alice.rows().single().forwardedCount)
        assertEquals(setOf(held.id), carrier.ids())
        assertEquals(2, bob.deliverCalls + carrier.deliverCalls)
    }

    // 2 ------------------------------------------------------------------------------------------
    @Test
    fun replayOverMeshAndRelayDoesNotDuplicateOrMutateMls() {
        val p = pair()
        val alice = MeshNode()
        val bob = MeshNode()
        val inbox = Inbox()
        val (messageId, sealed) = p.sealText("only once")
        runBlocking { alice.engine.holdSealed(sealed) }
        val frames = alice.framedOffers()
        bob.receive(frames, p.bobOrch, inbox)
        val mlsAfterFirst = p.bobMls()
        assertEquals(listOf("only once"), p.bobTexts())

        // Same frames again (mesh replay).
        bob.receive(frames, p.bobOrch, inbox)
        bob.receive(frames, p.bobOrch, inbox)
        assertArrayEquals(mlsAfterFirst, p.bobMls())
        assertEquals(listOf("only once"), p.bobTexts())
        assertEquals(setOf(messageId), inbox.distinctMessages())
        // Observed: offerMesh re-hands the already accepted record to persistInbound on replay
        // (ReceiptMessagingPeer.processLeased lines 279-283). ChatMessagingService.persistInbound
        // ignores it via messageDao.findById (line 1156) - that guard is NOT exercised here.
        assertEquals(3, inbox.calls.size)
        assertTrue(bob.rows().isEmpty())

        // The same envelope later arriving from the relay is also suppressed by the seen store.
        runBlocking { p.bobOrch.sync(p.alice.pin) }
        assertEquals(listOf("only once"), p.bobTexts())
    }

    // 3 ------------------------------------------------------------------------------------------
    @Test
    fun bundleForAnotherConversationLeavesBobUntouchedAndNextMessageStillDecrypts() {
        val p = pair()
        val other = pair() // unrelated Alice'/Bob' conversation on the same relay
        val bob = MeshNode()
        val sender = MeshNode()
        val inbox = Inbox()
        val (_, foreign) = other.sealText("not for bob")
        runBlocking { sender.engine.holdSealed(foreign) }
        val mlsBefore = p.bobMls()
        bob.receive(sender.framedOffers(), p.bobOrch, inbox)
        assertArrayEquals(mlsBefore, p.bobMls())
        assertTrue(p.bobTexts().isEmpty())
        assertTrue(inbox.calls.isEmpty())
        // Bob keeps it as a courier because nobody accepted it.
        assertEquals(1, bob.rows().size)

        val (_, real) = p.sealText("for bob")
        val aliceNode = MeshNode()
        runBlocking { aliceNode.engine.holdSealed(real) }
        bob.receive(aliceNode.framedOffers(), p.bobOrch, inbox)
        assertEquals(listOf("for bob"), p.bobTexts())
        assertEquals(1, inbox.calls.size)
        assertEquals(1, bob.rows().size) // only the foreign bundle remains
    }

    // 4 ------------------------------------------------------------------------------------------
    @Test
    fun dealEnvelopeOverMeshIsRefusedBeforeAnyDealOrInboxMutation() {
        val p = pair()
        var dealCalls = 0
        p.bobOrch.link(p.alice.pin)!!.receipts.dealInbound = { dealCalls++ }
        val records = runBlocking {
            // A structurally valid deal envelope (content irrelevant: refusal happens on InnerKind.DEAL).
            val aliceKey = Base64Url.decode(p.alice.pin)
            val bobKey = Base64Url.decode(p.bob.pin)
            val envelope = DealEnvelope(
                event = DealWireEvent.PROPOSAL,
                envelopeId = DealIds.generate(),
                dealId = DealIds.generate(),
                versionId = DealIds.generate(),
                senderId = ArmorChatId.derive(aliceKey),
                recipientId = ArmorChatId.derive(bobKey),
                senderKey = aliceKey,
                recipientKey = bobKey,
                conversationId = "conversation-mesh",
                createdAtEpochMs = nowMs,
                payload = ByteArray(96) { (it % 7).toByte() },
            )
            p.aliceOrch.link(p.bob.pin)!!.receipts.sendDealPayload(DealEnvelopes.encode(envelope))
        }
        assertTrue(records.isNotEmpty())
        val alice = MeshNode()
        val bob = MeshNode()
        val inbox = Inbox()
        records.forEach { runBlocking { alice.engine.holdSealed(it.envelopeCanonical!!.toByteArray(Charsets.UTF_8)) } }
        val mlsBefore = p.bobMls()
        bob.receive(alice.framedOffers(), p.bobOrch, inbox)
        assertArrayEquals(mlsBefore, p.bobMls())
        assertEquals(0, dealCalls)
        assertTrue(inbox.calls.isEmpty())
        records.forEach { assertEquals(null, p.bobOrch.link(p.alice.pin)!!.receipts.inbound(it.messageId)) }
        assertEquals(records.size, bob.rows().size) // refused => not dropped, kept as courier copy
        // Messaging still works afterwards.
        val (_, text) = p.sealText("after deal")
        val again = MeshNode()
        runBlocking { again.engine.holdSealed(text) }
        bob.receive(again.framedOffers(), p.bobOrch, inbox)
        assertEquals(listOf("after deal"), p.bobTexts())
    }

    // 5 ------------------------------------------------------------------------------------------
    @Test
    fun tamperedCiphertextAndDeadlineAreRejectedBeforeDecrypt() {
        val p = pair()
        val bob = MeshNode()
        val inbox = Inbox()
        val (_, sealed) = p.sealText("tamper")
        val good = bundle(sealed, nowMs)
        val payloadTamper = good.copyOf().also { it[it.size - 5] = (it[it.size - 5].toInt() xor 1).toByte() }
        val deadlineTamper = good.copyOf().also { it[36] = (it[36].toInt() xor 1).toByte() } // expireAt byte
        val idTamper = good.copyOf().also { it[10] = (it[10].toInt() xor 1).toByte() }
        val mlsBefore = p.bobMls()
        bob.receive(MeshFrames.encodeBundles(listOf(payloadTamper, deadlineTamper, idTamper)), p.bobOrch, inbox)
        assertEquals(0, bob.deliverCalls)
        assertTrue(bob.rows().isEmpty())
        assertArrayEquals(mlsBefore, p.bobMls())
        assertTrue(inbox.calls.isEmpty())
    }

    @Test
    fun hopCountBelowLimitIsUnauthenticatedMetadata_KNOWN_GAP() {
        // KNOWN GAP: hopCount is not covered by the bundle id, so a carrier can lower it.
        val p = pair()
        val bob = MeshNode()
        val inbox = Inbox()
        val (_, sealed) = p.sealText("hops rewritten")
        val rewritten = bundle(sealed, nowMs, hop = 5).also { it[6] = 0 }
        bob.receive(MeshFrames.encodeBundles(listOf(rewritten)), p.bobOrch, inbox)
        assertEquals(1, bob.deliverCalls)
        assertEquals(listOf("hops rewritten"), p.bobTexts())
    }

    @Test
    fun expiredBundleMustNotReachMessaging_DEFECT() {
        // DEFECT (regression test, expected to FAIL until fixed): MeshCourier.ingest ignores the
        // custody result (store(...) return value) and always calls deliver. Root cause:
        // core/mesh/MeshCourier.kt store(encoded, path) result discarded - carried over unchanged
        // from EmergencyMeshService.ingest (pre-extraction lines 202-205). BundleCodec.decode does
        // not check time, so an expired bundle is offered to MLS.
        val p = pair()
        val bob = MeshNode()
        val inbox = Inbox()
        val (_, sealed) = p.sealText("too late")
        val expired = bundle(sealed, nowMs - MeshPolicy.ABSOLUTE_TTL_MS - 1)
        val mlsBefore = p.bobMls()
        bob.receive(MeshFrames.encodeBundles(listOf(expired)), p.bobOrch, inbox)
        assertTrue(bob.rows().isEmpty())
        assertEquals("expired bundle reached messaging", 0, bob.deliverCalls)
        assertArrayEquals(mlsBefore, p.bobMls())
        assertTrue(inbox.calls.isEmpty())
    }

    @Test
    fun bundleAtHopLimitMustNotReachMessaging_DEFECT() {
        // DEFECT (expected to FAIL): hop == MAX_HOPS passes BundleCodec.decode (it rejects only
        // hop > MAX_HOPS, BundleCodec.kt:39) and CustodyLedger.accept rejects it ("hops",
        // CustodyLedger.kt:66), but MeshCourier.ingest ignores that result and still delivers.
        val p = pair()
        val bob = MeshNode()
        val inbox = Inbox()
        val (_, sealed) = p.sealText("too many hops")
        val atLimit = bundle(sealed, nowMs, hop = MeshPolicy.MAX_HOPS)
        bob.receive(MeshFrames.encodeBundles(listOf(atLimit)), p.bobOrch, inbox)
        assertTrue(bob.rows().isEmpty())
        assertEquals("hop-limit bundle reached messaging", 0, bob.deliverCalls)
        assertTrue(inbox.calls.isEmpty())
    }

    @Test
    fun bundleOverHopLimitIsRejectedByDecode() {
        val p = pair()
        val bob = MeshNode()
        val (_, sealed) = p.sealText("way too many hops")
        val over = bundle(sealed, nowMs, hop = MeshPolicy.MAX_HOPS + 1)
        bob.receive(MeshFrames.encodeBundles(listOf(over)), p.bobOrch, Inbox())
        assertEquals(0, bob.deliverCalls)
        assertTrue(bob.rows().isEmpty())
        assertTrue(p.bobTexts().isEmpty())
    }

    /**
     * Four encounter-sized batches fill the foreign pool: 8, then 8, a rate-window advance, then 8 and 8.
     * One more window advance makes the next refusal FULL rather than RATE.
     */
    private fun MeshNode.fillForeignPool(tag: String) {
        var n = 0
        val batches = MeshPolicy.FOREIGN_MAX / MeshPolicy.FOREIGN_PER_ENCOUNTER
        val batchesPerWindow = MeshPolicy.FOREIGN_RATE_MAX / MeshPolicy.FOREIGN_PER_ENCOUNTER
        repeat(batches) { batch ->
            if (batch > 0 && batch % batchesPerWindow == 0) {
                nowMs += MeshPolicy.FOREIGN_RATE_WINDOW_MS + 1_000L
            }
            val frames = MeshFrames.encodeBundles(
                List(MeshPolicy.FOREIGN_PER_ENCOUNTER) {
                    bundle("junk-$tag-${n++}-".toByteArray() + ByteArray(16) { (n and 0xff).toByte() }, nowMs)
                },
            )
            receive(frames, null, Inbox())
        }
        assertEquals(MeshPolicy.FOREIGN_MAX, rows().count { it.hopCount >= 1 })
        nowMs += MeshPolicy.FOREIGN_RATE_WINDOW_MS + 1_000L
    }

    // 6 ------------------------------------------------------------------------------------------
    @Test
    fun rateWindowThenFullForeignPoolRefusesWithoutEvictingOrBlockingOwnHold() {
        // Mesh-1K-AS: two encounters store 8+8. The same window refuses the next id as RATE and keeps
        // the junk. After the window advances the legitimate bundle is carried. The foreign pool then
        // refuses FULL without eviction. The sender's own pool still accepts that bundle.
        val p = pair()
        val carrier = MeshNode()
        val junk = (0 until 48).map { i ->
            bundle("junk-$i-".toByteArray() + ByteArray(32) { i.toByte() }, nowMs)
        }
        carrier.receive(MeshFrames.encodeBundles(junk.take(24)), null, Inbox())
        assertEquals(MeshPolicy.FOREIGN_PER_ENCOUNTER, carrier.rows().size)
        carrier.receive(MeshFrames.encodeBundles(junk.drop(24)), null, Inbox())
        assertEquals(MeshPolicy.FOREIGN_RATE_MAX, carrier.rows().size)
        val junkIds = carrier.ids()
        val junkBytes = carrier.rows().associate { it.id to it.payload.toList() }

        val (_, sealed) = p.sealText("legit under flood")
        val legit = bundle(sealed, nowMs)
        assertEquals(
            MeshAdmission.Rejected(MeshRejection.RATE),
            runBlocking { carrier.engine.ingest(legit, RadioPath.BLE) },
        )
        carrier.receive(MeshFrames.encodeBundles(listOf(legit)), null, Inbox())
        assertEquals(junkIds, carrier.ids())
        assertFalse(carrier.ids().contains(MeshIds.hex(MeshIds.idFor(sealed))))
        assertEquals(junkBytes, carrier.rows().associate { it.id to it.payload.toList() })

        nowMs += MeshPolicy.FOREIGN_RATE_WINDOW_MS + 1_000L
        val legitAfter = bundle(sealed, nowMs)
        carrier.receive(MeshFrames.encodeBundles(listOf(legitAfter)), null, Inbox())
        val legitId = MeshIds.hex(MeshIds.idFor(sealed))
        assertTrue(carrier.ids().contains(legitId))
        assertEquals(junkBytes, carrier.rows().filter { it.id in junkIds }.associate { it.id to it.payload.toList() })
        assertEquals(junkIds.size + 1, carrier.rows().size)

        val sender = MeshNode()
        sender.fillForeignPool("sender")
        val senderForeign = sender.rows().filter { it.hopCount >= 1 }.map { it.id }.toSet()
        val hold = runBlocking { sender.engine.holdSealed(sealed) }
        assertTrue("own pool stays reserved when the foreign pool is full: $hold", hold is MeshHoldResult.Held)
        assertEquals(senderForeign, sender.rows().filter { it.hopCount >= 1 }.map { it.id }.toSet())
        assertEquals(1, sender.rows().count { it.hopCount == 0 })

        val bob = MeshNode()
        val inbox = Inbox()
        bob.fillForeignPool("bob")
        val bobIds = bob.ids()
        assertTrue(inbox.calls.isEmpty())
        val callsBefore = bob.deliverCalls
        val mlsBefore = p.bobMls()
        val blocked = bundle(sealed, nowMs)
        val admission = runBlocking { bob.engine.ingest(blocked, RadioPath.BLE) }
        assertEquals(MeshAdmission.Rejected(MeshRejection.FULL), admission)
        bob.receive(MeshFrames.encodeBundles(listOf(blocked)), p.bobOrch, inbox)
        assertEquals(callsBefore, bob.deliverCalls)
        assertTrue(p.bobTexts().isEmpty())
        assertTrue(inbox.calls.isEmpty())
        assertArrayEquals(mlsBefore, p.bobMls())
        assertEquals(bobIds, bob.ids())
    }

    // 7 ------------------------------------------------------------------------------------------
    @Test
    fun corruptBundleInBatchDoesNotBlockValidOnes() {
        val p = pair()
        val bob = MeshNode()
        val inbox = Inbox()
        val (id1, s1) = p.sealText("first")
        val (id2, s2) = p.sealText("second")
        val corrupt = bundle("garbage".toByteArray(), nowMs).also { it[0] = 'Z'.code.toByte() }
        val notJson = bundle("not an envelope".toByteArray(), nowMs)
        val frames = MeshFrames.encodeBundles(listOf(bundle(s1, nowMs), corrupt, notJson, bundle(s2, nowMs)))
        bob.receive(frames, p.bobOrch, inbox)
        assertEquals(listOf("first", "second"), p.bobTexts())
        assertEquals(setOf(id1, id2), inbox.distinctMessages())
        assertEquals(3, bob.deliverCalls) // corrupt skipped by decode; notJson offered and refused
        assertEquals(setOf(MeshIds.hex(MeshIds.idFor("not an envelope".toByteArray()))), bob.ids())
        assertNotNull(p.bobMls())
    }

    // Mesh-1B boundaries -------------------------------------------------------------------------
    private fun Pair2.admitThenReceive(text: String, created: Long, hop: Int = 0): Triple<MeshAdmission, MeshNode, Inbox> {
        val (_, sealed) = sealText(text)
        val encoded = bundle(sealed, created, hop)
        val probe = MeshNode()
        val admission = runBlocking { probe.engine.ingest(encoded, RadioPath.BLE) }
        val bob = MeshNode()
        val inbox = Inbox()
        bob.receive(MeshFrames.encodeBundles(listOf(encoded)), bobOrch, inbox)
        return Triple(admission, bob, inbox)
    }

    @Test
    fun futureSkewBoundary() {
        val p = pair()
        val (atLimit, bobA, inboxA) = p.admitThenReceive("skew ok", nowMs + MeshPolicy.CLOCK_SKEW_MS)
        assertTrue(atLimit is MeshAdmission.Admitted)
        assertEquals(1, bobA.deliverCalls)
        assertEquals(listOf("skew ok"), p.bobTexts())
        assertEquals(1, inboxA.calls.size)
        val mlsBefore = p.bobMls()
        val (over, bobB, inboxB) = p.admitThenReceive("skew bad", nowMs + MeshPolicy.CLOCK_SKEW_MS + 1)
        assertEquals(MeshAdmission.Rejected(MeshRejection.FUTURE), over)
        assertEquals(0, bobB.deliverCalls)
        assertTrue(bobB.rows().isEmpty())
        assertTrue(inboxB.calls.isEmpty())
        assertArrayEquals(mlsBefore, p.bobMls())
        assertEquals(listOf("skew ok"), p.bobTexts())
    }

    @Test
    fun exactExpiryBoundary() {
        val p = pair()
        val (live, bobA, _) = p.admitThenReceive("last ms", nowMs - MeshPolicy.ABSOLUTE_TTL_MS + 1)
        assertTrue(live is MeshAdmission.Admitted)
        assertEquals(1, bobA.deliverCalls)
        assertEquals(listOf("last ms"), p.bobTexts())
        val mlsBefore = p.bobMls()
        val (dead, bobB, inboxB) = p.admitThenReceive("deadline", nowMs - MeshPolicy.ABSOLUTE_TTL_MS)
        assertEquals(MeshAdmission.Rejected(MeshRejection.EXPIRED), dead)
        assertEquals(0, bobB.deliverCalls)
        assertTrue(inboxB.calls.isEmpty())
        assertArrayEquals(mlsBefore, p.bobMls())
    }

    @Test
    fun exactHopBoundaries() {
        val p = pair()
        val (below, bobA, _) = p.admitThenReceive("hop five", nowMs, hop = MeshPolicy.MAX_HOPS - 1)
        assertTrue(below is MeshAdmission.Admitted)
        assertEquals(1, bobA.deliverCalls)
        assertEquals(listOf("hop five"), p.bobTexts())
        val mlsBefore = p.bobMls()
        val (atLimit, bobB, inboxB) = p.admitThenReceive("hop six", nowMs, hop = MeshPolicy.MAX_HOPS)
        assertEquals(MeshAdmission.Rejected(MeshRejection.HOPS), atLimit)
        assertEquals(0, bobB.deliverCalls)
        assertTrue(inboxB.calls.isEmpty())
        val (over, bobC, inboxC) = p.admitThenReceive("hop seven", nowMs, hop = MeshPolicy.MAX_HOPS + 1)
        assertEquals(MeshAdmission.Rejected(MeshRejection.MALFORMED), over) // codec rejects > MAX_HOPS
        assertEquals(0, bobC.deliverCalls)
        assertTrue(inboxC.calls.isEmpty())
        assertArrayEquals(mlsBefore, p.bobMls())
        assertEquals(listOf("hop five"), p.bobTexts())
    }

    @Test
    fun heldDuplicateIsNotDeliveredFailClosed_LIMITATION() {
        // LIMITATION (Mesh-1B, fail closed): the ledger cannot tell a pending-for-me bundle from one
        // carried for others. A bundle stored while this phone could not deliver it (here: no session
        // wired yet) is NOT retried when it arrives again; it stays in custody until its deadline.
        val p = pair()
        val alice = MeshNode()
        val bob = MeshNode()
        val inbox = Inbox()
        val (_, sealed) = p.sealText("pending")
        runBlocking { alice.engine.holdSealed(sealed) }
        val frames = alice.framedOffers()
        bob.receive(frames, orchestrator = null, inbox = inbox)
        assertEquals(1, bob.rows().size)
        val mlsBefore = p.bobMls()
        val callsBefore = bob.deliverCalls
        val again = runBlocking { bob.engine.ingest(BundleCodec.encode(BundleCodec.decode(MeshFrames.decodeBundles(frames).single()).let { (it as com.codeyantra.armorchat.core.mesh.BundleDecode.Ok).bundle }), RadioPath.BLE) }
        assertTrue(again is MeshAdmission.Duplicate)
        bob.receive(frames, p.bobOrch, inbox)
        assertEquals(callsBefore, bob.deliverCalls)
        assertTrue(p.bobTexts().isEmpty())
        assertTrue(inbox.calls.isEmpty())
        assertArrayEquals(mlsBefore, p.bobMls())
        assertEquals(1, bob.rows().size)
    }
}
