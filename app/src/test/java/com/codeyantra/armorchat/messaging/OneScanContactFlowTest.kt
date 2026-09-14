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
import com.codeyantra.armorchat.domain.identity.ContactQrV2
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test

class OneScanContactFlowTest {
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

    private val origin = "https://127.0.0.1:8444"
    private val network = InMemoryRelayNetwork(origin)
    private val registry = DefaultRelayRegistry(
        listOf(RelayNode("local", origin, RelayRole.BOOTSTRAP)),
    )
    private val provider = DefaultRelayProvider(registry) { network.client() }
    private val nowSeconds = { System.currentTimeMillis() / 1000 }

    private fun orch() = SessionOrchestrator(
        registry = registry,
        provider = provider,
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = MessagingLinkFactory { signer, relay, now, engine ->
            val peer = TwoPhonePeer(signer, relay, now, FakeMls::transact, engine)
            LiveLink(peer, ReceiptMessagingPeer(peer, now, jitter = { 0 }))
        },
        nowSeconds = nowSeconds,
    )

    @Test
    fun oneScanFlowEstablishesSecureSessionWithoutNeedsIdentityPin() = runBlocking {
        val aliceOrch = orch()
        val bobOrch = orch()

        val alice = TinkSigner()
        val bob = TinkSigner()

        // 1. Bob provisions local device & mailbox for contact Alice, exports Contact QR v2 ("My ArmorChat QR")
        val bobLink = bobOrch.provision(bob, alice.pin)
        val bobQrV2 = bobLink.peer.exportContactV2()
        val bobQrString = bobQrV2.toQr()

        // Verify Contact QR v2 structure
        val parsedV2 = ContactQrV2.parse(bobQrString, nowSeconds(), registry)
        assertThat(parsedV2.publicKeyBase64Url).isEqualTo(bob.pin)
        assertThat(parsedV2.relayOrigin).isEqualTo(origin)

        // 2. Alice imports Bob's Contact QR v2 in ONE SCAN (auto-provisions Alice's link for Bob)
        val importResult = aliceOrch.importPeerContactV2(parsedV2, alice)

        // Confirm import succeeded without returning NeedsIdentityPin!
        assertThat(importResult).isInstanceOf(RouteImportResult.Imported::class.java)
        val imported = importResult as RouteImportResult.Imported
        assertThat(imported.peerPin).isEqualTo(bob.pin)

        confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)

        // 4. Verify both sessions transition to ESTABLISHED (READY)
        val aliceView = aliceOrch.sessionView(alice.pin, bob.pin)
        val bobView = bobOrch.sessionView(bob.pin, alice.pin)

        assertThat(aliceView.state).isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(bobView.state).isEqualTo(SecureSessionState.ESTABLISHED)

        // Verify composer is unlocked on both sides
        assertThat(aliceView.composerAllowed).isTrue()
        assertThat(bobView.composerAllowed).isTrue()

        // 5. Alice sends text message and Bob receives it
        aliceOrch.sendText(bob.pin, "One-scan secure chat working!")
        val received = bobOrch.sync(alice.pin)
        assertThat(received).contains("One-scan secure chat working!")
    }

    @Test
    fun mailboxRegistrationIsReusedOnMultipleQRExports() = runBlocking {
        val bobOrch = orch()
        val bob = TinkSigner()
        val alicePin = "alice-pin-1234567890123456789012345678901"

        val link1 = bobOrch.provision(bob, alicePin)
        val mailboxId1 = link1.peer.mailbox?.mailboxId

        val qr1 = link1.peer.exportContactV2()

        // Exporting again reuses the same mailbox ID
        val link2 = bobOrch.provision(bob, alicePin)
        val mailboxId2 = link2.peer.mailbox?.mailboxId

        val qr2 = link2.peer.exportContactV2()

        assertThat(mailboxId1).isNotNull()
        assertThat(mailboxId2).isEqualTo(mailboxId1)
        assertThat(qr1.mailboxId).isEqualTo(qr2.mailboxId)
    }
}
