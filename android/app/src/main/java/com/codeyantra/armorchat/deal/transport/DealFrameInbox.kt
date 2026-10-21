package com.codeyantra.armorchat.deal.transport

import com.codeyantra.armorchat.deal.crypto.DealKeyProtector
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealTransportRow

/**
 * Sealed partial envelopes for one conversation. A restart can finish a frame
 * that arrived before the process stopped. Terms stay inside the sealed column.
 */
class DealFrameInbox(
    private val database: DealDatabase,
    private val protector: DealKeyProtector,
    private val conversationId: String,
    private val clock: () -> Long,
) {
    private val assembler = DealFrameAssembler()

    fun recover(): List<ByteArray> {
        val ready = mutableListOf<ByteArray>()
        for (row in database.dealDao().transportByStatus(DealTransportState.IN, DealTransportState.CHUNK)) {
            if (row.conversationId != conversationId || row.eventType != DealTransportState.CHUNK) continue
            val chunk = decode(row) ?: continue
            assembler.offer(chunk)?.let { ready += it }
        }
        return ready
    }

    fun offer(messageId: String, chunk: DealFrameChunk): ByteArray? {
        val existing = database.dealDao().findTransport(messageId)
        if (existing == null) {
            val sealed = protector.seal(chunk.bytes, DealTransportService.PURPOSE)
            database.dealDao().insertTransport(
                DealTransportRow(
                    envelopeId = messageId,
                    dealId = chunk.envelopeId,
                    versionId = "${chunk.index}:${chunk.count}:${chunk.frameSha256}",
                    conversationId = conversationId,
                    direction = DealTransportState.IN,
                    eventType = DealTransportState.CHUNK,
                    status = DealTransportState.CHUNK,
                    sealedEnvelope = sealed,
                    createdAtEpochMs = clock(),
                    updatedAtEpochMs = clock(),
                ),
            )
        } else if (existing.eventType != DealTransportState.CHUNK) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        return assembler.offer(chunk)
    }

    fun release(envelopeId: String) = assembler.release(envelopeId)

    private fun decode(row: DealTransportRow): DealFrameChunk? {
        val parts = row.versionId.split(":", limit = 3)
        if (parts.size != 3) return null
        val index = parts[0].toIntOrNull() ?: return null
        val count = parts[1].toIntOrNull() ?: return null
        val bytes = protector.open(row.sealedEnvelope, DealTransportService.PURPOSE)
        return DealFrameChunk(row.dealId, index, count, parts[2], bytes)
    }
}
