package com.codeyantra.armorchat.core.mesh

import java.nio.ByteBuffer

sealed interface BundleDecode {
    data class Ok(val bundle: CustodyBundle) : BundleDecode
    data class Rejected(val reason: String) : BundleDecode
}

object BundleCodec {
    private val MAGIC = byteArrayOf('A'.code.toByte(), 'C'.code.toByte(), 'M'.code.toByte(), 'X'.code.toByte())

    fun encode(bundle: CustodyBundle): ByteArray {
        val out = ByteBuffer.allocate(44 + bundle.payload.size)
        out.put(MAGIC)
        out.put(MeshPolicy.PROTOCOL_VERSION.toByte())
        out.put(if (bundle.emergency) 1 else 0)
        out.put(bundle.hopCount.toByte())
        out.put(MeshPolicy.MAX_HOPS.toByte())
        out.put(bundle.id)
        out.putLong(bundle.createdAtMs)
        out.putLong(bundle.expireAtMs)
        out.putInt(bundle.payload.size)
        out.put(bundle.payload)
        return out.array()
    }

    fun decode(bytes: ByteArray): BundleDecode {
        if (bytes.size < 44) return BundleDecode.Rejected("short")
        val buf = ByteBuffer.wrap(bytes)
        val magic = ByteArray(4)
        buf.get(magic)
        if (!magic.contentEquals(MAGIC)) return BundleDecode.Rejected("magic")
        val version = buf.get().toInt() and 0xff
        if (version != MeshPolicy.PROTOCOL_VERSION) return BundleDecode.Rejected("version")
        val emergency = buf.get().toInt() and 0xff == 1
        val hop = buf.get().toInt() and 0xff
        val maxHop = buf.get().toInt() and 0xff
        if (maxHop != MeshPolicy.MAX_HOPS || hop > MeshPolicy.MAX_HOPS) return BundleDecode.Rejected("hops")
        val id = ByteArray(16)
        buf.get(id)
        val createdAt = buf.long
        val expireAt = buf.long
        val len = buf.int
        if (len < 0 || len > MeshPolicy.MAX_PAYLOAD_BYTES || buf.remaining() != len) {
            return BundleDecode.Rejected("length")
        }
        val payload = ByteArray(len)
        buf.get(payload)
        if (!id.contentEquals(MeshIds.idFor(payload))) return BundleDecode.Rejected("id")
        if (expireAt != MeshPolicy.expireAt(createdAt)) return BundleDecode.Rejected("ttl")
        return BundleDecode.Ok(
            CustodyBundle(
                id = id,
                createdAtMs = createdAt,
                expireAtMs = expireAt,
                hopCount = hop,
                emergency = emergency,
                payload = payload,
            ),
        )
    }
}
