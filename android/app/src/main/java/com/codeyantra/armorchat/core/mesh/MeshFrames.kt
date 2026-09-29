package com.codeyantra.armorchat.core.mesh

import java.nio.ByteBuffer

data class MeshPhase(
    val beacon: MeshBeacon,
    val ids: List<ByteArray>,
)

object MeshFrames {
    private const val HELLO: Int = 1
    private const val BUNDLE: Int = 4

    fun encodePhase(phase: MeshPhase): ByteArray {
        val beacon = BeaconCodec.encode(phase.beacon)
        val ids = ByteBuffer.allocate(2 + phase.ids.sumOf { it.size })
        ids.putShort(phase.ids.size.toShort())
        phase.ids.forEach { ids.put(it) }
        val idsBytes = ids.array()
        val out = ByteBuffer.allocate(3 + beacon.size + 3 + idsBytes.size)
        putFrame(out, HELLO, beacon)
        putFrame(out, 2, idsBytes)
        return out.array()
    }

    fun decodePhase(bytes: ByteArray): MeshPhase? {
        val frames = split(bytes)
        val hello = frames.firstOrNull { it.first == HELLO }?.second ?: return null
        val idsBody = frames.firstOrNull { it.first == 2 }?.second ?: return null
        val beacon = BeaconCodec.decode(hello) ?: return null
        if (idsBody.size < 2) return null
        val buf = ByteBuffer.wrap(idsBody)
        val count = buf.short.toInt() and 0xffff
        if (buf.remaining() != count * 16) return null
        val ids = List(count) {
            val id = ByteArray(16)
            buf.get(id)
            id
        }
        return MeshPhase(beacon, ids)
    }

    fun encodeBundles(encoded: List<ByteArray>): ByteArray {
        val size = encoded.sumOf { 3 + it.size }
        val out = ByteBuffer.allocate(size)
        encoded.forEach { putFrame(out, BUNDLE, it) }
        return out.array()
    }

    fun decodeBundles(bytes: ByteArray): List<ByteArray> =
        split(bytes).filter { it.first == BUNDLE }.map { it.second }

    private fun putFrame(out: ByteBuffer, type: Int, body: ByteArray) {
        out.put(type.toByte())
        out.putShort(body.size.toShort())
        out.put(body)
    }

    private fun split(bytes: ByteArray): List<Pair<Int, ByteArray>> {
        val buf = ByteBuffer.wrap(bytes)
        val frames = mutableListOf<Pair<Int, ByteArray>>()
        while (buf.remaining() >= 3) {
            val type = buf.get().toInt() and 0xff
            val len = buf.short.toInt() and 0xffff
            if (buf.remaining() < len) return frames
            val body = ByteArray(len)
            buf.get(body)
            frames += type to body
        }
        return frames
    }
}
