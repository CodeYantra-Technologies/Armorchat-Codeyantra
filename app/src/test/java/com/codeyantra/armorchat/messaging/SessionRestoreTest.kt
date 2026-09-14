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

class SessionRestoreTest {
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
    fun v2BootstrapIsPreservedAcrossRestore() = runBlocking {
        val aliceOrch = orch()
        val bobOrch = orch()

        val alice = TinkSigner()
        val bob = TinkSigner()

        // 1. Bob generates V2 QR
        val bobLink = bobOrch.provision(bob, alice.pin)
        val bobQrV2 = bobLink.peer.exportContactV2()

        // 2. Alice imports Bob's Contact QR v2
        aliceOrch.importPeerContactV2(bobQrV2, alice)
        confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
        
        // 3. Alice persists the session
        val persisted = aliceOrch.persist(bob.pin)
        assertThat(persisted).isNotNull()
        assertThat(persisted!!.peerRouteQr).isNotNull()
        
        // 4. Simulate restart: new orchestrator restores the persisted state
        val restoredOrch = orch()
        restoredOrch.restore(alice, persisted, alice.pin)
        
        // 5. Verify the restored session is ESTABLISHED
        val view = restoredOrch.sessionView(alice.pin, bob.pin)
        assertThat(view.state).isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(view.composerAllowed).isTrue()
    }
}
