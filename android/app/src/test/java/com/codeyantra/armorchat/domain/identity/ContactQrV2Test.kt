package com.codeyantra.armorchat.domain.identity

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.network.DefaultRelayRegistry
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

class ContactQrV2Test {
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

    private fun validV2(): ContactQrV2 = ContactQrV2.issue(
        signer = signer,
        relayOrigin = origin,
        mailboxId = "mbx-1234567890123456789012345678901",
        submitCapability = "sub-1234567890123456789012345678901",
        deviceId = "dev-123456789012",
        keyPackageBinding = validKeyPackageBinding(),
        nowEpochSeconds = now,
    )

    @Test
    fun validV2PayloadParsesAndVerifiesSuccessfully() {
        val v2 = validV2()
        val json = v2.toQr()
        val parsed = ContactQrV2.parse(json, now, registry)
        assertThat(parsed.id).isEqualTo(v2.id)
        assertThat(parsed.publicKeyBase64Url).isEqualTo(signer.pin)
        assertThat(parsed.relayOrigin).isEqualTo(origin)
        assertThat(parsed.mailboxId).isEqualTo(v2.mailboxId)
        assertThat(parsed.submitCapability).isEqualTo(v2.submitCapability)
    }

    @Test
    fun unifiedParserSupportsV1AndV2() {
        val v2 = validV2()
        val parsedV2 = ContactQrParser.parse(v2.toQr(), now, registry).getOrThrow()
        assertThat(parsedV2).isInstanceOf(ParsedContactQr.V2::class.java)
        assertThat(parsedV2.armorChatId).isEqualTo(v2.id)

        val v1 = IdentityQrPayload.fromPublicKey(Base64Url.decode(signer.pin))
        val parsedV1 = ContactQrParser.parse(IdentityQrPayload.serialize(v1), now, registry).getOrThrow()
        assertThat(parsedV1).isInstanceOf(ParsedContactQr.V1::class.java)
        assertThat(parsedV1.armorChatId).isEqualTo(v1.id)
    }

    @Test
    fun tamperedRelayOriginFailsSignatureVerification() {
        val v2 = validV2()
        val tamperedJson = v2.toQr().replace(origin, "https://attacker.example.com")
        assertThrows(Exception::class.java) {
            ContactQrV2.parse(tamperedJson, now, registry)
        }
    }

    @Test
    fun tamperedMailboxIdFailsSignatureVerification() {
        val v2 = validV2()
        val tamperedJson = v2.toQr().replace(v2.mailboxId, "mbx-attacker99999999999999999999")
        assertThrows(Exception::class.java) {
            ContactQrV2.parse(tamperedJson, now, registry)
        }
    }

    @Test
    fun tamperedSubmitCapabilityFailsSignatureVerification() {
        val v2 = validV2()
        val tamperedJson = v2.toQr().replace(v2.submitCapability, "sub-attacker99999999999999999999")
        assertThrows(Exception::class.java) {
            ContactQrV2.parse(tamperedJson, now, registry)
        }
    }

    @Test
    fun mismatchedArmorChatIdRejected() {
        val v2 = validV2()
        val fakeId = "ac1:JJZM82FNPH31PBRV5MHD44BVX722V6ZN"
        val tamperedJson = v2.toQr().replace(v2.id, fakeId)
        assertThrows(Exception::class.java) {
            ContactQrV2.parse(tamperedJson, now, registry)
        }
    }

    @Test
    fun expiredQrRejected() {
        val v2 = validV2()
        val json = v2.toQr()
        val future = v2.expiresAt + 1000L
        assertThrows(IllegalArgumentException::class.java) {
            ContactQrV2.parse(json, future, registry)
        }
    }

    @Test
    fun unauthorizedRelayOriginRejected() {
        val v2 = validV2()
        val unauthorizedRegistry = DefaultRelayRegistry(
            listOf(RelayNode("other", "https://unauthorized.example.com", RelayRole.BOOTSTRAP)),
        )
        assertThrows(IllegalArgumentException::class.java) {
            ContactQrV2.parse(v2.toQr(), now, unauthorizedRegistry)
        }
    }

    @Test
    fun qrPayloadDoesNotContainSecrets() {
        val v2 = validV2()
        val json = v2.toQr().lowercase()
        assertThat(json).doesNotContain("private_key")
        assertThat(json).doesNotContain("owner_token")
        assertThat(json).doesNotContain("secret")
        assertThat(json).doesNotContain("keystore")
    }
}
