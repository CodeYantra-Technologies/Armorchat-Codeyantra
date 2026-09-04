package com.codeyantra.armorchat.domain.identity

import java.security.MessageDigest
import java.util.zip.CRC32

/**
 * ArmorChat ID (`ac1:…`) — a shareable fingerprint of an Ed25519 public key.
 *
 * Binary payload (20 bytes):
 *   fingerprint(16) = SHA-256(ed25519_pubkey_32)[0..15]
 *   crc32(4)        = IEEE CRC-32 over the 16 fingerprint bytes (big-endian)
 *
 * Encoding: Crockford Base32 (no padding), uppercase → 32 characters.
 * Display form: `ac1:` + encoded.
 *
 * Fingerprint length rationale: 128-bit truncation gives a birthday bound of
 * ~2^64, adequate for a human-shareable contact ID. The full 32-byte public
 * key travels in the QR payload; on import the ID is recomputed and must match.
 *
 * CRC-32 is corruption detection only — not a security property.
 */
object ArmorChatId {
    const val PREFIX = "ac1:"
    const val FINGERPRINT_LENGTH = 16
    const val CRC_LENGTH = 4
    const val PAYLOAD_LENGTH = FINGERPRINT_LENGTH + CRC_LENGTH
    const val ENCODED_LENGTH = 32
    const val PUBLIC_KEY_LENGTH = 32

    data class Parsed(
        val id: String,
        val fingerprint: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Parsed) return false
            return id == other.id && fingerprint.contentEquals(other.fingerprint)
        }

        override fun hashCode(): Int = 31 * id.hashCode() + fingerprint.contentHashCode()
    }

    fun derive(publicKeyBytes: ByteArray): String {
        require(publicKeyBytes.size == PUBLIC_KEY_LENGTH) {
            "Ed25519 public key must be $PUBLIC_KEY_LENGTH bytes"
        }
        val fingerprint = fingerprintOf(publicKeyBytes)
        val payload = fingerprint + crc32Bytes(fingerprint)
        return PREFIX + CrockfordBase32.encode(payload)
    }

    fun parse(id: String): Result<Parsed> = runCatching {
        val trimmed = id.trim()
        require(trimmed.startsWith(PREFIX, ignoreCase = true)) {
            "ArmorChat ID must start with $PREFIX"
        }
        val body = trimmed.substring(PREFIX.length).uppercase()
        require(body.length == ENCODED_LENGTH) {
            "ArmorChat ID body must be $ENCODED_LENGTH Crockford characters"
        }
        val payload = CrockfordBase32.decode(body)
        require(payload.size == PAYLOAD_LENGTH) {
            "Decoded ArmorChat ID payload must be $PAYLOAD_LENGTH bytes"
        }
        val fingerprint = payload.copyOfRange(0, FINGERPRINT_LENGTH)
        val crc = payload.copyOfRange(FINGERPRINT_LENGTH, PAYLOAD_LENGTH)
        require(crc.contentEquals(crc32Bytes(fingerprint))) {
            "ArmorChat ID checksum mismatch"
        }
        Parsed(id = PREFIX + body, fingerprint = fingerprint)
    }

    fun matchesPublicKey(id: String, publicKeyBytes: ByteArray): Boolean {
        val derived = runCatching { derive(publicKeyBytes) }.getOrNull() ?: return false
        return derived.equals(id.trim(), ignoreCase = false) ||
            derived.equals(normalize(id), ignoreCase = false)
    }

    fun normalize(id: String): String =
        parse(id).getOrElse { throw it }.id

    fun fingerprintOf(publicKeyBytes: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256").digest(publicKeyBytes)
        return digest.copyOfRange(0, FINGERPRINT_LENGTH)
    }

    private fun crc32Bytes(data: ByteArray): ByteArray {
        val crc = CRC32()
        crc.update(data)
        val value = crc.value
        return byteArrayOf(
            ((value shr 24) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            (value and 0xFF).toByte(),
        )
    }
}
