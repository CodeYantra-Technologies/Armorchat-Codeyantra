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
import com.codeyantra.armorchat.domain.identity.ContactQrV3
import com.codeyantra.armorchat.domain.identity.InviteToken
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.AfterClass
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

/**
 * Real mls-rs JNI + local TLS relay for the compact v3 invite path.
 * Skipped unless `-PtwoPhoneTests` puts libarmorchat_mlsrs on java.library.path.
 */
class ContactQrV3MlsRsTest {
    private class TinkSigner : IdentitySigner {
        val handle: KeysetHandle
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

    private fun orch() = SessionOrchestrator(
        registry = DefaultRelayRegistry(
            listOf(RelayNode("local", "https://127.0.0.1:8445", RelayRole.BOOTSTRAP)),
        ),
        provider = DefaultRelayProvider(
            DefaultRelayRegistry(
                listOf(RelayNode("local", "https://127.0.0.1:8445", RelayRole.BOOTSTRAP)),
            ),
        ) { transport },
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = DefaultMessagingLinkFactory,
        nowSeconds = { System.currentTimeMillis() / 1000 },
    )

    @Test
    fun compactInviteLookupRedeemRealMlsEncryptDecryptReceipt() = runBlocking {
        val ownerOrch = orch()
        val aliceOrch = orch()
        val owner = TinkSigner()
        val alice = TinkSigner()
        ownerOrch.provisionInvite(owner)
        val token = InviteToken.generate()
        val bundle = ownerOrch.publishInviteV3(owner, token, "neeraj-veltrix", "Neeraj")
        val qr = ContactQrV3(token).toQr()
        assertThat(qr.length).isEqualTo(ContactQrV3.PREFIX.length + InviteToken.ENCODED_LENGTH)
        assertThat(bundle.toJson().lowercase()).doesNotContain("submit_capability")

        val looked = aliceOrch.lookupInviteV3(token)
        assertThat(looked.publicKeyBase64Url).isEqualTo(owner.pin)
        val redeemed = aliceOrch.redeemInviteV3(token)
        verifyKeyPackageBinding(redeemed.keyPackageBinding, owner.pin)
        assertThat(aliceOrch.importPeerContactV3(redeemed, alice))
            .isInstanceOf(RouteImportResult.Imported::class.java)
        confirmSharedMls(aliceOrch, ownerOrch, alice.pin, owner.pin)

        assertThat(aliceOrch.sessionView(alice.pin, owner.pin).state)
            .isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(ownerOrch.sessionView(owner.pin, alice.pin).composerAllowed).isTrue()

        val sent = aliceOrch.sendText(owner.pin, "Hello ArmorChat")
        val received = ownerOrch.sync(alice.pin)
        assertThat(received).contains("Hello ArmorChat")
        aliceOrch.sync(owner.pin)
        val status = aliceOrch.status(owner.pin, sent.messageId)
        assertThat(status).isEqualTo(MessageStatus.DELIVERED)

        val savedOwner = ownerOrch.persist(alice.pin)!!
        val restored = orch()
        restored.restore(owner, savedOwner, owner.pin)
        aliceOrch.sendText(owner.pin, "after persist")
        assertThat(restored.sync(alice.pin)).contains("after persist")
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
            work = Files.createTempDirectory("armorchat-v3-mlsrs").toFile()
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
            Assume.assumeTrue(process.isAlive)
            val client = pinnedClient(cert)
            transport = CodeYantraRelayTransport(
                origin = CodeYantraRelayTransport.parseOrigin("https://127.0.0.1:8445"),
                client = client,
            )
            var ready = false
            repeat(40) {
                try {
                    kotlinx.coroutines.runBlocking { transport.registerMailbox() }
                    ready = true
                    return@repeat
                } catch (_: Exception) {
                    Thread.sleep(150)
                }
            }
            Assume.assumeTrue(ready)
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
