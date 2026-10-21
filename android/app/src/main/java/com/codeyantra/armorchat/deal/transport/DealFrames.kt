package com.codeyantra.armorchat.deal.transport

import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.domain.identity.Base64Url

/**
 * One slice of a deal envelope carried inside an authenticated messaging record.
 * The bytes are the envelope itself. They are not a chat preview.
 */
class DealFrameChunk(
    val envelopeId: String,
    val index: Int,
    val count: Int,
    val frameSha256: String,
    val bytes: ByteArray,
) {
    override fun toString(): String = "DealFrameChunk"
}

/**
 * Splits a DealMessageEnvelope so each piece fits in one messaging record.
 * Reassembly checks the hash and rejects a conflicting payload for the same envelope.
 */
object DealFrames {
    const val CHUNK_BYTES = 12_288
    const val MAX_CHUNKS = 16

    fun split(payload: ByteArray): List<DealFrameChunk> {
        val envelope = DealEnvelopes.parse(payload)
        if (payload.size > MAX_CHUNKS * CHUNK_BYTES) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val hash = Base64Url.encode(sha256(payload))
        val count = (payload.size + CHUNK_BYTES - 1) / CHUNK_BYTES
        return List(count) { index ->
            val start = index * CHUNK_BYTES
            val end = minOf(start + CHUNK_BYTES, payload.size)
            DealFrameChunk(
                envelopeId = envelope.envelopeId,
                index = index,
                count = count,
                frameSha256 = hash,
                bytes = payload.copyOfRange(start, end),
            )
        }
    }
}

/**
 * Collects chunks until every index is present. A repeated chunk is ignored.
 * A different payload for the same envelope is rejected.
 */
class DealFrameAssembler {
    private val groups = mutableMapOf<String, Group>()

    fun offer(chunk: DealFrameChunk): ByteArray? {
        if (chunk.count !in 1..DealFrames.MAX_CHUNKS || chunk.index !in 0 until chunk.count) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        if (chunk.bytes.isEmpty() || chunk.bytes.size > DealFrames.CHUNK_BYTES) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val group = groups.getOrPut(chunk.envelopeId) { Group(chunk.count, chunk.frameSha256) }
        if (group.count != chunk.count || group.hash != chunk.frameSha256) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val held = group.pending
        if (held != null) return held.copyOf()
        if (group.done) return null
        val existing = group.parts[chunk.index]
        if (existing != null) {
            if (!existing.contentEquals(chunk.bytes)) throw DealFormatException("Deal envelope was rejected.")
            return null
        }
        group.parts[chunk.index] = chunk.bytes.copyOf()
        if (group.parts.any { it == null }) return null
        val frame = group.parts.fold(ByteArray(0)) { acc, part -> acc + part!! }
        if (Base64Url.encode(sha256(frame)) != group.hash) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        group.parts.forEach { it?.fill(0) }
        group.parts.fill(null)
        group.pending = frame
        return frame.copyOf()
    }

    /** Drops a frame after it was applied or rejected. A later chunk is ignored. */
    fun release(envelopeId: String) {
        val group = groups[envelopeId] ?: return
        group.pending?.fill(0)
        group.pending = null
        group.done = true
    }

    private class Group(val count: Int, val hash: String) {
        val parts = arrayOfNulls<ByteArray>(count)
        var pending: ByteArray? = null
        var done: Boolean = false
    }
}
