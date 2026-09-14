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

class MultiPeerInviteTest {
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
    fun sameQrScannedByThreePeersCreatesIndependentSessions() = runBlocking {
        val ownerOrch = orch()
        val owner = TinkSigner()
        ownerOrch.provisionInvite(owner)
        val token = InviteToken.generate()
        ownerOrch.publishInviteV3(owner, token, "neeraj-veltrix", "Neeraj")
        val qr = ContactQrV3(token).toQr()
        assertThat(qr).isEqualTo("armorchat://add/$token")
        assertThat(qr.length).isEqualTo(ContactQrV3.PREFIX.length + InviteToken.ENCODED_LENGTH)
        assertThat(qr).doesNotContain("connection")
        assertThat(qr.lowercase()).doesNotContain("keypackage")
        assertThat(qr.lowercase()).doesNotContain("refresh")

        val peers = List(3) { TinkSigner() }
        val orchs = List(3) { orch() }
        val bindings = mutableSetOf<String>()
        val grants = mutableSetOf<String>()
        for (i in peers.indices) {
            assertThat(ContactQrV3.parse(qr)?.token).isEqualTo(token)
            val redemption = orchs[i].redeemInviteV3(token)
            bindings += redemption.keyPackageBinding
            grants += redemption.bootstrapGrant.token
            assertThat(redemption.bundle.toJson().lowercase()).doesNotContain("submit_capability")
            assertThat(redemption.bootstrapGrant.kind).isEqualTo("bootstrap")
            val imported = orchs[i].importPeerContactV3(redemption, peers[i])
            assertThat(imported).isInstanceOf(RouteImportResult.Imported::class.java)
            confirmSharedMls(orchs[i], ownerOrch, peers[i].pin, owner.pin)
            val view = orchs[i].sessionView(peers[i].pin, owner.pin)
            val ownerView = ownerOrch.sessionView(owner.pin, peers[i].pin)
            assertThat(view.state).isEqualTo(SecureSessionState.ESTABLISHED)
            assertThat(ownerView.state).isEqualTo(SecureSessionState.ESTABLISHED)
            assertThat(view.composerAllowed).isTrue()
            assertThat(ownerView.composerAllowed).isTrue()
            orchs[i].sendText(owner.pin, "hello from $i")
            assertThat(ownerOrch.sync(peers[i].pin)).contains("hello from $i")
        }
        assertThat(bindings).hasSize(3)
        assertThat(grants).hasSize(3)

        orchs[0].sendText(owner.pin, "private-0")
        val leaked = ownerOrch.sync(peers[1].pin)
        assertThat(leaked).doesNotContain("private-0")
        assertThat(orchs[1].sessionView(peers[1].pin, owner.pin).state)
            .isEqualTo(SecureSessionState.ESTABLISHED)
    }

    @Test
    fun onePeerCannotImpersonateAnotherOnImport() = runBlocking {
        val ownerOrch = orch()
        val owner = TinkSigner()
        ownerOrch.provisionInvite(owner)
        val token = InviteToken.generate()
        ownerOrch.publishInviteV3(owner, token, "neeraj-veltrix", "Neeraj")
        val alice = TinkSigner()
        val mallory = TinkSigner()
        val aliceOrch = orch()
        val malloryOrch = orch()
        val aliceRedemption = aliceOrch.redeemInviteV3(token)
        val malloryRedemption = malloryOrch.redeemInviteV3(token)
        assertThat(aliceRedemption.keyPackageBinding).isNotEqualTo(malloryRedemption.keyPackageBinding)
        assertThat(aliceRedemption.bootstrapGrant.token).isNotEqualTo(malloryRedemption.bootstrapGrant.token)
        aliceOrch.importPeerContactV3(aliceRedemption, alice)
        malloryOrch.importPeerContactV3(malloryRedemption, mallory)
        confirmSharedMls(aliceOrch, ownerOrch, alice.pin, owner.pin)
        confirmSharedMls(malloryOrch, ownerOrch, mallory.pin, owner.pin)
        val aliceSid = aliceOrch.link(owner.pin)!!.peer.sessionId
        val mallorySid = malloryOrch.link(owner.pin)!!.peer.sessionId
        assertThat(aliceSid).isNotEmpty()
        assertThat(mallorySid).isNotEqualTo(aliceSid)
        aliceOrch.sendText(owner.pin, "only-alice")
        assertThat(ownerOrch.sync(alice.pin)).contains("only-alice")
        assertThat(ownerOrch.sync(mallory.pin)).doesNotContain("only-alice")
    }

    @Test
    fun revokedInviteCannotBeRedeemedAndDoesNotForceQrRefreshBetweenLivePeers() = runBlocking {
        val ownerOrch = orch()
        val owner = TinkSigner()
        ownerOrch.provisionInvite(owner)
        val token = InviteToken.generate()
        ownerOrch.publishInviteV3(owner, token, "neeraj-veltrix", "Neeraj")
        val aliceOrch = orch()
        val alice = TinkSigner()
        val redemption = aliceOrch.redeemInviteV3(token)
        aliceOrch.importPeerContactV3(redemption, alice)
        confirmSharedMls(aliceOrch, ownerOrch, alice.pin, owner.pin)
        network.client().revokeInvite(
            ownerOrch.link(SessionOrchestrator.INVITE_PEER)!!.peer.mailbox!!.ownerToken,
            token,
        )
        val bobOrch = orch()
        val bob = TinkSigner()
        val failed = runCatching { bobOrch.redeemInviteV3(token) }
        assertThat(failed.isFailure).isTrue()
        assertThat(aliceOrch.sessionView(alice.pin, owner.pin).composerAllowed).isTrue()
        aliceOrch.sendText(owner.pin, "still-live")
        assertThat(ownerOrch.sync(alice.pin)).contains("still-live")
    }

    @Test
    fun compactQrNeedsNoSecondScan() = runBlocking {
        val ownerOrch = orch()
        val owner = TinkSigner()
        ownerOrch.provisionInvite(owner)
        val token = InviteToken.generate()
        ownerOrch.publishInviteV3(owner, token, "neeraj-veltrix", "Neeraj")
        val qr = ContactQrV3(token).toQr()
        val aliceOrch = orch()
        val alice = TinkSigner()
        val redemption = aliceOrch.redeemInviteV3(ContactQrV3.parse(qr)!!.token)
        aliceOrch.importPeerContactV3(redemption, alice)
        confirmSharedMls(aliceOrch, ownerOrch, alice.pin, owner.pin)
        assertThat(aliceOrch.sessionView(alice.pin, owner.pin).localRouteQr).isNull()
        assertThat(aliceOrch.sessionView(alice.pin, owner.pin).setupHint).isNull()
        assertThat(aliceOrch.sessionView(alice.pin, owner.pin).composerAllowed).isTrue()
    }
}
