package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.DefaultRelayProvider
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.core.network.ServicePolicyController
import com.codeyantra.armorchat.core.network.ServicePolicyTrust
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.identity.ContactQrV3
import com.codeyantra.armorchat.domain.identity.InviteToken
import com.codeyantra.armorchat.domain.identity.ParsedContactQr
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import kotlinx.coroutines.runBlocking
import org.junit.Test

class ContactQrV3FlowTest {
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
    fun oneScanV3EstablishesSessionWithoutConnectionQr() = runBlocking {
        val aliceOrch = orch()
        val bobOrch = orch()
        val alice = TinkSigner()
        val bob = TinkSigner()

        bobOrch.provisionInvite(bob)
        val token = InviteToken.generate()
        val bundle = bobOrch.publishInviteV3(bob, token, "neeraj-veltrix", "Neeraj")
        val qr = ContactQrV3(token).toQr()
        assertThat(ContactQrV3.parse(qr)?.token).isEqualTo(token)
        assertThat(qr).doesNotContain("connection")
        assertThat(bundle.id).isEqualTo(ArmorChatId.derive(Base64Url.decode(bob.pin)))

        val fetched = aliceOrch.lookupInviteV3(token)
        assertThat(fetched.handle).isEqualTo("neeraj-veltrix")
        assertThat(fetched.toJson().lowercase()).doesNotContain("submit_capability")
        val redeemed = aliceOrch.redeemInviteV3(token)
        assertThat(redeemed.bundle.id).isEqualTo(fetched.id)
        val imported = aliceOrch.importPeerContactV3(redeemed, alice)
        assertThat(imported).isInstanceOf(RouteImportResult.Imported::class.java)
        assertThat(imported).isNotInstanceOf(RouteImportResult.NeedsIdentityPin::class.java)

        confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)

        val aliceView = aliceOrch.sessionView(alice.pin, bob.pin)
        val bobView = bobOrch.sessionView(bob.pin, alice.pin)
        assertThat(aliceOrch.link(bob.pin)!!.peer.sessionId)
            .isEqualTo(bobOrch.link(alice.pin)!!.peer.sessionId)
        assertThat(aliceView.state).isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(bobView.state).isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(aliceView.composerAllowed).isTrue()
        assertThat(bobView.composerAllowed).isTrue()

        aliceOrch.sendText(bob.pin, "One scan ready")
        val received = bobOrch.sync(alice.pin)
        assertThat(received).contains("One scan ready")
    }

    @Test
    fun inviteRefreshReusesMailboxAndDoesNotChangeIdentity() = runBlocking {
        val bobOrch = orch()
        val bob = TinkSigner()
        val first = bobOrch.provisionInvite(bob)
        val mailbox = first.peer.mailbox!!.mailboxId
        val token1 = InviteToken.generate()
        val bundle1 = bobOrch.publishInviteV3(bob, token1, "neeraj-veltrix", "Neeraj")
        val token2 = InviteToken.generate()
        val bundle2 = bobOrch.publishInviteV3(bob, token2, "neeraj-veltrix", "Neeraj", previousToken = token1)
        val again = bobOrch.provisionInvite(bob)
        assertThat(again.peer.mailbox!!.mailboxId).isEqualTo(mailbox)
        assertThat(bundle2.id).isEqualTo(bundle1.id)
        assertThat(bundle2.publicKeyBase64Url).isEqualTo(bob.pin)
        assertThat(token2).isNotEqualTo(token1)
        assertThat(network.client().lookupInvite(token1)).isNull()
        assertThat(network.client().lookupInvite(token2)).isNotNull()
    }

    @Test
    fun sameKeyHandleRenameKeepsPinDifferentKeyDoesNotReplace() = runBlocking {
        val aliceOrch = orch()
        val bob = TinkSigner()
        val other = TinkSigner()
        aliceOrch.provisionInvite(TinkSigner())
        val token = InviteToken.generate()
        val bobOrch = orch()
        bobOrch.provisionInvite(bob)
        val original = bobOrch.publishInviteV3(bob, token, "neeraj-veltrix", "Neeraj")
        assertThat(original.handle).isEqualTo("neeraj-veltrix")
        val renamed = ContactBundleV3Holder.rename(bob, "neeraj", original)
        assertThat(renamed.publicKeyBase64Url).isEqualTo(original.publicKeyBase64Url)
        assertThat(renamed.id).isEqualTo(original.id)
        val otherOrch = orch()
        otherOrch.provisionInvite(other)
        val conflict = otherOrch.publishInviteV3(other, InviteToken.generate(), "neeraj-veltrix", "Neeraj")
        assertThat(conflict.publicKeyBase64Url).isNotEqualTo(original.publicKeyBase64Url)
        assertThat(conflict.handle).isEqualTo(original.handle)
        assertThat(ParsedContactQr.V3(conflict).armorChatId).isNotEqualTo(original.id)
    }

    @Test
    fun composerStaysDisabledUntilMlsReady() = runBlocking {
        val aliceOrch = orch()
        val alice = TinkSigner()
        val bob = TinkSigner()
        aliceOrch.provision(alice, bob.pin)
        val view = aliceOrch.sessionView(alice.pin, bob.pin)
        assertThat(view.state).isNotEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(view.composerAllowed).isFalse()
        assertThat(view.setupHint).isEqualTo("Securing chat…")
    }

    @Test
    fun restoreAfterV3ImportEnablesComposer() = runBlocking {
        val aliceOrch = orch()
        val bobOrch = orch()
        val alice = TinkSigner()
        val bob = TinkSigner()
        bobOrch.provisionInvite(bob)
        val token = InviteToken.generate()
        bobOrch.publishInviteV3(bob, token, "neeraj-veltrix", "Neeraj")
        val redemption = aliceOrch.redeemInviteV3(token)
        aliceOrch.importPeerContactV3(redemption, alice)
        confirmSharedMls(aliceOrch, bobOrch, alice.pin, bob.pin)

        val saved = aliceOrch.persist(bob.pin)!!
        val restored = orch()
        restored.restore(alice, saved, alice.pin)
        val view = restored.sessionView(alice.pin, bob.pin)
        assertThat(view.state).isEqualTo(SecureSessionState.ESTABLISHED)
        assertThat(view.composerAllowed).isTrue()
    }

    @Test
    fun fakeMlsIsAbsentFromMainSources() {
        val root = java.io.File("src/main/java/com/codeyantra/armorchat")
        assertThat(root.isDirectory).isTrue()
        val hits = mutableListOf<String>()
        root.walkTopDown().filter { it.extension == "kt" }.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (line.contains("FakeMls") || line.contains("object FakeMls")) {
                    hits += "${file.name}:${index + 1}"
                }
            }
        }
        assertThat(hits).isEmpty()
    }
}

private object ContactBundleV3Holder {
    fun rename(
        signer: IdentitySigner,
        handle: String,
        original: com.codeyantra.armorchat.domain.identity.ContactBundleV3,
    ): com.codeyantra.armorchat.domain.identity.ContactBundleV3 {
        return com.codeyantra.armorchat.domain.identity.ContactBundleV3.issue(
            signer = signer,
            token = InviteToken.generate(),
            handle = handle,
            displayName = original.displayName,
            relayOrigin = original.relayOrigin,
            mailboxId = original.mailboxId,
            deviceId = original.deviceId,
            nowEpochSeconds = original.issuedAt,
        )
    }
}
