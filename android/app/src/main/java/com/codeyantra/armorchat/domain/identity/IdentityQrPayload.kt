package com.codeyantra.armorchat.domain.identity

/**
 * Versioned public-only QR payload for ArmorChat identity exchange.
 *
 * Compact JSON:
 * {"p":"armorchat","v":1,"id":"ac1:…","pk":"<base64url unpadded 32-byte ed25519 pub>"}
 *
 * Never includes private keys, recovery secrets, or device IDs.
 * On scan, [pk] is used to recompute the ArmorChat ID; mismatch → reject.
 */
data class IdentityQrPayload(
    val id: String,
    val publicKeyBase64Url: String,
    val version: Int = CURRENT_VERSION,
) {
    companion object {
        const val MAX_PAYLOAD_BYTES = 1024
        const val PRODUCT = "armorchat"
        const val CURRENT_VERSION = 1

        fun fromPublicKey(publicKeyBytes: ByteArray): IdentityQrPayload {
            require(publicKeyBytes.size == ArmorChatId.PUBLIC_KEY_LENGTH)
            val id = ArmorChatId.derive(publicKeyBytes)
            return IdentityQrPayload(
                id = id,
                publicKeyBase64Url = Base64Url.encode(publicKeyBytes),
                version = CURRENT_VERSION,
            )
        }

        fun serialize(payload: IdentityQrPayload): String {
            require(payload.version == CURRENT_VERSION)
            // Compact deterministic JSON (field order fixed for QR stability).
            return buildString {
                append("""{"p":"""").append(PRODUCT).append("""","v":""")
                append(payload.version)
                append(""","id":"""").append(payload.id).append("""","pk":"""")
                append(payload.publicKeyBase64Url).append(""""}""")
            }
        }

        fun parse(raw: String): Result<IdentityQrPayload> = runCatching {
            require(raw.length <= MAX_PAYLOAD_BYTES && raw.toByteArray(Charsets.UTF_8).size <= MAX_PAYLOAD_BYTES) {
                "QR payload exceeds size limit"
            }
            val fields = PublicQrJson.read(raw)
            require(fields.keys == setOf("p", "v", "id", "pk")) { "Unexpected QR fields" }
            require(fields["p"] == PRODUCT) { "QR product must be $PRODUCT" }
            require(fields["v"] == CURRENT_VERSION) { "Unsupported QR version" }
            val v = CURRENT_VERSION
            val id = fields["id"] as? String ?: error("Missing ID")
            val pk = fields["pk"] as? String ?: error("Missing public key")
            require(pk.matches(Regex("[A-Za-z0-9_-]{43}"))) { "Invalid Base64URL public key" }
            val parsedId = ArmorChatId.parse(id).getOrThrow()
            val publicKey = Base64Url.decode(pk)
            require(publicKey.size == ArmorChatId.PUBLIC_KEY_LENGTH) {
                "Public key must be ${ArmorChatId.PUBLIC_KEY_LENGTH} bytes"
            }
            require(Base64Url.encode(publicKey) == pk) { "Noncanonical Base64URL" }
            require(org.bouncycastle.math.ec.rfc8032.Ed25519.validatePublicKeyFull(publicKey, 0)) {
                "Invalid Ed25519 public key"
            }
            val expected = ArmorChatId.derive(publicKey)
            require(expected == parsedId.id) {
                "QR id does not match public key (binding check failed)"
            }
            IdentityQrPayload(id = parsedId.id, publicKeyBase64Url = pk, version = v)
        }

    }
}
