package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.core.network.ServicePolicyTrust
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.identity.ContactQrV3
import com.codeyantra.armorchat.domain.identity.InviteToken
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test

class InvitePublishLifecycleTest {
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

    private val origin = "https://relay1.codeyantra.in"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(
        listOf(RelayNode("node-1", origin, RelayRole.BOOTSTRAP)),
    )
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val nowSeconds = { System.currentTimeMillis() / 1000 }

    private fun orch(relayProvider: com.codeyantra.armorchat.core.network.RelayProvider = provider) = SessionOrchestrator(
        registry = registry,
        provider = relayProvider,
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = MessagingLinkFactory { signer, relay, now, engine ->
            val peer = TwoPhonePeer(signer, relay, now, FakeMls::transact, engine)
            LiveLink(peer, ReceiptMessagingPeer(peer, now, jitter = { 0 }))
        },
        nowSeconds = nowSeconds,
    )

    @Test
    fun successfulPublish_makesInviteAvailableForImmediateLookup() = runBlocking {
        val aliceSigner = TinkSigner()
        val aliceOrch = orch()
        val token = InviteToken.generate()

        val publishedBundle = aliceOrch.publishInviteV3(
            signer = aliceSigner,
            token = token,
            handle = "alice",
            displayName = "Alice",
        )

        val bobOrch = orch()
        val lookedUpBundle = bobOrch.lookupInviteV3(token)

        assertThat(lookedUpBundle.id).isEqualTo(publishedBundle.id)
        assertThat(lookedUpBundle.publicKeyBase64Url).isEqualTo(publishedBundle.publicKeyBase64Url)
        assertThat(lookedUpBundle.inviteId).isEqualTo(publishedBundle.inviteId)
    }

    @Test
    fun displayedToken_equalsPublishedTokenLogically() = runBlocking {
        val aliceSigner = TinkSigner()
        val aliceOrch = orch()
        val token = InviteToken.generate()

        aliceOrch.publishInviteV3(
            signer = aliceSigner,
            token = token,
            handle = "alice",
            displayName = "Alice",
        )

        val qrString = ContactQrV3(token).toQr()
        val parsedToken = ContactQrV3.parse(qrString)!!.token

        val bobOrch = orch()
        val bundle = bobOrch.lookupInviteV3(parsedToken)
        assertThat(bundle.publicKeyBase64Url).isEqualTo(aliceSigner.pin)
    }

    @Test
    fun explicitRefresh_rotatesInvite_andRevokesPreviousInvite() = runBlocking {
        val aliceSigner = TinkSigner()
        val aliceOrch = orch()
        val token1 = InviteToken.generate()

        aliceOrch.publishInviteV3(
            signer = aliceSigner,
            token = token1,
            handle = "alice",
            displayName = "Alice",
        )

        val bobOrch = orch()
        assertThat(bobOrch.lookupInviteV3(token1)).isNotNull()

        // Explicit refresh
        val token2 = InviteToken.generate()
        aliceOrch.publishInviteV3(
            signer = aliceSigner,
            token = token2,
            handle = "alice",
            displayName = "Alice",
            previousToken = token1,
        )

        // Old token1 is now revoked
        val oldLookup = runCatching { bobOrch.lookupInviteV3(token1) }
        assertThat(oldLookup.isFailure).isTrue()

        // New token2 is lookupable
        val newLookup = bobOrch.lookupInviteV3(token2)
        assertThat(newLookup.publicKeyBase64Url).isEqualTo(aliceSigner.pin)
    }

    @Test
    fun publishAndLookup_useSameSelectedRelay() {
        val bootstrap = registry.bootstrap()
        assertThat(bootstrap.origin).isEqualTo("https://relay1.codeyantra.in")
    }
    @Test
    fun failedKeyPackagePublish_retryRetainsMailbox() = runBlocking {
        val delegate = network.client()
        var registrations = 0
        var fail = true
        val client = object : com.codeyantra.armorchat.core.network.RelayClient by delegate {
            override suspend fun registerMailbox(): com.codeyantra.armorchat.core.network.MailboxRegistration {
                registrations++
                return delegate.registerMailbox()
            }
            override suspend fun putKeyPackages(ownerToken: String, packagesJson: String) {
                if (fail) error("injected failure")
                delegate.putKeyPackages(ownerToken, packagesJson)
            }
        }
        val subject = orch(DefaultRelayProvider(registry) { client })
        val signer = TinkSigner()
        assertThat(runCatching { subject.provisionInvite(signer) }.isFailure).isTrue()
        val mailbox = subject.persist(SessionOrchestrator.INVITE_PEER)!!.mailbox
        assertThat(mailbox).isNotNull()
        fail = false
        subject.publishInviteV3(signer, InviteToken.generate(), "alice", "Alice")
        assertThat(registrations).isEqualTo(1)
        assertThat(subject.persist(SessionOrchestrator.INVITE_PEER)!!.mailbox).isEqualTo(mailbox)
    }

    @Test
    fun failedReplacementPublish_doesNotRevokeActiveInvite() = runBlocking {
        val signer = TinkSigner()
        val oldToken = InviteToken.generate()
        val delegate = network.client()
        var reject = false
        val client = object : com.codeyantra.armorchat.core.network.RelayClient by delegate {
            override suspend fun putInvite(ownerToken: String, token: String, bundleJson: String, expiresAt: Long): Boolean {
                return if (reject) false else delegate.putInvite(ownerToken, token, bundleJson, expiresAt)
            }
        }
        val subject = orch(DefaultRelayProvider(registry) { client })
        subject.publishInviteV3(signer, oldToken, "alice", "Alice")
        reject = true
        assertThat(runCatching {
            subject.publishInviteV3(signer, InviteToken.generate(), "alice", "Alice", oldToken)
        }.isFailure).isTrue()
        assertThat(subject.lookupInviteV3(oldToken).publicKeyBase64Url).isEqualTo(signer.pin)
    }

}
