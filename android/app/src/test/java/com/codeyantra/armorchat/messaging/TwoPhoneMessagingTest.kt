package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.CodeYantraRelayTransport
import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.core.network.ServicePolicyTrust
import com.codeyantra.armorchat.crypto.MlsRsFeasibility
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.domain.identity.Base64Url
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

class TwoPhoneMessagingTest {
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

    private fun peer(): TwoPhonePeer =
        TwoPhonePeer(TinkSigner(), transport, nowSeconds = { System.currentTimeMillis() / 1000 })

    @Test
    fun coordinatorAutomatesRouteExchangeAndHello() = runBlocking {
        val registry = DefaultRelayRegistry(
            listOf(RelayNode("local", "https://127.0.0.1:8444", RelayRole.BOOTSTRAP)),
        )
        val provider = DefaultRelayProvider(registry) { transport }
        fun orch() = SessionOrchestrator(
            registry = registry,
            provider = provider,
            policy = ServicePolicyController(ServicePolicyTrust()),
            factory = DefaultMessagingLinkFactory,
            nowSeconds = { System.currentTimeMillis() / 1000 },
        )
        val aliceOrch = orch()
        val bobOrch = orch()
        val alice = TinkSigner()
        val bob = TinkSigner()
        aliceOrch.provision(alice, bob.pin)
        bobOrch.provision(bob, alice.pin)
        aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
        confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
        assertThat(aliceOrch.sessionView(alice.pin, bob.pin).composerAllowed).isTrue()
        assertThat(bobOrch.sessionView(bob.pin, alice.pin).composerAllowed).isTrue()
        aliceOrch.sendText(bob.pin, "Hello ArmorChat")
        assertThat(bobOrch.sync(alice.pin)).contains("Hello ArmorChat")
    }

    @Test
    fun helloArmorChatOverRelayAndReply() = runBlocking {
        val a = peer()
        val b = peer()
        a.createMlsDevice()
        b.createMlsDevice()
        a.registerAndPublish()
        b.registerAndPublish()
        a.importRoute(b.exportRoute(a.pin).toQr(), b.pin)
        b.importRoute(a.exportRoute(b.pin).toQr(), a.pin)
        a.initiateGroup()
        assertThat(b.joinFromInbox()).isTrue()
        val sent = a.sendPlaintext("Hello ArmorChat")
        assertThat(sent.state.name).isEqualTo("ACCEPTED")
        assertThat(sent.replay).isFalse()
        assertThat(b.receivePlaintext()).isEqualTo("Hello ArmorChat")
        b.sendPlaintext("Hello from B")
        assertThat(a.receivePlaintext()).isEqualTo("Hello from B")
        assertThat(a.diagnostics.lastMessageId).isNotEmpty()
        assertThat(a.diagnostics.epoch).isGreaterThan(0)
        assertThat(a.diagnostics.relayState).isEqualTo("fetched")
    }

    @Test
    fun persistReloadContinues() = runBlocking {
        val a = peer()
        val b = peer()
        setupGroup(a, b)
        a.sendPlaintext("Hello ArmorChat")
        assertThat(b.receivePlaintext()).isEqualTo("Hello ArmorChat")
        val savedA = a.snapshot()
        val savedB = b.snapshot()
        a.restore(savedA)
        b.restore(savedB)
        b.sendPlaintext("after restart")
        assertThat(a.receivePlaintext()).isEqualTo("after restart")
    }

    @Test
    fun duplicateEnvelopeDoesNotChangePlaintext() = runBlocking {
        val a = peer()
        val b = peer()
        setupGroup(a, b)
        a.sendPlaintext("Hello ArmorChat")
        val again = a.resubmitLast() as com.codeyantra.armorchat.core.network.RelaySubmitResult.Accepted
        assertThat(again.replay).isTrue()
        assertThat(again.state.name).isEqualTo("ACCEPTED")
        assertThat(b.receivePlaintext()).isEqualTo("Hello ArmorChat")
        assertThat(b.receivePlaintext()).isNull()
    }

    @Test
    fun tamperedCiphertextRejected(): Unit = runBlocking {
        val a = peer()
        val b = peer()
        setupGroup(a, b)
        val enc = a.encryptApplication("Hello ArmorChat")
        val raw = Base64Url.decode(enc)
        raw[raw.lastIndex] = (raw.last().toInt() xor 1).toByte()
        assertThrows(Exception::class.java) { b.decryptLocal(Base64Url.encode(raw)) }
    }

    @Test
    fun wrongIdentityBindingRejected(): Unit = runBlocking {
        val a = peer()
        val b = peer()
        val c = peer()
        a.createMlsDevice()
        b.createMlsDevice()
        c.createMlsDevice()
        a.registerAndPublish()
        b.registerAndPublish()
        a.importRoute(b.exportRoute(a.pin).toQr(), b.pin)
        assertThrows(Exception::class.java) {
            a.importRoute(b.exportRoute(a.pin).toQr(), c.pin)
        }
    }

    @Test
    fun staleEpochRejected(): Unit = runBlocking {
        val a = peer()
        val b = peer()
        setupGroup(a, b)
        val stale = a.encryptApplication("old")
        val commit = a.selfUpdate()
        b.applyCommit(commit)
        assertThrows(Exception::class.java) { b.decryptLocal(stale) }
    }

    @Test
    fun offlineThenFetch() = runBlocking {
        val a = peer()
        val b = peer()
        setupGroup(a, b)
        a.sendPlaintext("Hello ArmorChat")
        // B was "offline": no fetch yet.
        assertThat(b.receivePlaintext()).isEqualTo("Hello ArmorChat")
    }

    private suspend fun setupGroup(a: TwoPhonePeer, b: TwoPhonePeer) {
        a.createMlsDevice()
        b.createMlsDevice()
        a.registerAndPublish()
        b.registerAndPublish()
        a.importRoute(b.exportRoute(a.pin).toQr(), b.pin)
        b.importRoute(a.exportRoute(b.pin).toQr(), a.pin)
        a.initiateGroup()
        assertThat(b.joinFromInbox()).isTrue()
    }

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
            work = Files.createTempDirectory("armorchat-two-phone").toFile()
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
                    environment()["RELAY_LISTEN"] = "127.0.0.1:8444"
                    environment()["RELAY_MAILBOX_LIMIT"] = "100"
                }
                .start()
            var ready = false
            repeat(20) {
                Thread.sleep(150)
                if (process.isAlive) ready = true
            }
            Assume.assumeTrue(ready)
            val client = pinnedClient(cert)
            transport = CodeYantraRelayTransport(
                origin = CodeYantraRelayTransport.parseOrigin("https://127.0.0.1:8444"),
                client = client,
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


