package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.CodeYantraRelays
import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.NetworkPolicyState
import com.codeyantra.armorchat.core.network.NetworkPolicyVerifier
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.core.network.ServicePolicyTrust
import com.codeyantra.armorchat.core.json.CanonicalJson
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

class AutoSessionSetupTest {
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

    private val now = 1_800_000_000L
    private val origin = "https://relay-x.example"

    private fun harness(): Triple<SessionOrchestrator, SessionOrchestrator, InMemoryRelayNetwork> {
        val network = InMemoryRelayNetwork(origin)
        val registry = DefaultRelayRegistry(listOf(RelayNode("node-x", origin, RelayRole.BOOTSTRAP)))
        val provider = DefaultRelayProvider(registry) { network.client() }
        val factory = MessagingLinkFactory { signer, relay, clock, engine ->
            val peer = TwoPhonePeer(signer, relay, clock, FakeMls::transact, engine)
            LiveLink(peer, ReceiptMessagingPeer(peer, clock, jitter = { 0 }))
        }
        fun orch(trust: ServicePolicyTrust = ServicePolicyTrust()) = SessionOrchestrator(
            registry = registry,
            provider = provider,
            policy = ServicePolicyController(trust),
            factory = factory,
            nowSeconds = { now },
        )
        return Triple(orch(), orch(), network)
    }

    @Test
    fun automaticRouteAndSessionSetupDoesNotPasteSecrets() = runBlocking {
        val (aliceOrch, bobOrch, network) = harness()
        val alice = TinkSigner()
        val bob = TinkSigner()
        aliceOrch.provision(alice, bob.pin)
        bobOrch.provision(bob, alice.pin)
        val bobRoute = bobOrch.localRouteQr(alice.pin)!!
        assertThat(bobRoute).doesNotContain("private")
        val imported = aliceOrch.importPeerRoute(bobRoute, alice)
        assertThat(imported).isInstanceOf(RouteImportResult.Imported::class.java)
        confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)
        assertThat(aliceOrch.sessionView(alice.pin, bob.pin).composerAllowed).isTrue()
        assertThat(bobOrch.sessionView(bob.pin, alice.pin).composerAllowed).isTrue()
        assertThat(aliceOrch.sessionView(alice.pin, bob.pin).relayOrigin).isEqualTo(origin)
        assertThat(aliceOrch.sessionView(alice.pin, bob.pin).relayOrigin)
            .isNotEqualTo(CodeYantraRelays.NODE_1_ORIGIN)
        val sent = aliceOrch.sendText(bob.pin, "Hello ArmorChat")
        assertThat(sent.progress.status).isEqualTo(MessageStatus.SENT)
        val received = bobOrch.sync(alice.pin)
        assertThat(received).contains("Hello ArmorChat")
        aliceOrch.sync(bob.pin)
        assertThat(aliceOrch.status(bob.pin, sent.messageId)).isEqualTo(MessageStatus.DELIVERED)
        val inboundId = bobOrch.link(alice.pin)!!.receipts.lastAcceptedTextId()
        bobOrch.link(alice.pin)!!.receipts.markOpened(inboundId)
        bobOrch.flush(alice.pin)
        aliceOrch.sync(bob.pin)
        assertThat(aliceOrch.status(bob.pin, sent.messageId)).isEqualTo(MessageStatus.READ)
        unused(network)
    }

    @Test
    fun queuedMessagesSurviveSignedSuspensionAndResume() = runBlocking {
        SignatureConfig.register()
        val handle = KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
        val pub = (handle.publicKeysetHandle.primary.key as Ed25519PublicKey).publicKeyBytes.toByteArray()
        val trust = ServicePolicyTrust(Base64Url.encode(pub))
        val network = InMemoryRelayNetwork(origin)
        val registry = DefaultRelayRegistry(listOf(RelayNode("node-x", origin, RelayRole.BOOTSTRAP)))
        val provider = DefaultRelayProvider(registry) { network.client() }
        val factory = MessagingLinkFactory { signer, relay, clock, engine ->
            val peer = TwoPhonePeer(signer, relay, clock, FakeMls::transact, engine)
            LiveLink(peer, ReceiptMessagingPeer(peer, clock, jitter = { 0 }))
        }
        val policyA = ServicePolicyController(trust)
        val policyB = ServicePolicyController(trust)
        val aliceOrch = SessionOrchestrator(registry, provider, policyA, factory) { now }
        val bobOrch = SessionOrchestrator(registry, provider, policyB, factory) { now }
        val alice = TinkSigner()
        val bob = TinkSigner()
        aliceOrch.provision(alice, bob.pin)
        bobOrch.provision(bob, alice.pin)
        aliceOrch.importPeerRoute(bobOrch.localRouteQr(alice.pin)!!, alice)
        confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)

        network.suspended = true
        val queued = aliceOrch.sendText(bob.pin, "queued while relay 503")
        assertThat(queued.progress.status).isEqualTo(MessageStatus.QUEUED)

        val body = NetworkPolicyVerifier.body(NetworkPolicyState.SUSPENDED, "pol-s", now, now + 9_000)
        val framed = CanonicalJson.frame(com.codeyantra.armorchat.core.network.ServicePolicyDocuments.CONTEXT, body)
        val signature = Base64Url.encode(RawIdentitySignature.sign(handle, framed))
        val doc = NetworkPolicyVerifier.encode(body, signature)
        policyA.applySignedDocument(doc, now)
        policyB.applySignedDocument(doc, now)
        assertThat(aliceOrch.sessionView(alice.pin, bob.pin).composerAllowed).isFalse()
        aliceOrch.flush(bob.pin)
        assertThat(aliceOrch.status(bob.pin, queued.messageId)).isEqualTo(MessageStatus.QUEUED)

        val activeBody = NetworkPolicyVerifier.body(NetworkPolicyState.ACTIVE, "pol-a", now, now + 9_000)
        val activeFramed = CanonicalJson.frame(com.codeyantra.armorchat.core.network.ServicePolicyDocuments.CONTEXT, activeBody)
        val activeDoc = NetworkPolicyVerifier.encode(
            activeBody,
            Base64Url.encode(RawIdentitySignature.sign(handle, activeFramed)),
        )
        policyA.applySignedDocument(activeDoc, now)
        policyB.applySignedDocument(activeDoc, now)
        network.suspended = false
        aliceOrch.flush(bob.pin)
        assertThat(aliceOrch.status(bob.pin, queued.messageId)).isEqualTo(MessageStatus.SENT)
        val received = bobOrch.sync(alice.pin)
        assertThat(received).contains("queued while relay 503")
    }

    private fun unused(value: Any?) = Unit
}
