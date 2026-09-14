package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.network.CodeYantraRelayTransport
import com.codeyantra.armorchat.domain.identity.Base64Url
import java.security.MessageDigest

/**
 * Signed routing invite. Separate from the public identity QR.
 * Product field is armorchat-route, never mixed into ac1 identity QR.
 */
data class RouteInvitation(
    val relayOrigin: String,
    val mailboxId: String,
    val submitCapability: String,
    val deviceId: String,
    val generation: Long,
    val inviterPin: String,
    val recipientPin: String,
    val revision: Long,
    val issuedAt: Long,
    val expiresAt: Long,
    val keyPackageBinding: String,
    val signature: String,
) {
    fun toQr(): String {
        val signed = mapOf("body" to bodyMap(), "signature" to signature)
        return CanonicalJson.of(mapOf("p" to PRODUCT, "route" to signed, "v" to 1L))
    }

    fun bodyMap(): Map<String, Any?> = mapOf(
        "device_id" to deviceId,
        "expires_at" to expiresAt,
        "generation" to generation,
        "issued_at" to issuedAt,
        "key_package_binding" to keyPackageBinding,
        "mailbox_id" to mailboxId,
        "recipient_identity_key" to recipientPin,
        "relay_origin" to relayOrigin,
        "revision" to revision,
        "submit_capability" to submitCapability,
        "suite" to SUITE,
        "v" to 1L,
    )

    companion object {
        const val PRODUCT = "armorchat-route"
        const val SUITE = "ac-mls-v1"
        const val MAX_BYTES = 8192

        fun issue(
            signer: IdentitySigner,
            relayOrigin: String,
            mailboxId: String,
            submitCapability: String,
            deviceId: String,
            recipientPin: String,
            keyPackageBinding: String,
            nowEpochSeconds: Long,
        ): RouteInvitation {
            CodeYantraRelayTransport.parseOrigin(relayOrigin)
            val body = mapOf(
                "device_id" to deviceId,
                "expires_at" to nowEpochSeconds + 7L * 24 * 3600,
                "generation" to 1L,
                "issued_at" to nowEpochSeconds,
                "key_package_binding" to keyPackageBinding,
                "mailbox_id" to mailboxId,
                "recipient_identity_key" to recipientPin,
                "relay_origin" to relayOrigin,
                "revision" to 1L,
                "submit_capability" to submitCapability,
                "suite" to SUITE,
                "v" to 1L,
            )
            val framed = CanonicalJson.frame(ArmorChatContexts.ROUTE, body)
            val signature = Base64Url.encode(signer.sign(ArmorChatContexts.ROUTE, framed))
            return RouteInvitation(
                relayOrigin = relayOrigin,
                mailboxId = mailboxId,
                submitCapability = submitCapability,
                deviceId = deviceId,
                generation = 1,
                inviterPin = signer.pin,
                recipientPin = recipientPin,
                revision = 1,
                issuedAt = nowEpochSeconds,
                expiresAt = nowEpochSeconds + 7L * 24 * 3600,
                keyPackageBinding = keyPackageBinding,
                signature = signature,
            )
        }

        fun looksLike(raw: String): Boolean {
            if (raw.length > MAX_BYTES) return false
            return try {
                CanonicalJson.parseObject(raw)["p"] == PRODUCT
            } catch (_: Exception) {
                false
            }
        }

        fun parseUnverified(raw: String, expectedRecipientPin: String): RouteInvitation {
            require(raw.length <= MAX_BYTES) { "route too large" }
            val root = CanonicalJson.parseObject(raw)
            require(root["p"] == PRODUCT) { "not a route invite" }
            require((root["v"] as Long) == 1L) { "unsupported route version" }
            @Suppress("UNCHECKED_CAST")
            val signed = root["route"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val body = signed["body"] as Map<String, Any?>
            require((body["v"] as Long) == 1L && body["suite"] == SUITE)
            require(body["recipient_identity_key"] == expectedRecipientPin) {
                "route not bound to this identity"
            }
            CodeYantraRelayTransport.parseOrigin(body["relay_origin"] as String)
            val binding = CanonicalJson.parseObject(body["key_package_binding"] as String)
            @Suppress("UNCHECKED_CAST")
            val inviterPin = (binding["body"] as Map<String, Any?>)["identity_key"] as String
            val signature = signed["signature"] as String
            return RouteInvitation(
                relayOrigin = body["relay_origin"] as String,
                mailboxId = body["mailbox_id"] as String,
                submitCapability = body["submit_capability"] as String,
                deviceId = body["device_id"] as String,
                generation = body["generation"] as Long,
                inviterPin = inviterPin,
                recipientPin = body["recipient_identity_key"] as String,
                revision = body["revision"] as Long,
                issuedAt = body["issued_at"] as Long,
                expiresAt = body["expires_at"] as Long,
                keyPackageBinding = body["key_package_binding"] as String,
                signature = signature,
            )
        }

        fun parse(raw: String, expectedRecipientPin: String, nowEpochSeconds: Long): RouteInvitation {
            require(raw.length <= MAX_BYTES) { "route too large" }
            val root = CanonicalJson.parseObject(raw)
            require(root["p"] == PRODUCT) { "not a route invite" }
            require((root["v"] as Long) == 1L) { "unsupported route version" }
            @Suppress("UNCHECKED_CAST")
            val signed = root["route"] as Map<String, Any?>
            @Suppress("UNCHECKED_CAST")
            val body = signed["body"] as Map<String, Any?>
            require((body["v"] as Long) == 1L && body["suite"] == SUITE)
            require(body["recipient_identity_key"] == expectedRecipientPin) {
                "route not bound to this identity"
            }
            CodeYantraRelayTransport.parseOrigin(body["relay_origin"] as String)
            require((body["expires_at"] as Long) > nowEpochSeconds) { "route expired" }
            val binding = CanonicalJson.parseObject(body["key_package_binding"] as String)
            @Suppress("UNCHECKED_CAST")
            val inviterPin = (binding["body"] as Map<String, Any?>)["identity_key"] as String
            val framed = CanonicalJson.frame(ArmorChatContexts.ROUTE, body)
            val signature = signed["signature"] as String
            verifyEd25519(inviterPin, framed, Base64Url.decode(signature))
            return RouteInvitation(
                relayOrigin = body["relay_origin"] as String,
                mailboxId = body["mailbox_id"] as String,
                submitCapability = body["submit_capability"] as String,
                deviceId = body["device_id"] as String,
                generation = body["generation"] as Long,
                inviterPin = inviterPin,
                recipientPin = body["recipient_identity_key"] as String,
                revision = body["revision"] as Long,
                issuedAt = body["issued_at"] as Long,
                expiresAt = body["expires_at"] as Long,
                keyPackageBinding = body["key_package_binding"] as String,
                signature = signature,
            )
        }
    }
}

internal fun verifyEd25519(pin: String, message: ByteArray, signature: ByteArray) {
    val pub = Base64Url.decode(pin)
    require(pub.size == 32 && signature.size == 64)
    val verify = com.google.crypto.tink.subtle.Ed25519Verify(pub)
    verify.verify(signature, message)
}

internal fun sha256(bytes: ByteArray): ByteArray =
    MessageDigest.getInstance("SHA-256").digest(bytes)
