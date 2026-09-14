package com.codeyantra.armorchat.domain.identity

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
import com.codeyantra.armorchat.core.network.InMemoryRelayNetwork
import com.codeyantra.armorchat.core.network.RelayNode
import com.codeyantra.armorchat.core.network.RelayRole
import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.messaging.ArmorChatContexts
import com.codeyantra.armorchat.messaging.IdentitySigner
import com.google.common.truth.Truth.assertThat
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import org.junit.Assert.assertThrows
import org.junit.Test

class ContactQrV3Test {
    private class TestSigner : IdentitySigner {
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

    private val signer = TestSigner()
    private val origin = "https://127.0.0.1:8444"
    private val registry = DefaultRelayRegistry(
        listOf(RelayNode("local", origin, RelayRole.BOOTSTRAP)),
    )
    private val now = 1_700_000_000L

    private fun validKeyPackageBinding(): String {
        val body = mapOf(
            "identity_key" to signer.pin,
            "key_package" to "kp1",
            "suite" to "ac-mls-v1",
            "v" to 1L,
        )
        val framed = CanonicalJson.frame(ArmorChatContexts.KEY_PACKAGE, body)
        val signature = Base64Url.encode(signer.sign(ArmorChatContexts.KEY_PACKAGE, framed))
        return CanonicalJson.of(mapOf("body" to body, "signature" to signature))
    }

    private fun validBundle(token: String = InviteToken.generate()): ContactBundleV3 =
        ContactBundleV3.issue(
            signer = signer,
            token = token,
            handle = "neeraj-veltrix",
            displayName = "Neeraj",
            relayOrigin = origin,
            mailboxId = "mbx-1234567890123456789012345678901",
            deviceId = "dev-123456789012",
            nowEpochSeconds = now,
        )

    @Test
    fun tokenHas256BitsAndCanonicalEncoding() {
        val token = InviteToken.generate()
        assertThat(token).hasLength(InviteToken.ENCODED_LENGTH)
        assertThat(InviteToken.parseCanonical(token)).isEqualTo(token)
        assertThat(InviteToken.ENTROPY_BITS).isEqualTo(256)
        val tokens = (1..40).map { InviteToken.generate() }.toSet()
        assertThat(tokens).hasSize(40)
    }

    @Test
    fun compactQrIsSparseAndOmitsSecrets() {
        val token = InviteToken.generate()
        val qr = ContactQrV3(token).toQr()
        assertThat(qr).isEqualTo("armorchat://add/$token")
        assertThat(qr.length).isEqualTo(ContactQrV3.PREFIX.length + InviteToken.ENCODED_LENGTH)
        assertThat(qr.lowercase()).doesNotContain("owner_token")
        assertThat(qr.lowercase()).doesNotContain("private")
        assertThat(qr).doesNotContain(signer.pin)
        assertThat(qr).doesNotContain("ac1:")
        assertThat(ContactQrV3.parse(qr)?.token).isEqualTo(token)
    }

    @Test
    fun v3QrIsMuchSmallerThanV2() {
        val v2 = ContactQrV2.issue(
            signer = signer,
            relayOrigin = origin,
            mailboxId = "mbx-1234567890123456789012345678901",
            submitCapability = "sub-1234567890123456789012345678901",
            deviceId = "dev-123456789012",
            keyPackageBinding = validKeyPackageBinding(),
            nowEpochSeconds = now,
        ).toQr()
        val v3 = ContactQrV3(InviteToken.generate()).toQr()
        assertThat(v3.length).isLessThan(80)
        assertThat(v3.length).isLessThan(v2.length / 4)
    }

    @Test
    fun malformedTokensAreRejected() {
        assertThat(ContactQrV3.parse("armorchat://add/short")).isNull()
        assertThat(ContactQrV3.parse("armorchat://add/")).isNull()
        assertThat(ContactQrV3.parse("https://evil.example/add/aaaa")).isNull()
        assertThat(InviteToken.isCanonical("++++not-valid-base64url-token-value+++++")).isFalse()
        assertThrows(IllegalArgumentException::class.java) {
            InviteToken.parseCanonical("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa")
        }
    }

    @Test
    fun signedBundleVerifiesAndRecomputesArmorChatId() {
        val token = InviteToken.generate()
        val bundle = validBundle(token)
        val parsed = ContactBundleV3.parse(bundle.toJson(), token, now, registry)
        assertThat(parsed.id).isEqualTo(ArmorChatId.derive(Base64Url.decode(signer.pin)))
        assertThat(parsed.handle).isEqualTo("neeraj-veltrix")
        assertThat(parsed.displayName).isEqualTo("Neeraj")
        assertThat(parsed.containsForbiddenSecrets()).isFalse()
        assertThat(parsed.toJson().lowercase()).doesNotContain("owner_token")
        assertThat(parsed.toJson().lowercase()).doesNotContain("submit_capability")
        assertThat(parsed.toJson()).doesNotContain("key_package")
    }

    @Test
    fun expiredAndUnknownInvitesFail() {
        val token = InviteToken.generate()
        val bundle = validBundle(token)
        assertThrows(IllegalArgumentException::class.java) {
            ContactBundleV3.parse(bundle.toJson(), token, bundle.expiresAt + 1, registry)
        }
        val other = InviteToken.generate()
        assertThrows(IllegalArgumentException::class.java) {
            ContactBundleV3.parse(bundle.toJson(), other, now, registry)
        }
    }

    @Test
    fun tamperedFieldsFailVerification() {
        val token = InviteToken.generate()
        val json = validBundle(token).toJson()
        fun mustFail(mutated: String) {
            assertThrows(Exception::class.java) {
                ContactBundleV3.parse(mutated, token, now, registry)
            }
        }
        mustFail(json.replace("neeraj-veltrix", "attacker-name"))
        mustFail(json.replace("Neeraj", "Mallory"))
        mustFail(json.replace("\"pk\":\"${signer.pin}\"", "\"pk\":\"AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\""))
        val otherId = ArmorChatId.derive(ByteArray(32) { 9 })
        mustFail(json.replace(ArmorChatId.derive(Base64Url.decode(signer.pin)), otherId))
        mustFail(json.replace(origin, "https://attacker.example.com"))
        mustFail(json.replace("mbx-1234567890123456789012345678901", "mbx-attacker99999999999999999999"))
    }

    @Test
    fun parserPrefersCompactV3() {
        val qr = ContactQrV3(InviteToken.generate()).toQr()
        val parsed = ContactQrParser.parse(qr, now, registry).getOrThrow()
        assertThat(parsed).isInstanceOf(ParsedContactQr.V3Invite::class.java)
    }
}
