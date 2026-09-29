package com.codeyantra.armorchat.core.mesh

import java.nio.ByteBuffer

data class MeshBeacon(
    val holding: Int,
    val emergency: Boolean,
    val wifiDirect: Boolean,
    val minutesLeft: Int,
    val nonce: ByteArray,
    val digest: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MeshBeacon) return false
        return holding == other.holding &&
            emergency == other.emergency &&
            wifiDirect == other.wifiDirect &&
            minutesLeft == other.minutesLeft &&
            nonce.contentEquals(other.nonce) &&
            digest.contentEquals(other.digest)
    }

    override fun hashCode(): Int = nonce.contentHashCode()
}

object BeaconCodec {
    const val SIZE: Int = 18

    fun digest(ids: List<ByteArray>): ByteArray {
        val sorted = ids.sortedWith(compareBy { MeshIds.hex(it) })
        val joined = ByteArray(sorted.sumOf { it.size })
        var at = 0
        for (id in sorted) {
            id.copyInto(joined, at)
            at += id.size
        }
        return MeshIds.sha256(joined).copyOf(8)
    }

    fun minutesLeft(soonestExpireAtMs: Long?, nowMs: Long): Int {
        if (soonestExpireAtMs == null) return 255
        val left = ((soonestExpireAtMs - nowMs) / 60_000L).toInt()
        return left.coerceIn(0, 240)
    }

    fun encode(beacon: MeshBeacon): ByteArray {
        require(beacon.nonce.size == 4)
        require(beacon.digest.size == 8)
        var flags = 0
        if (beacon.emergency) flags = flags or 0x01
        if (beacon.wifiDirect) flags = flags or 0x02
        val out = ByteBuffer.allocate(SIZE)
        out.put(MeshPolicy.PROTOCOL_VERSION.toByte())
        out.put(flags.toByte())
        out.put(beacon.holding.coerceIn(0, 255).toByte())
        out.put(beacon.minutesLeft.coerceIn(0, 255).toByte())
        out.put(beacon.nonce)
        out.put(beacon.digest)
        return out.array()
    }

    fun decode(bytes: ByteArray): MeshBeacon? {
        if (bytes.size != SIZE) return null
        val buf = ByteBuffer.wrap(bytes)
        val version = buf.get().toInt() and 0xff
        if (version != MeshPolicy.PROTOCOL_VERSION) return null
        val flags = buf.get().toInt() and 0xff
        val holding = buf.get().toInt() and 0xff
        val minutes = buf.get().toInt() and 0xff
        val nonce = ByteArray(4)
        buf.get(nonce)
        val digest = ByteArray(8)
        buf.get(digest)
        return MeshBeacon(
            holding = holding,
            emergency = flags and 0x01 != 0,
            wifiDirect = flags and 0x02 != 0,
            minutesLeft = minutes,
            nonce = nonce,
            digest = digest,
        )
    }
}
