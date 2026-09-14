package com.codeyantra.armorchat.domain.identity

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Opaque v3 contact-invite token. 256 bits of CSPRNG. Not an identity,
 * mailbox credential, or key.
 */
object InviteToken {
    const val BYTE_LENGTH = 32
    const val ENCODED_LENGTH = 43
    const val ENTROPY_BITS = BYTE_LENGTH * 8
    const val TTL_SECONDS = 48L * 3600L
    const val REUSE_SKEW_SECONDS = 60L

    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(BYTE_LENGTH)
        random.nextBytes(bytes)
        return Base64Url.encode(bytes)
    }

    fun parseCanonical(raw: String): String {
        require(raw.length == ENCODED_LENGTH) { "invite token length" }
        require(raw.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '-' || it == '_' }) {
            "invite token charset"
        }
        val bytes = Base64Url.decode(raw)
        require(bytes.size == BYTE_LENGTH) { "invite token size" }
        val canonical = Base64Url.encode(bytes)
        require(canonical == raw) { "invite token not canonical" }
        return canonical
    }

    fun isCanonical(raw: String): Boolean = runCatching { parseCanonical(raw) }.isSuccess

    /** SHA-256 of the 32 token bytes, unpadded Base64URL. Binds a bundle to one QR. */
    fun inviteId(token: String): String {
        val bytes = Base64Url.decode(parseCanonical(token))
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return Base64Url.encode(digest)
    }
}
