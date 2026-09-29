package com.codeyantra.armorchat.domain.identity

import com.codeyantra.armorchat.core.json.CanonicalJson
import com.codeyantra.armorchat.core.network.CodeYantraRelayTransport
import com.codeyantra.armorchat.core.network.RelayRegistry
import com.codeyantra.armorchat.core.network.SubmitGrant
import com.codeyantra.armorchat.messaging.ArmorChatContexts
import com.codeyantra.armorchat.messaging.IdentitySigner
import com.codeyantra.armorchat.messaging.verifyEd25519

/**
 * Compact Contact QR v3: opaque invite token only.
 *
 * `armorchat://add/<43-char unpadded Base64URL token>`
 */
data class ContactQrV3(
    val token: String,
) {
    init {
        InviteToken.parseCanonical(token)
    }

    fun toQr(): String = PREFIX + token

    companion object {
        const val PREFIX = "armorchat://add/"
        const val VERSION = 3

        fun looksLike(raw: String): Boolean {
            val trimmed = raw.trim()
            return trimmed.startsWith(PREFIX) && !trimmed.contains('\n')
        }

        fun parse(raw: String): ContactQrV3? {
            val trimmed = raw.trim()
            if (!trimmed.startsWith(PREFIX)) return null
            if (trimmed.length != PREFIX.length + InviteToken.ENCODED_LENGTH) return null
            val token = trimmed.substring(PREFIX.length)
            if (token.any { it == '/' || it == '?' || it == '#' || it == ' ' }) return null
            return runCatching { ContactQrV3(InviteToken.parseCanonical(token)) }.getOrNull()
        }

        fun ofGenerated(randomToken: String = InviteToken.generate()): ContactQrV3 =
            ContactQrV3(InviteToken.parseCanonical(randomToken))
    }
}

/**
 * Owner-signed contact record fetched with a v3 invite token.
 * The relay only stores/returns this JSON; clients verify the Ed25519 signature.
 */
data class ContactBundleV3(
    val id: String,
    val publicKeyBase64Url: String,
    val handle: String?,
    val displayName: String?,
    val relayOrigin: String,
    val mailboxId: String,
    val deviceId: String,
    val inviteId: String,
    val issuedAt: Long,
    val expiresAt: Long,
    val signature: String,
    val suite: String = SUITE,
    val version: Int = VERSION,
) {
    fun toJson(): String {
        val signed = mapOf("body" to bodyMap(), "signature" to signature)
        return CanonicalJson.of(mapOf("contact_bundle" to signed, "p" to PRODUCT, "v" to VERSION.toLong()))
    }

    fun bodyMap(): Map<String, Any?> = linkedMapOf<String, Any?>(
        "applies_to" to APPLIES_TO,
        "device_id" to deviceId,
        "expires_at" to expiresAt,
        "id" to id,
        "invite_id" to inviteId,
        "issued_at" to issuedAt,
        "mailbox_id" to mailboxId,
        "pk" to publicKeyBase64Url,
        "relay_origin" to relayOrigin,
        "suite" to suite,
        "v" to VERSION.toLong(),
    ).apply {
        if (!displayName.isNullOrBlank()) put("display_name", displayName)
        if (!handle.isNullOrBlank()) put("handle", handle)
    }

    fun containsForbiddenSecrets(): Boolean {
        val blob = toJson().lowercase()
        return listOf("owner_token", "private_key", "privatekey", "keystore", "recovery").any { it in blob }
    }

    companion object {
        const val PRODUCT = "armorchat"
        const val VERSION = 3
        const val SUITE = "ac-mls-v1"
        const val APPLIES_TO = "armorchat-contact-bundle-v3"
        const val MAX_BYTES = 8192

        fun issue(
            signer: IdentitySigner,
            token: String,
            handle: String?,
            displayName: String?,
            relayOrigin: String,
            mailboxId: String,
            deviceId: String,
            nowEpochSeconds: Long,
            // Relay rejects expires_at > server_now + TTL; leave room for phone clock skew.
            ttlSeconds: Long = InviteToken.TTL_SECONDS - 300L,
        ): ContactBundleV3 {
            val canonicalToken = InviteToken.parseCanonical(token)
            require(ttlSeconds in 1..InviteToken.TTL_SECONDS) { "invite ttl" }
            val normalizedOrigin = CodeYantraRelayTransport.parseOrigin(relayOrigin).toString().trimEnd('/')
            val pk = signer.pin
            val pkBytes = Base64Url.decode(pk)
            require(pkBytes.size == ArmorChatId.PUBLIC_KEY_LENGTH)
            val id = ArmorChatId.derive(pkBytes)
            val issuedAt = nowEpochSeconds
            val expiresAt = nowEpochSeconds + ttlSeconds
            val canonicalHandle = handle?.let { ArmorChatHandle.parse(it)?.canonical }
            val safeName = displayName?.trim()?.takeIf { it.isNotEmpty() }?.take(100)
            val inviteId = InviteToken.inviteId(canonicalToken)
            val body = linkedMapOf<String, Any?>(
                "applies_to" to APPLIES_TO,
                "device_id" to deviceId,
                "expires_at" to expiresAt,
                "id" to id,
                "invite_id" to inviteId,
                "issued_at" to issuedAt,
                "mailbox_id" to mailboxId,
                "pk" to pk,
                "relay_origin" to normalizedOrigin,
                "suite" to SUITE,
                "v" to VERSION.toLong(),
            ).apply {
                if (safeName != null) put("display_name", safeName)
                if (canonicalHandle != null) put("handle", canonicalHandle)
            }
            val framed = CanonicalJson.frame(ArmorChatContexts.CONTACT_BUNDLE_V3, body)
            val signature = Base64Url.encode(signer.sign(ArmorChatContexts.CONTACT_BUNDLE_V3, framed))
            return ContactBundleV3(
                id = id,
                publicKeyBase64Url = pk,
                handle = canonicalHandle,
                displayName = safeName,
                relayOrigin = normalizedOrigin,
                mailboxId = mailboxId,
                deviceId = deviceId,
                inviteId = inviteId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                signature = signature,
            )
        }

        fun parse(
            raw: String,
            token: String,
            nowEpochSeconds: Long,
            relayRegistry: RelayRegistry? = null,
        ): ContactBundleV3 {
            require(raw.length <= MAX_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) {
                "bundle exceeds size limit"
            }
            val canonicalToken = InviteToken.parseCanonical(token)
            val expectedInviteId = InviteToken.inviteId(canonicalToken)
            val root = CanonicalJson.parseObject(raw)
            require(root["p"] == PRODUCT) { "Not an armorchat bundle" }
            require((root["v"] as Long) == VERSION.toLong()) { "Not a v3 contact bundle" }
            @Suppress("UNCHECKED_CAST")
            val signed = root["contact_bundle"] as? Map<String, Any?> ?: error("Missing signature envelope")
            @Suppress("UNCHECKED_CAST")
            val body = signed["body"] as? Map<String, Any?> ?: error("Missing bundle body")

            require((body["v"] as Long) == VERSION.toLong() && body["suite"] == SUITE)
            require(body["applies_to"] == APPLIES_TO)

            val pk = body["pk"] as? String ?: error("Missing pk")
            val id = body["id"] as? String ?: error("Missing id")
            val pubKeyBytes = Base64Url.decode(pk)
            require(pubKeyBytes.size == ArmorChatId.PUBLIC_KEY_LENGTH)
            require(org.bouncycastle.math.ec.rfc8032.Ed25519.validatePublicKeyFull(pubKeyBytes, 0)) {
                "Invalid Ed25519 public key"
            }
            val recomputedId = ArmorChatId.derive(pubKeyBytes)
            require(recomputedId == id) { "ArmorChat ID does not match public identity key" }

            val expiresAt = body["expires_at"] as Long
            require(expiresAt > nowEpochSeconds) { "Bundle expired" }
            val issuedAt = body["issued_at"] as Long
            require(issuedAt <= nowEpochSeconds + 300) { "Bundle issued in the future" }
            require(expiresAt - issuedAt <= InviteToken.TTL_SECONDS) { "Bundle ttl too long" }

            val relayOrigin = body["relay_origin"] as String
            CodeYantraRelayTransport.parseOrigin(relayOrigin)
            if (relayRegistry != null) {
                require(relayRegistry.isAuthorizedOrigin(relayOrigin)) { "Unauthorized relay origin" }
            }

            val inviteId = body["invite_id"] as? String ?: error("Missing invite_id")
            require(inviteId == expectedInviteId) { "Bundle is not bound to this invite token" }
            require(!body.containsKey("submit_capability")) { "v3 bundle must not carry submit capability" }
            require(!body.containsKey("key_package_binding")) { "v3 bundle must not carry key package" }

            val handleRaw = body["handle"] as? String
            val handle = handleRaw?.let { ArmorChatHandle.parse(it)?.canonical }
            if (handleRaw != null) {
                require(handle != null && handle == handleRaw) { "Invalid handle in bundle" }
            }
            val displayName = (body["display_name"] as? String)?.trim()
            if (displayName != null) {
                require(displayName.isNotEmpty() && displayName.length <= 100)
            }

            val framed = CanonicalJson.frame(ArmorChatContexts.CONTACT_BUNDLE_V3, body)
            val signature = signed["signature"] as? String ?: error("Missing signature")
            verifyEd25519(pk, framed, Base64Url.decode(signature))

            val mailboxId = body["mailbox_id"] as String
            require(mailboxId.isNotBlank())
            require(!mailboxId.equals("owner_token", true))
            require("owner_token" !in raw.lowercase())
            require("submit_capability" !in raw.lowercase())

            return ContactBundleV3(
                id = id,
                publicKeyBase64Url = pk,
                handle = handle,
                displayName = displayName,
                relayOrigin = relayOrigin,
                mailboxId = mailboxId,
                deviceId = body["device_id"] as String,
                inviteId = inviteId,
                issuedAt = issuedAt,
                expiresAt = expiresAt,
                signature = signature,
            )
        }
    }
}

/**
 * Per-peer result of redeeming a compact v3 invite.
 * Identity is the signed bundle; MLS bootstrap and write grant are unique to this claim.
 */
data class InviteRedemption(
    val bundle: ContactBundleV3,
    val keyPackageId: String,
    val keyPackageBinding: String,
    val bootstrapGrant: SubmitGrant,
)
