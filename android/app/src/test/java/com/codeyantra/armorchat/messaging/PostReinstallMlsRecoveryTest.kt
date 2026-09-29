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
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test

class PostReinstallMlsRecoveryTest {
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
            return RawIdentitySignature.sign(handle, bodyCanonical)
        }
    }

    private val origin = "https://relay-x.example"
    private var now = 1_800_000_000L

    private fun orch(network: InMemoryRelayNetwork) = SessionOrchestrator(
        registry = DefaultRelayRegistry(listOf(RelayNode("node-x", origin, RelayRole.BOOTSTRAP))),
        provider = DefaultRelayProvider(
            DefaultRelayRegistry(listOf(RelayNode("node-x", origin, RelayRole.BOOTSTRAP))),
        ) { network.client() },
        policy = ServicePolicyController(ServicePolicyTrust()),
        factory = MessagingLinkFactory { signer, relay, clock, engine ->
            val peer = TwoPhonePeer(signer, relay, clock, FakeMls::transact, engine)
            LiveLink(peer, ReceiptMessagingPeer(peer, clock, jitter = { 0 }))
        },
        nowSeconds = { now },
    )

    @Test
    fun emptySnapshotWithPinnedRouteCreatesFreshGroup() = runBlocking {
        val network = InMemoryRelayNetwork(origin)
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceLive = orch(network)
        val bobLive = orch(network)
        aliceLive.provision(alice, bob.pin)
        bobLive.provision(bob, alice.pin)
        aliceLive.importPeerRoute(bobLive.localRouteQr(alice.pin)!!, alice)
        confirmSharedMls(aliceLive, bobLive, alice.pin, bob.pin)
        assertThat(aliceLive.sessionView(alice.pin, bob.pin).state).isEqualTo(SecureSessionState.ESTABLISHED)

        val savedAlice = aliceLive.persist(bob.pin)!!
        val savedBob = bobLive.persist(alice.pin)!!
        val staleAlice = savedAlice.copy(mlsSnapshot = ByteArray(0), groupCreated = false, sessionId = null)
        val staleBob = savedBob.copy(mlsSnapshot = ByteArray(0), groupCreated = false, sessionId = null)

        val aliceRestored = orch(network)
        val bobRestored = orch(network)
        aliceRestored.restore(alice, staleAlice, alice.pin)
        bobRestored.restore(bob, staleBob, bob.pin)
        assertThat(aliceRestored.sessionView(alice.pin, bob.pin).state)
            .isEqualTo(SecureSessionState.ESTABLISHING)

        aliceRestored.link(bob.pin)!!.peer.markPeerKeyPackageFresh()
        confirmSharedMls(aliceRestored, bobRestored, alice.pin, bob.pin)
        assertThat(aliceRestored.sessionView(alice.pin, bob.pin).state)
            .isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(bobRestored.sessionView(bob.pin, alice.pin).state)
            .isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(aliceRestored.sessionView(alice.pin, bob.pin).composerAllowed).isTrue()
        assertThat(aliceRestored.link(bob.pin)!!.peer.sessionId)
            .isEqualTo(bobRestored.link(alice.pin)!!.peer.sessionId)

        val sent = aliceRestored.sendText(bob.pin, "post-reinstall-A")
        assertThat(sent.progress.status).isEqualTo(MessageStatus.SENT)
        val received = bobRestored.sync(alice.pin)
        assertThat(received).contains("post-reinstall-A")
    }

    @Test
    fun unconfirmedGroupIsNotTornDownWhilePeerIsSlow() = runBlocking {
        val network = InMemoryRelayNetwork(origin)
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceOrch = orch(network)
        val bobOrch = orch(network)
        aliceOrch.provision(alice, bob.pin)
        bobOrch.provision(bob, alice.pin)
        aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
        aliceOrch.establish(bob.pin)
        val aliceSession = aliceOrch.link(bob.pin)!!.peer.sessionId
        assertThat(aliceSession).isNotNull()

        // Peer stays offline past the establish backoff while the UI / keep-alive
        // loop keeps calling establish(). The pending Welcome must stay valid.
        now += 16
        aliceOrch.establish(bob.pin)
        now += 16
        aliceOrch.establish(bob.pin)
        assertThat(aliceOrch.link(bob.pin)!!.peer.sessionId).isEqualTo(aliceSession)

        bobOrch.tryAdoptInvite(alice.pin)
        bobOrch.sync(alice.pin)
        val bobSession = bobOrch.link(alice.pin)!!.peer.sessionId
        // Joiner opens the chat (establish) before the initiator has answered.
        now += 16
        bobOrch.establish(alice.pin)
        assertThat(bobOrch.link(alice.pin)!!.peer.groupCreated).isTrue()
        assertThat(bobOrch.link(alice.pin)!!.peer.sessionId).isEqualTo(bobSession)

        aliceOrch.sync(bob.pin)
        bobOrch.sync(alice.pin)
        aliceOrch.sync(bob.pin)
        bobOrch.sync(alice.pin)
        assertThat(aliceOrch.sessionView(alice.pin, bob.pin).state)
            .isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(bobOrch.sessionView(bob.pin, alice.pin).state)
            .isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(aliceOrch.sessionView(alice.pin, bob.pin).composerAllowed).isTrue()
        assertThat(bobOrch.sessionView(bob.pin, alice.pin).composerAllowed).isTrue()
        assertThat(aliceOrch.link(bob.pin)!!.peer.sessionId)
            .isEqualTo(bobOrch.link(alice.pin)!!.peer.sessionId)

        val sent = aliceOrch.sendText(bob.pin, "after-slow-peer")
        assertThat(sent.progress.status).isEqualTo(MessageStatus.SENT)
        assertThat(bobOrch.sync(alice.pin)).contains("after-slow-peer")
    }

    @Test
    fun restoredGroupFlagWithoutSnapshotIsUnusable() = runBlocking {
        val network = InMemoryRelayNetwork(origin)
        val alice = TinkSigner()
        val bob = TinkSigner()
        val aliceLive = orch(network)
        val bobLive = orch(network)
        aliceLive.provision(alice, bob.pin)
        bobLive.provision(bob, alice.pin)
        aliceLive.importPeerRoute(bobLive.localRouteQr(alice.pin)!!, alice)
        aliceLive.establish(bob.pin)
        val saved = aliceLive.persist(bob.pin)!!.copy(mlsSnapshot = ByteArray(0), groupCreated = true)
        val restored = orch(network)
        restored.restore(alice, saved, alice.pin)
        assertThat(restored.sessionView(alice.pin, bob.pin).state)
            .isNotEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(restored.link(bob.pin)!!.peer.groupCreated).isFalse()
    }
}
