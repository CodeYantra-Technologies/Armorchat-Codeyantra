package com.codeyantra.armorchat.core.mesh

import java.io.ByteArrayOutputStream

/**
 * BLE framing for one mesh message: each ATT value is `[flags][data...]`, where
 * flag bit 0 marks the last chunk. A chunk never exceeds the usable ATT value
 * (MTU - 3), so Android never needs a prepared (long) write.
 */
object GattChunks {
    const val DEFAULT_MTU: Int = 23
    const val ATT_HEADER: Int = 3
    const val MAX_ATT_VALUE: Int = 512

    fun usableValue(mtu: Int): Int = (mtu - ATT_HEADER).coerceIn(DEFAULT_MTU - ATT_HEADER, MAX_ATT_VALUE)

    fun split(bytes: ByteArray, mtu: Int): List<ByteArray> {
        val step = usableValue(mtu) - 1
        if (bytes.isEmpty()) return listOf(byteArrayOf(1))
        val out = ArrayList<ByteArray>((bytes.size + step - 1) / step)
        var offset = 0
        while (offset < bytes.size) {
            val take = minOf(step, bytes.size - offset)
            val chunk = ByteArray(1 + take)
            chunk[0] = if (offset + take == bytes.size) 1 else 0
            bytes.copyInto(chunk, 1, offset, offset + take)
            out += chunk
            offset += take
        }
        return out
    }
}

class GattReassembler {
    private val buffer = ByteArrayOutputStream()

    /** Returns the whole message when [chunk] carries the last flag, else null. */
    fun feed(chunk: ByteArray): ByteArray? {
        if (chunk.isEmpty()) return null
        buffer.write(chunk, 1, chunk.size - 1)
        if (chunk[0].toInt() and 0x01 == 0) return null
        val bytes = buffer.toByteArray()
        buffer.reset()
        return bytes
    }
}

/**
 * A chunk is sent only after the previous ack is success (0). A busy or
 * failed code stops the sequence, so a later success does not skip the gap.
 */
object ChunkSendSequence {
    fun acknowledgedCount(acks: List<Int>): Int {
        var count = 0
        for (code in acks) {
            if (code != 0) return count
            count += 1
        }
        return count
    }
}
