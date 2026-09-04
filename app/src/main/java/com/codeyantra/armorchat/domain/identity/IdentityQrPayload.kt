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
            val text = raw.trim()
            require(text.startsWith("{") && text.endsWith("}")) { "QR payload must be JSON object" }
            val p = requiredString(text, "p")
            require(p == PRODUCT) { "QR product must be $PRODUCT" }
            val v = requiredInt(text, "v")
            require(v == CURRENT_VERSION) { "Unsupported QR version: $v" }
            val id = requiredString(text, "id")
            val pk = requiredString(text, "pk")
            val parsedId = ArmorChatId.parse(id).getOrThrow()
            val publicKey = Base64Url.decode(pk)
            require(publicKey.size == ArmorChatId.PUBLIC_KEY_LENGTH) {
                "Public key must be ${ArmorChatId.PUBLIC_KEY_LENGTH} bytes"
            }
            val expected = ArmorChatId.derive(publicKey)
            require(expected == parsedId.id) {
                "QR id does not match public key (binding check failed)"
            }
            IdentityQrPayload(id = parsedId.id, publicKeyBase64Url = pk, version = v)
        }

        private fun requiredString(json: String, key: String): String {
            val pattern = """"$key"\s*:\s*"([^"]*)"""".toRegex()
            val match = pattern.find(json)
                ?: throw IllegalArgumentException("Missing string field: $key")
            return match.groupValues[1]
        }

        private fun requiredInt(json: String, key: String): Int {
            val pattern = """"$key"\s*:\s*(-?\d+)""".toRegex()
            val match = pattern.find(json)
                ?: throw IllegalArgumentException("Missing int field: $key")
            return match.groupValues[1].toInt()
        }
    }
}
