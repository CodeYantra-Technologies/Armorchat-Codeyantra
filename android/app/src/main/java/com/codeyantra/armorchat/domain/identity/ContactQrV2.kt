package com.codeyantra.armorchat.domain.identity

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.network.CodeYantraRelayTransport
import com.codeyantra.armorchat.core.network.RelayRegistry
import com.codeyantra.armorchat.messaging.ArmorChatContexts
import com.codeyantra.armorchat.messaging.IdentitySigner
import com.codeyantra.armorchat.messaging.verifyEd25519
import com.codeyantra.armorchat.messaging.verifyKeyPackageBinding

/**
 * Versioned Contact QR Payload v2 for one-scan ArmorChat contact exchange.
 *
 * Cryptographically binds identity, relay origin, mailbox, submit capability,
 * and KeyPackage under Ed25519 identity signature [ArmorChatContexts.CONTACT_V2].
 */
data class ContactQrV2(
    val id: String,
    val publicKeyBase64Url: String,
    val relayOrigin: String,
    val mailboxId: String,
    val submitCapability: String,
    val deviceId: String,
    val keyPackageBinding: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val signature: String,
    val suite: String = SUITE,
    val version: Int = VERSION,
) {
    fun toQr(): String {
        val signed = mapOf("body" to bodyMap(), "signature" to signature)
        return CanonicalJson.of(mapOf("p" to PRODUCT, "contact" to signed, "v" to VERSION.toLong()))
    }

    fun bodyMap(): Map<String, Any?> = mapOf(
        "applies_to" to APPLIES_TO,
        "device_id" to deviceId,
        "expires_at" to expiresAt,
        "id" to id,
        "issued_at" to issuedAt,
        "key_package_binding" to keyPackageBinding,
        "mailbox_id" to mailboxId,
        "pk" to publicKeyBase64Url,
        "relay_origin" to relayOrigin,
        "submit_capability" to submitCapability,
        "suite" to suite,
        "v" to VERSION.toLong(),
    )

    companion object {
        const val PRODUCT = "armorchat"
        const val VERSION = 2
        const val SUITE = "ac-mls-v1"
        const val APPLIES_TO = "armorchat-contact-v2"
        const val MAX_BYTES = 8192

        fun issue(
            signer: IdentitySigner,
            relayOrigin: String,
            mailboxId: String,
            submitCapability: String,
            deviceId: String,
            keyPackageBinding: String,
            nowEpochSeconds: Long,
            ttlSeconds: Long = 30L * 24 * 3600,
        ): ContactQrV2 {
            val normalizedOrigin = CodeYantraRelayTransport.parseOrigin(relayOrigin).toString().trimEnd('/')
            val pk = signer.pin
            val id = ArmorChatId.derive(Base64Url.decode(pk))
            val issuedAt = nowEpochSeconds
            val expiresAt = nowEpochSeconds + ttlSeconds
            val body = mapOf(
                "applies_to" to APPLIES_TO,
                "device_id" to deviceId,
                "expires_at" to expiresAt,
                "id" to id,
                "issued_at" to issuedAt,
                "key_package_binding" to keyPackageBinding,
                "mailbox_id" to mailboxId,
                "pk" to pk,
                "relay_origin" to normalizedOrigin,
                "submit_capability" to submitCapability,
                "suite" to SUITE,
                "v" to VERSION.toLong(),
            )
            val framed = CanonicalJson.frame(ArmorChatContexts.CONTACT_V2, body)
            val signature = Base64Url.encode(signer.sign(ArmorChatContexts.CONTACT_V2, framed))
            return ContactQrV2(
                id = id,
                publicKeyBase64Url = pk,
                relayOrigin = normalizedOrigin,
                mailboxId = mailboxId,
                submitCapability = submitCapability,
                deviceId = deviceId,
                keyPackageBinding = keyPackageBinding,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                signature = signature,
            )
        }

        fun parse(
            raw: String,
            nowEpochSeconds: Long,
            relayRegistry: RelayRegistry? = null,
        ): ContactQrV2 {
            require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "QR payload exceeds size limit"
            }
            val root = CanonicalJson.parseObject(raw)
            require(root["p"] == PRODUCT) { "Not an armorchat QR" }
            require((root["v"] as Long) == VERSION.toLong()) { "Not a v2 contact QR" }
            @Suppress("UNCHECKED_CAST")
            val signed = root["contact"] as? Map<String, Any?> ?: error("Missing contact signature envelope")
            @Suppress("UNCHECKED_CAST")
            val body = signed["body"] as? Map<String, Any?> ?: error("Missing contact body")

            require((body["v"] as Long) == VERSION.toLong() && body["suite"] == SUITE) {
                "Unsupported version or suite"
            }
            require(body["applies_to"] == APPLIES_TO) { "Invalid applies_to" }

            val pk = body["pk"] as? String ?: error("Missing pk")
            val id = body["id"] as? String ?: error("Missing id")

            val pubKeyBytes = Base64Url.decode(pk)
            require(pubKeyBytes.size == ArmorChatId.PUBLIC_KEY_LENGTH) { "Invalid public key size" }
            require(org.bouncycastle.math.ec.rfc8032.Ed25519.validatePublicKeyFull(pubKeyBytes, 0)) {
                "Invalid Ed25519 public key"
            }

            val recomputedId = ArmorChatId.derive(pubKeyBytes)
            require(recomputedId == id) { "ArmorChat ID does not match public identity key" }

            val expiresAt = body["expires_at"] as Long
            require(expiresAt > nowEpochSeconds) { "QR payload expired" }

            val relayOrigin = body["relay_origin"] as String
            CodeYantraRelayTransport.parseOrigin(relayOrigin)
            if (relayRegistry != null) {
                require(relayRegistry.isAuthorizedOrigin(relayOrigin)) { "Unauthorized relay origin" }
            }

            val keyPackageBinding = body["key_package_binding"] as String
            verifyKeyPackageBinding(keyPackageBinding, pk)

            val framed = CanonicalJson.frame(ArmorChatContexts.CONTACT_V2, body)
            val signature = signed["signature"] as? String ?: error("Missing signature")
            verifyEd25519(pk, framed, Base64Url.decode(signature))

            return ContactQrV2(
                id = id,
                publicKeyBase64Url = pk,
                relayOrigin = relayOrigin,
                mailboxId = body["mailbox_id"] as String,
                submitCapability = body["submit_capability"] as String,
                deviceId = body["device_id"] as String,
                keyPackageBinding = keyPackageBinding,
                issuedAt = body["issued_at"] as Long,
                expiresAt = expiresAt,
                signature = signature,
            )
        }
    }
}
