package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.CodeYantraRelayTransport
import com.codeyantra.armorchat.core.network.ImmutableRelayEnvelope
import com.codeyantra.armorchat.core.network.LeasedRelayEnvelope
import com.codeyantra.armorchat.core.network.RelayQueueState
import com.codeyantra.armorchat.core.network.RelaySubmitResult
import com.codeyantra.armorchat.crypto.MlsRsFeasibility
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.AfterClass
import org.junit.Assert.assertThrows
import org.junit.Assume
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyStore
import java.security.Security
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

class ReceiptsAndRetryTest {
    private class TinkSigner : IdentitySigner {
        private val handle: KeysetHandle
        override val pin: String

        init {
            SignatureConfig.register()
            handle = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            val pub = (handle.publicKeysetHandle.primary.key as Ed25519PublicKey)
                .publicKeyBytes.toByteArray()
            pin = Base64Url.encode(pub)
        }

        override fun sign(context: String, bodyCanonical: ByteArray): ByteArray {
            require(context in ArmorChatContexts.allowed)
            val prefix = context.toByteArray(Charsets.US_ASCII) + 0
            require(bodyCanonical.copyOf(prefix.size).contentEquals(prefix))
            return RawIdentitySignature.sign(handle, bodyCanonical)
        }
    }

    private fun rawPeer(): TwoPhonePeer =
        TwoPhonePeer(TinkSigner(), transport, nowSeconds = { System.currentTimeMillis() / 1000 })

    private fun wrap(peer: TwoPhonePeer) =
        ReceiptMessagingPeer(peer, { System.currentTimeMillis() / 1000 }, jitter = { 0 })

    @Test
    fun offlineThenDeliveryReceipt() = runBlocking {
        val (a, b) = setup()
        val sent = a.sendText("Hello ArmorChat")
        assertThat(sent.progress.status).isEqualTo(MessageStatus.SENT)
        assertThat(b.acceptedPlaintexts()).isEmpty()
        assertThat(b.receiveAll()).containsExactly("Hello ArmorChat")
        a.receiveAll()
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.DELIVERED)
    }

    @Test
    fun openedMessageSendsReadReceipt() = runBlocking {
        val (a, b) = setup()
        val sent = a.sendText("Hello ArmorChat")
        b.receiveAll()
        b.markOpened(b.lastAcceptedTextId())
        b.flushOutbox()
        a.receiveAll()
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.READ)
    }

    @Test
    fun exactCiphertextRetryPreservesIdAndExpiry() = runBlocking {
        val (a, _) = setup()
        val seen = mutableListOf<String>()
        var fails = 1
        a.submitInterceptor = { env ->
            seen += env.canonicalJson()
            if (fails-- > 0) RelaySubmitResult.Rejected("TIMEOUT") else null
        }
        val first = a.sendText("Hello ArmorChat")
        assertThat(first.progress.status).isEqualTo(MessageStatus.QUEUED)
        assertThat(first.envelopeCanonical).isNotNull()
        a.flushOutbox()
        assertThat(a.status(first.messageId)).isEqualTo(MessageStatus.SENT)
        assertThat(seen).hasSize(2)
        assertThat(seen[0]).isEqualTo(seen[1])
        assertThat(a.outbound(first.messageId).expiresAt).isEqualTo(first.expiresAt)
        assertThat(a.outbound(first.messageId).ciphertextB64).isEqualTo(first.ciphertextB64)
    }

    @Test
    fun duplicateEnvelopeDoesNotDuplicateLocalMessage() = runBlocking {
        val (a, b) = setup()
        a.sendText("Hello ArmorChat")
        val leased = b.peer.fetchLeased()
        assertThat(leased).isNotEmpty()
        b.processLeased(leased.first(), consumeOnSuccess = false)
        b.processLeased(leased.first(), consumeOnSuccess = false)
        assertThat(b.acceptedPlaintexts()).containsExactly("Hello ArmorChat")
        val replay = a.resubmitExact(a.lastTextId()) as RelaySubmitResult.Accepted
        assertThat(replay.replay).isTrue()
    }

    @Test
    fun forgedDeliveryAndReadReceiptsFailClosed() = runBlocking {
        val (a, b) = setup()
        val sent = a.sendText("Hello ArmorChat")
        val garbage = LeasedRelayEnvelope(
            messageId = sent.messageId,
            envelopeSha256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            encryptedPayloadB64 = Base64Url.encode(ByteArray(64) { 7 }),
            payloadType = ImmutableRelayEnvelope.PAYLOAD_APPLICATION,
            expiresAt = sent.expiresAt,
            leaseToken = "lease",
            leaseUntil = System.currentTimeMillis() / 1000 + 60,
        )
        assertThrows(MessagingProtocolException::class.java) { runBlocking { a.processLeased(garbage, false) } }
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)
        val forgedRead = garbage.copy(messageId = Base64Url.encode(ByteArray(16) { 3 }))
        assertThrows(MessagingProtocolException::class.java) { runBlocking { a.processLeased(forgedRead, false) } }
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)
        unused(b)
    }

    @Test
    fun wrongMessageSessionDigestAndEpochFailClosed() = runBlocking {
        val (a, b) = setup()
        val sent = a.sendText("Hello ArmorChat")
        val wrongId = b.receiptTemplate(InnerKind.DELIVERY, sent) { rec ->
            rec.copy(body = (rec.body as InnerBody.Receipt).copy(targetMessageId = Base64Url.encode(ByteArray(16) { 9 })))
        }
        b.submitInner(wrongId)
        assertThrows(MessagingProtocolException::class.java) { runBlocking { a.receiveAll() } }
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)

        val wrongSession = b.receiptTemplate(InnerKind.DELIVERY, sent) { rec ->
            rec.copy(body = (rec.body as InnerBody.Receipt).copy(targetSessionId = Base64Url.encode(ByteArray(16) { 8 })))
        }
        b.submitInner(wrongSession)
        assertThrows(MessagingProtocolException::class.java) { runBlocking { a.receiveAll() } }
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)

        val wrongDigest = b.receiptTemplate(InnerKind.DELIVERY, sent) { rec ->
            rec.copy(body = (rec.body as InnerBody.Receipt).copy(targetCiphertextSha256 = Base64Url.encode(ByteArray(32) { 4 })))
        }
        b.submitInner(wrongDigest)
        assertThrows(MessagingProtocolException::class.java) { runBlocking { a.receiveAll() } }
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)

        val staleEpoch = b.receiptTemplate(InnerKind.DELIVERY, sent) { rec ->
            rec.copy(body = (rec.body as InnerBody.Receipt).copy(targetEpoch = 99L))
        }
        b.submitInner(staleEpoch)
        assertThrows(MessagingProtocolException::class.java) { runBlocking { a.receiveAll() } }
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)
    }

    @Test
    fun staleEpochCiphertextRejected() = runBlocking {
        val (a, b) = setup()
        val sent = a.sendText("Hello ArmorChat")
        val receipt = b.receiptTemplate(InnerKind.DELIVERY, sent)
        val stale = b.peer.encryptRaw(receipt.encode())
        val commit = a.peer.selfUpdate()
        b.peer.applyCommit(commit)
        val leased = LeasedRelayEnvelope(
            messageId = receipt.messageId,
            envelopeSha256 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            encryptedPayloadB64 = stale.ciphertextB64,
            payloadType = ImmutableRelayEnvelope.PAYLOAD_APPLICATION,
            expiresAt = receipt.expiresAt,
            leaseToken = "lease",
            leaseUntil = System.currentTimeMillis() / 1000 + 60,
        )
        assertThrows(MessagingProtocolException::class.java) { runBlocking { a.processLeased(leased, false) } }
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)
    }

    @Test
    fun duplicateReceiptIsIdempotent() = runBlocking {
        val (a, b) = setup()
        val sent = a.sendText("Hello ArmorChat")
        b.receiveAll()
        val items = a.peer.fetchLeased()
        assertThat(items).isNotEmpty()
        a.processLeased(items.first(), consumeOnSuccess = false)
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.DELIVERED)
        a.processLeased(items.first(), consumeOnSuccess = false)
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.DELIVERED)
    }

    @Test
    fun receiptBeforeLocalAcceptFailsClosed() = runBlocking {
        val (a, b) = setup()
        a.sendText("Hello ArmorChat")
        val leased = b.peer.fetchLeased().first()
        assertThrows(MessagingProtocolException::class.java) {
            b.queueDeliveryReceipt(leased.messageId)
        }
        assertThat(b.acceptedPlaintexts()).isEmpty()
        b.processLeased(leased)
        assertThat(b.acceptedPlaintexts()).containsExactly("Hello ArmorChat")
        Unit
    }

    @Test
    fun relayCannotFakeDeliveredOrRead() = runBlocking {
        val (a, _) = setup()
        val sent = a.sendText("Hello ArmorChat")
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)
        assertThat(a.applyRelayQueueHint(sent.messageId, RelayQueueState.CONSUMED))
            .isEqualTo(MessageStatus.SENT)
        assertThrows(MessagingProtocolException::class.java) { a.refuseRelayStatusWord("delivered") }
        assertThrows(MessagingProtocolException::class.java) { a.refuseRelayStatusWord("read") }
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.SENT)
    }

    @Test
    fun lateReceiptAdvancesFailed() = runBlocking {
        val (a, b) = setup()
        val sent = a.sendText("Hello ArmorChat", acknowledgeRelay = false)
        a.forceFail(sent.messageId)
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.FAILED)
        b.receiveAll()
        a.receiveAll()
        assertThat(a.status(sent.messageId)).isEqualTo(MessageStatus.DELIVERED)
    }

    private suspend fun setup(): Pair<ReceiptMessagingPeer, ReceiptMessagingPeer> {
        val a = rawPeer()
        val b = rawPeer()
        a.createMlsDevice()
        b.createMlsDevice()
        a.registerAndPublish()
        b.registerAndPublish()
        a.importRoute(b.exportRoute(a.pin).toQr(), b.pin)
        b.importRoute(a.exportRoute(b.pin).toQr(), a.pin)
        a.initiateGroup()
        assertThat(b.joinFromInbox()).isTrue()
        return wrap(a) to wrap(b)
    }

    private fun unused(value: Any?) = Unit

    companion object {
        private lateinit var work: File
        private lateinit var process: Process
        private lateinit var transport: CodeYantraRelayTransport

        @JvmStatic
        @BeforeClass
        fun start() {
            Security.getProviders()
                .filter { it.name.contains("Conscrypt", ignoreCase = true) }
                .forEach { Security.removeProvider(it.name) }
            try {
                MlsRsFeasibility.nativeProbe()
            } catch (_: UnsatisfiedLinkError) {
                Assume.assumeTrue("libarmorchat_mlsrs not on java.library.path", false)
            } catch (_: ExceptionInInitializerError) {
                Assume.assumeTrue("libarmorchat_mlsrs not on java.library.path", false)
            } catch (_: NoClassDefFoundError) {
                Assume.assumeTrue("libarmorchat_mlsrs not on java.library.path", false)
            }
            work = Files.createTempDirectory("armorchat-receipts").toFile()
            val key = File(work, "key.pem")
            val cert = File(work, "cert.pem")
            ProcessBuilder(
                "openssl", "req", "-x509", "-newkey", "rsa:2048", "-sha256", "-days", "1",
                "-nodes", "-subj", "/CN=localhost", "-keyout", key.absolutePath, "-out", cert.absolutePath,
            ).redirectErrorStream(true).start().waitFor()
            Assume.assumeTrue(cert.exists())
            val data = File(work, "data")
            data.mkdirs()
            val cli = sequenceOf(
                File("../relay/src/cli.mjs"),
                File("relay/src/cli.mjs"),
                File("android/relay/src/cli.mjs"),
            ).firstOrNull { it.exists() }
            Assume.assumeTrue(cli != null)
            val relayRoot = cli!!.parentFile!!.parentFile
            process = ProcessBuilder("node", cli.absolutePath)
                .directory(relayRoot)
                .redirectErrorStream(true)
                .apply {
                    environment()["RELAY_DATA_DIR"] = data.absolutePath
                    environment()["RELAY_TLS_CERT"] = cert.absolutePath
                    environment()["RELAY_TLS_KEY"] = key.absolutePath
                    environment()["RELAY_LISTEN"] = "127.0.0.1:8445"
                    environment()["RELAY_MAILBOX_LIMIT"] = "100"
                }
                .start()
            var ready = false
            repeat(20) {
                Thread.sleep(150)
                if (process.isAlive) ready = true
            }
            Assume.assumeTrue(ready)
            transport = CodeYantraRelayTransport(
                origin = CodeYantraRelayTransport.parseOrigin("https://127.0.0.1:8445"),
                client = pinnedClient(cert),
            )
        }

        @JvmStatic
        @AfterClass
        fun stop() {
            if (::process.isInitialized) process.destroy()
            if (::work.isInitialized) work.deleteRecursively()
        }

        private fun pinnedClient(certFile: File): OkHttpClient {
            val cert = certFile.inputStream().use {
                CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate
            }
            val ks = KeyStore.getInstance(KeyStore.getDefaultType())
            ks.load(null)
            ks.setCertificateEntry("relay", cert)
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            tmf.init(ks)
            val tm = tmf.trustManagers.first { it is X509TrustManager } as X509TrustManager
            val ssl = SSLContext.getInstance("TLS")
            ssl.init(null, arrayOf(tm), null)
            return OkHttpClient.Builder()
                .sslSocketFactory(ssl.socketFactory, tm)
                .hostnameVerifier { host, _ -> host == "127.0.0.1" || host == "localhost" }
                .callTimeout(15, TimeUnit.SECONDS)
                .build()
        }
    }
}
