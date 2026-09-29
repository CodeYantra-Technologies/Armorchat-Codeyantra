package com.codeyantra.armorchat.core.mesh

import java.security.MessageDigest

data class CustodyBundle(
    val id: ByteArray,
    val createdAtMs: Long,
    val expireAtMs: Long,
    val hopCount: Int,
    val emergency: Boolean,
    val payload: ByteArray,
    val forwardedCount: Int = 0,
) {
    val idHex: String get() = MeshIds.hex(id)

    fun live(nowMs: Long): Boolean = expireAtMs > nowMs && hopCount < MeshPolicy.MAX_HOPS

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustodyBundle) return false
        return id.contentEquals(other.id) &&
            createdAtMs == other.createdAtMs &&
            expireAtMs == other.expireAtMs &&
            hopCount == other.hopCount &&
            emergency == other.emergency &&
            payload.contentEquals(other.payload) &&
            forwardedCount == other.forwardedCount
    }

    override fun hashCode(): Int = id.contentHashCode()
}

object MeshIds {
    fun idFor(payload: ByteArray): ByteArray = sha256(payload).copyOf(16)

    fun sha256(bytes: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(bytes)

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun parseHex(hex: String): ByteArray {
        require(hex.length % 2 == 0) { "odd hex" }
        return ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
    }
}
