package com.codeyantra.armorchat.messaging

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.mesh.EmergencyMeshEngine
import com.codeyantra.armorchat.core.mesh.MeshCourier
import com.codeyantra.armorchat.core.mesh.MeshFrames
import com.codeyantra.armorchat.core.mesh.RadioPath
import com.codeyantra.armorchat.core.network.CodeYantraRelayTransport
import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.ImmutableRelayEnvelope
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.RelayClient
import com.codeyantra.armorchat.core.network.RelayFetchResult
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
import java.io.EOFException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.Dns
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Mesh-1K-B: relay transport failure (IOException) during text send.
 *
 * REAL production code exercised: SessionOrchestrator.sendText / sync / offerMesh,
 * ReceiptMessagingPeer (sendText, submitOutbound, submit, flushOutbox, resubmitExact,
 * processLeased seen-store dedupe), OutboxPolicy, MessageProgress, CodeYantraRelayTransport +
 * real OkHttp for the offline DNS failure, EmergencyMeshEngine.holdSealed on in-memory Room
 * `mesh_custody`, MeshCourier.ingest, MeshDelivery.deliver/alreadyStored, MeshInboxCommit.inboundRow,
 * Room `messages` primary key + MessageDao.findById.
 *
 * SIMULATED / NOT COVERED:
 *  - ChatMessagingService.sendText is not constructed (Hilt/Keystore/notification deps). [serviceSend]
 *    replays its lines 400-419 exactly: holdSealed when armed && status != SENT, then insert the
 *    messages row with record.messageId/status. The orchestrator call it wraps is real.
 *  - ChatMessagingService.persistInbound is replaced by the same findById-then-insert over
 *    MeshInboxCommit.inboundRow that MeshCustodyRecoveryIntegrationTest uses.
 *  - The relay is InMemoryRelayNetwork (replays an existing message id like relay/src/store.mjs).
 *    "Accepted then timeout" is a wrapper that forwards to the fake relay and then throws.
 *  - MLS is FakeMls. FakeMls decrypts a replayed ciphertext again; native mls-rs replay rejection
 *    after the in-memory seen store is lost (process restart) is NOT proven here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class OfflineRelayMeshFallbackTest {

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

    private enum class Net { ONLINE, OFFLINE, ACCEPT_THEN_TIMEOUT, CANCEL, CANCELLED_IO }

    private var nowMs = 1_800_000_000_000L
    private val origin = "https://relay-mesh.example"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(listOf(RelayNode("node-mesh", origin, RelayRole.BOOTSTRAP)))
    private val databases = mutableListOf<ArmorChatDatabase>()

    /** Alice's relay connectivity. */
    private var aliceNet = Net.ONLINE

    /** Every Alice envelope that reached the fake relay: message id, canonical bytes, replay flag. */
    private data class Reached(val messageId: String, val canonical: String, val replay: Boolean)
    private val reached = mutableListOf<Reached>()

    /** Real CodeYantraRelayTransport over real OkHttp whose DNS has no network (airplane mode). */
    private val offlineHttp = CodeYantraRelayTransport(
        origin = CodeYantraRelayTransport.parseOrigin(origin),
        client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    throw UnknownHostException("no network")
            })
            .retryOnConnectionFailure(false)
            .build(),
    )

    private fun aliceClient(base: RelayClient): RelayClient = object : RelayClient by base {
        override suspend fun submitEnvelope(token: String, envelope: ImmutableRelayEnvelope): RelaySubmitResult =
            when (aliceNet) {
                Net.ONLINE -> base.submitEnvelope(token, envelope).also { note(envelope, it) }
                Net.OFFLINE -> offlineHttp.submitEnvelope(token, envelope)
                Net.ACCEPT_THEN_TIMEOUT -> {
                    note(envelope, base.submitEnvelope(token, envelope))
                    throw SocketTimeoutException("timeout")
                }
                Net.CANCEL -> throw CancellationException("caller cancelled")
                Net.CANCELLED_IO -> {
                    currentCoroutineContext().cancel()
                    throw InterruptedIOException("interrupted")
                }
            }

        override suspend fun fetchInbox(token: String, cursor: String?, limit: Int): RelayFetchResult =
            if (aliceNet == Net.ONLINE) base.fetchInbox(token, cursor, limit) else offlineHttp.fetchInbox(token, cursor, limit)
    }

    private fun note(envelope: ImmutableRelayEnvelope, result: RelaySubmitResult) {
        val replay = (result as? RelaySubmitResult.Accepted)?.replay ?: return
        reached += Reached(envelope.messageId, envelope.canonicalJson(), replay)
    }

    private val factory = MessagingLinkFactory { signer, relay, clock, engine ->
        val peer = TwoPhonePeer(signer, relay, clock, FakeMls::transact, engine)
        LiveLink(peer, ReceiptMessagingPeer(peer, clock, jitter = { 0 }))
    }

    private fun orch(provider: DefaultRelayProvider) = SessionOrchestrator(
        registry = registry,
        provider = provider,
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = factory,
        nowSeconds = { nowMs / 1000 },
    )

    /** One phone's durable side: chat `messages`, `conversations` and `mesh_custody` in Room. */
    private inner class Phone(val conversationId: String) {
        val db: ArmorChatDatabase = Room.inMemoryDatabaseBuilder(
            RuntimeEnvironment.getApplication(),
            ArmorChatDatabase::class.java,
        ).allowMainThreadQueries().build().also { databases += it }
        val engine = EmergencyMeshEngine(RuntimeEnvironment.getApplication(), db.meshCustodyDao(), AppClock { nowMs })
        var persistCalls = 0

        init {
            runBlocking { db.conversationDao().insert(ConversationEntity(conversationId, "contact-$conversationId", "Peer", nowMs)) }
        }

        fun custody() = runBlocking { db.meshCustodyDao().all() }
        fun rows(): List<MessageEntity> = runBlocking { db.messageDao().observeAllMessages().first() }
        fun inboundIds(): List<String> = rows().filter { !it.isOutgoing }.map { it.id }

        /** Stand-in for ChatMessagingService.persistInbound (SIMULATED; Room PK and findById are real). */
        val persist: suspend (String, InboundRecord) -> Unit = { _, record ->
            persistCalls += 1
            MeshInboxCommit.inboundRow(conversationId, record, nowMs)?.let { e ->
                if (db.messageDao().findById(e.id) == null) db.messageDao().insert(e)
            }
        }

        fun framedOffers(): ByteArray = runBlocking { MeshFrames.encodeBundles(engine.offers(emptyList())) }

        /** Same call EmergencyMeshService.ingest makes, with [orchestrator] behind deliverMesh. */
        fun receiveMesh(frames: ByteArray, orchestrator: SessionOrchestrator) = runBlocking {
            MeshCourier.ingest(
                bytes = frames,
                path = RadioPath.BLE,
                store = { encoded, via -> engine.ingest(encoded, via) },
                deliver = { payload -> MeshDelivery.deliver(payload, { raw, p -> orchestrator.offerMesh(raw, p) }, persist) },
                dropDelivered = { idHex -> engine.dropDelivered(idHex) },
            )
        }
    }

    private inner class Pair2 {
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceOrch = orch(DefaultRelayProvider(registry) { aliceClient(network.client()) })
        val bobOrch = orch(DefaultRelayProvider(registry) { network.client() })
        val alicePhone = Phone("conv-bob")
        val bobPhone = Phone("conv-alice")

        init {
            runBlocking {
                aliceOrch.provision(alice, bob.pin)
                bobOrch.provision(bob, alice.pin)
                aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
                confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
                check(aliceOrch.sessionView(alice.pin, bob.pin).composerAllowed)
                check(bobOrch.sessionView(bob.pin, alice.pin).composerAllowed)
            }
            reached.clear()
        }

        val aliceReceipts get() = aliceOrch.link(bob.pin)!!.receipts

        /**
         * SIMULATED ChatMessagingService.sendText lines 390-419 around the REAL orchestrator call:
         * an exception here is what the service turns into SessionNotReady (no row, no hold).
         */
        fun serviceSend(text: String, meshArmed: Boolean = true): OutboundRecord = runBlocking {
            val record = aliceOrch.sendText(bob.pin, text)
            if (meshArmed && record.progress.status != MessageStatus.SENT) {
                record.envelopeCanonical?.toByteArray(Charsets.UTF_8)?.let { alicePhone.engine.holdSealed(it, emergency = true) }
            }
            if (alicePhone.db.messageDao().findById(record.messageId) == null) {
                alicePhone.db.messageDao().insert(
                    MessageEntity(
                        id = record.messageId,
                        conversationId = alicePhone.conversationId,
                        type = MessageType.TEXT.name,
                        isOutgoing = true,
                        localPlaintext = text,
                        ciphertext = record.ciphertextB64?.toByteArray(Charsets.UTF_8),
                        status = record.progress.status.name,
                        createdAtEpochMs = nowMs,
                    ),
                )
            }
            record
        }

        fun bobRelaySync() = runBlocking { bobOrch.sync(alice.pin) { r -> bobPhone.persist(alice.pin, r) } }
        fun bobTexts() = bobOrch.link(alice.pin)!!.receipts.acceptedPlaintexts()
        fun aliceStatus(id: String) = aliceReceipts.status(id)
    }

    private fun outerId(payload: ByteArray): String =
        org.json.JSONObject(String(payload, Charsets.UTF_8)).getString("message_id")

    @After
    fun close() {
        databases.forEach { it.close() }
    }

    // 1 ------------------------------------------------------------------------------------------
    @Test
    fun offlineIoExceptionFromRealOkHttpTransportBecomesTypedUnavailable() {
        val p = Pair2()
        aliceNet = Net.OFFLINE
        // Raw transport behaviour is unchanged: real OkHttp throws, it does not return a result.
        val env = runBlocking {
            p.aliceOrch.sendText(p.bob.pin, "probe") // must not throw any more
        }.envelope!!
        try {
            runBlocking { offlineHttp.submitEnvelope("token", env) }
            fail("real OkHttp transport should throw offline")
        } catch (_: UnknownHostException) {
        }
        // The submit seam maps it to the typed result.
        val result = runBlocking { p.aliceReceipts.resubmitExact(env.messageId) }
        assertEquals(RelaySubmitResult.Unavailable, result)
        assertEquals(OutboxPolicy.SubmitOutcome.OFFLINE, OutboxPolicy.classify(result))
        assertTrue(reached.isEmpty())
    }

    @Test
    fun transportFailureClassificationSeparatesNotSentFromAmbiguous() {
        val notSent = listOf(
            UnknownHostException("x"), ConnectException("x"), NoRouteToHostException("x"), SSLHandshakeException("x"),
        )
        val ambiguous = listOf(
            SocketTimeoutException("timeout"), SocketException("reset"), EOFException("x"), InterruptedIOException("x"),
        )
        notSent.forEach { assertEquals(it.toString(), OutboxPolicy.TransportFailure.NOT_SENT, OutboxPolicy.classifyTransportFailure(it)) }
        ambiguous.forEach { assertEquals(it.toString(), OutboxPolicy.TransportFailure.AMBIGUOUS, OutboxPolicy.classifyTransportFailure(it)) }
    }

    // 2 ------------------------------------------------------------------------------------------
    @Test
    fun cancellationExceptionFromSubmitPropagates() {
        val p = Pair2()
        aliceNet = Net.CANCEL
        try {
            runBlocking { p.aliceOrch.sendText(p.bob.pin, "cancel me") }
            fail("CancellationException must propagate")
        } catch (_: CancellationException) {
        }
        val rec = p.aliceReceipts.store.texts().single()
        // Not mapped to OFFLINE: no retry-wait commit happened, attempt is still the in-flight SENDING.
        assertEquals(AttemptState.SENDING, rec.progress.attempt)
        assertEquals(0, rec.progress.attemptCount)
        assertEquals(DeliveryEvidence.NONE, rec.progress.evidence)
    }

    @Test
    fun interruptedIoWhileCoroutineCancelledIsRethrownAsCancellation() {
        val p = Pair2()
        aliceNet = Net.CANCELLED_IO
        val job = runBlocking {
            val d = async { p.aliceOrch.sendText(p.bob.pin, "cancelled io") }
            try {
                d.await()
                fail("cancelled caller must not get a result")
            } catch (_: CancellationException) {
            }
            d
        }
        assertTrue(job.isCancelled)
        val rec = p.aliceReceipts.store.texts().single()
        assertEquals(AttemptState.SENDING, rec.progress.attempt)
        // The committed envelope is still the retry unit: a later flush resends the identical bytes.
        aliceNet = Net.ONLINE
        runBlocking { p.aliceReceipts.flushOutbox() }
        assertEquals(MessageStatus.SENT, p.aliceStatus(rec.messageId))
        assertEquals(listOf(Reached(rec.messageId, rec.envelopeCanonical!!, replay = false)), reached)
    }

    // 3 + 5 --------------------------------------------------------------------------------------
    @Test
    fun offlineSendQueuesRowAndHoldsTheSameEnvelopeForMesh() {
        val p = Pair2()
        aliceNet = Net.OFFLINE
        val record = p.serviceSend("airplane hello")
        assertEquals(MessageStatus.QUEUED, record.progress.status)
        val env = record.envelope!!
        assertEquals(record.messageId, env.messageId)
        val row = runBlocking { p.alicePhone.db.messageDao().findById(record.messageId) }!!
        assertEquals(MessageStatus.QUEUED.name, row.status)
        assertTrue(row.isOutgoing)
        val held = p.alicePhone.custody().single()
        assertEquals(0, held.hopCount)
        assertArrayEquals(record.envelopeCanonical!!.toByteArray(Charsets.UTF_8), held.payload)
        assertEquals(record.messageId, outerId(held.payload))
        assertEquals(env.canonicalJson(), record.envelopeCanonical)
        assertEquals(1, p.aliceReceipts.store.texts().size)
        assertTrue(reached.isEmpty())

        // Internet returns: the relay retry submits the SAME id and the SAME canonical bytes.
        aliceNet = Net.ONLINE
        runBlocking { p.aliceReceipts.flushOutbox() }
        assertEquals(listOf(Reached(record.messageId, String(held.payload, Charsets.UTF_8), replay = false)), reached)
        assertEquals(1, p.aliceReceipts.store.texts().size)
        assertEquals(1, p.alicePhone.rows().size)
    }

    // 4 ------------------------------------------------------------------------------------------
    @Test
    fun offlineFailureNeverReportsSentOrDelivered() {
        val p = Pair2()
        aliceNet = Net.OFFLINE
        val record = p.serviceSend("not delivered yet")
        repeat(3) { runBlocking { p.aliceReceipts.flushOutbox() } }
        val now = p.aliceReceipts.outbound(record.messageId).progress
        assertEquals(DeliveryEvidence.NONE, now.evidence)
        assertEquals(MessageStatus.QUEUED, now.status)
        assertFalse(now.status == MessageStatus.SENT || now.status == MessageStatus.DELIVERED)
        // Mesh carries it to Bob; Alice still has no evidence (no Mesh ACK, relay unreachable).
        p.bobPhone.receiveMesh(p.alicePhone.framedOffers(), p.bobOrch)
        assertEquals(listOf("not delivered yet"), p.bobTexts())
        runCatching { runBlocking { p.aliceOrch.sync(p.bob.pin) } } // offline fetch throws: no evidence
        assertEquals(MessageStatus.QUEUED, p.aliceStatus(record.messageId))
    }

    // 6 ------------------------------------------------------------------------------------------
    @Test
    fun ambiguousTimeoutAfterRelayAcceptanceDoesNotCreateSecondLogicalMessage() {
        val p = Pair2()
        aliceNet = Net.ACCEPT_THEN_TIMEOUT
        val record = p.serviceSend("maybe sent")
        assertEquals(MessageStatus.QUEUED, record.progress.status) // never claims SENT without a response
        assertEquals(1, reached.size)
        assertFalse(reached.single().replay)
        aliceNet = Net.ONLINE
        runBlocking { p.aliceReceipts.flushOutbox() }
        assertEquals(MessageStatus.SENT, p.aliceStatus(record.messageId))
        assertEquals(2, reached.size)
        assertTrue("retry is an idempotent replay of the same envelope", reached[1].replay)
        assertEquals(reached[0], reached[1].copy(replay = false))
        assertEquals(1, p.aliceReceipts.store.texts().size)
        assertEquals(listOf(record.messageId), p.alicePhone.rows().map { it.id })
        p.bobRelaySync()
        p.bobRelaySync()
        assertEquals(listOf(record.messageId), p.bobPhone.inboundIds())
        assertEquals(listOf("maybe sent"), p.bobTexts())
    }

    // 7 ------------------------------------------------------------------------------------------
    @Test
    fun meshFirstThenRelayReplayDoesNotDuplicateReceiverInbox() {
        val p = Pair2()
        aliceNet = Net.OFFLINE
        val record = p.serviceSend("mesh first")
        p.bobPhone.receiveMesh(p.alicePhone.framedOffers(), p.bobOrch)
        assertEquals(listOf(record.messageId), p.bobPhone.inboundIds())
        assertTrue(runBlocking { MeshDelivery.alreadyStored(p.alicePhone.custody().single().payload) { p.bobPhone.db.messageDao().findById(it) } })

        aliceNet = Net.ONLINE
        runBlocking { p.aliceReceipts.flushOutbox() } // relay now gets the same envelope
        assertEquals(MessageStatus.SENT, p.aliceStatus(record.messageId))
        val callsBefore = p.bobPhone.persistCalls
        p.bobRelaySync()
        assertTrue("seen store re-hands the accepted record", p.bobPhone.persistCalls > callsBefore)
        assertEquals(listOf(record.messageId), p.bobPhone.inboundIds())
        assertEquals(listOf("mesh first"), p.bobTexts())
        // The relay copy was consumed; a further sync finds nothing new.
        p.bobRelaySync()
        assertEquals(listOf(record.messageId), p.bobPhone.inboundIds())
        // Delivery evidence only from Bob's validated receipt.
        runBlocking { p.aliceOrch.sync(p.bob.pin) }
        assertEquals(MessageStatus.DELIVERED, p.aliceStatus(record.messageId))
    }

    // 8 ------------------------------------------------------------------------------------------
    @Test
    fun relayFirstThenMeshReplayDoesNotDuplicateReceiverInbox() {
        val p = Pair2()
        aliceNet = Net.ACCEPT_THEN_TIMEOUT // relay holds it, Alice saw a timeout: QUEUED + held
        val record = p.serviceSend("relay first")
        assertEquals(1, p.alicePhone.custody().size)
        p.bobRelaySync()
        assertEquals(listOf(record.messageId), p.bobPhone.inboundIds())
        val payload = p.alicePhone.custody().single().payload
        // Durable pre-check MeshCustodyRecovery uses: already stored, no trial decrypt.
        assertTrue(runBlocking { MeshDelivery.alreadyStored(payload) { p.bobPhone.db.messageDao().findById(it) } })
        // Courier path: seen store + findById drop the copy.
        p.bobPhone.receiveMesh(p.alicePhone.framedOffers(), p.bobOrch)
        assertEquals(listOf(record.messageId), p.bobPhone.inboundIds())
        assertEquals(listOf("relay first"), p.bobTexts())
        // Alice's later relay retry is a replay; Bob still has one row.
        aliceNet = Net.ONLINE
        runBlocking { p.aliceReceipts.flushOutbox() }
        assertTrue(reached.last().replay)
        p.bobRelaySync()
        assertEquals(listOf(record.messageId), p.bobPhone.inboundIds())
    }

    // 9 ------------------------------------------------------------------------------------------
    @Test
    fun offlineDoesNotBurnAttemptsButRetryableRejectionsStillDo() {
        val p = Pair2()
        aliceNet = Net.OFFLINE
        val record = p.serviceSend("count attempts", meshArmed = false)
        assertEquals(0, p.aliceReceipts.outbound(record.messageId).progress.attemptCount)
        repeat(OutboxPolicy.MAX_AUTOMATIC_ATTEMPTS + 2) { runBlocking { p.aliceReceipts.flushOutbox() } }
        val afterOffline = p.aliceReceipts.outbound(record.messageId).progress
        assertEquals("OFFLINE never exhausts the retry budget", 0, afterOffline.attemptCount)
        assertEquals(MessageStatus.QUEUED, afterOffline.status)
        assertEquals(nowMs / 1000, afterOffline.retryAtEpochSeconds) // backoff index 0, jitter 0

        aliceNet = Net.ONLINE
        p.aliceReceipts.submitInterceptor = { RelaySubmitResult.Rejected("INTERNAL") }
        runBlocking { p.aliceReceipts.flushOutbox() }
        assertEquals(1, p.aliceReceipts.outbound(record.messageId).progress.attemptCount)
        p.aliceReceipts.submitInterceptor = { RelaySubmitResult.ServiceSuspended(60) }
        runBlocking { p.aliceReceipts.flushOutbox() }
        assertEquals(1, p.aliceReceipts.outbound(record.messageId).progress.attemptCount)
        p.aliceReceipts.submitInterceptor = null
        runBlocking { p.aliceReceipts.flushOutbox() }
        assertEquals(MessageStatus.SENT, p.aliceStatus(record.messageId))
        assertEquals(1, reached.size)
    }

    // 10 -----------------------------------------------------------------------------------------
    @Test
    fun http429And503FallbackStillQueuesAndHolds() {
        // 429 -> ServiceSuspended (CodeYantraRelayTransport line ~102); 503 -> Rejected(code). Mapped
        // results are injected at the existing submitInterceptor seam (MOCK relay responses).
        val p = Pair2()
        p.aliceReceipts.submitInterceptor = { RelaySubmitResult.ServiceSuspended(60) }
        val r429 = p.serviceSend("busy relay")
        assertEquals(MessageStatus.QUEUED, r429.progress.status)
        assertEquals(0, r429.progress.attemptCount)
        p.aliceReceipts.submitInterceptor = { RelaySubmitResult.Rejected("SERVICE_UNAVAILABLE") }
        val r503 = p.serviceSend("down relay")
        assertEquals(MessageStatus.QUEUED, r503.progress.status)
        assertEquals(1, r503.progress.attemptCount)
        assertNotEquals(r429.messageId, r503.messageId)
        assertEquals(
            setOf(r429.messageId, r503.messageId),
            p.alicePhone.custody().map { outerId(it.payload) }.toSet(),
        )
        assertEquals(2, p.alicePhone.rows().size)
        p.aliceReceipts.submitInterceptor = null
        runBlocking { p.aliceReceipts.flushOutbox() }
        assertEquals(MessageStatus.SENT, p.aliceStatus(r429.messageId))
        assertEquals(MessageStatus.SENT, p.aliceStatus(r503.messageId))
    }

    // 11 (Mesh-1K-C, test-only) ----------------------------------------------------------------
    @Test
    fun retryExhaustionIsTerminalNotResubmittedAndSurfacesFailedToRow() {
        val p = Pair2()
        aliceNet = Net.OFFLINE
        val record = p.serviceSend("never accepted")
        assertEquals(1, p.alicePhone.custody().size)
        aliceNet = Net.ONLINE
        var rejected = 0
        p.aliceReceipts.submitInterceptor = { rejected += 1; RelaySubmitResult.Rejected("INTERNAL") }
        repeat(OutboxPolicy.MAX_AUTOMATIC_ATTEMPTS + 5) { runBlocking { p.aliceReceipts.flushOutbox() } }
        assertEquals("exactly MAX retryable submits, then terminal", OutboxPolicy.MAX_AUTOMATIC_ATTEMPTS, rejected)
        val failed = p.aliceReceipts.outbound(record.messageId).progress
        assertEquals(AttemptState.FAILED, failed.attempt)
        assertEquals(MessageStatus.FAILED, failed.status)
        assertEquals(DeliveryEvidence.NONE, failed.evidence)
        assertEquals("retry exhausted", failed.failure!!.reason)
        assertTrue(failed.failure!!.ambiguousAcceptance)
        // due() refuses FAILED: a healthy relay never sees it again.
        p.aliceReceipts.submitInterceptor = null
        repeat(3) { runBlocking { p.aliceReceipts.flushOutbox() } }
        assertTrue(reached.isEmpty())
        assertEquals(OutboxPolicy.MAX_AUTOMATIC_ATTEMPTS, rejected)
        // SIMULATED ChatMessagingService.syncContact lines 953-960 (status mirror into the row).
        runBlocking {
            p.aliceReceipts.store.texts().forEach { outbound ->
                if (p.alicePhone.db.messageDao().findById(outbound.messageId) != null) {
                    p.alicePhone.db.messageDao().updateStatus(outbound.messageId, outbound.progress.status.name)
                }
            }
        }
        assertEquals(MessageStatus.FAILED.name, runBlocking { p.alicePhone.db.messageDao().findById(record.messageId) }!!.status)
        // Limitation, recorded: the Mesh hop-0 copy is not withdrawn on relay exhaustion.
        assertEquals(1, p.alicePhone.custody().size)
    }

    @Test
    fun repeatedFailureOfOneItemDoesNotAbortTheRestOfTheFlush() {
        val p = Pair2()
        aliceNet = Net.OFFLINE
        val first = p.serviceSend("first stuck", meshArmed = false)
        val second = p.serviceSend("second fine", meshArmed = false)
        aliceNet = Net.ONLINE
        p.aliceReceipts.submitInterceptor = { env ->
            if (env.messageId == first.messageId) RelaySubmitResult.Rejected("INTERNAL") else null
        }
        runBlocking { p.aliceReceipts.flushOutbox() } // first: 1st retryable failure from QUEUED
        runBlocking { p.aliceReceipts.flushOutbox() } // first: 2nd failure (threw before 1K-B)
        assertEquals(2, p.aliceReceipts.outbound(first.messageId).progress.attemptCount)
        assertEquals(MessageStatus.QUEUED, p.aliceStatus(first.messageId))
        assertEquals(MessageStatus.SENT, p.aliceStatus(second.messageId))
        assertEquals(listOf(second.messageId), reached.map { it.messageId })
    }
}
