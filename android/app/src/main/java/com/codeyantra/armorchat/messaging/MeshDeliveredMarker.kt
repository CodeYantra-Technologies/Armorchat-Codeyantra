package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.mesh.MeshDeliveredStore
import kotlinx.coroutines.CancellationException

/**
 * Mesh-1K-N (P2): the two decisions ChatMessagingService.deliverMesh makes around
 * MeshDelivery.deliverDurable for delivered-here markers (EmergencyMeshEngine / MeshDeliveredStore).
 * Kept here so JVM tests run the same code production runs.
 */
internal object MeshDeliveredMarker {
    /**
     * deliverDurable's `durable` pre-check: the P1 durable inbox state first (authoritative). Only
     * when no row owns the outer id is the local marker consulted: a live marker for these exact
     * bytes means this envelope was durably delivered here before (row since deleted) => SAME
     * (DUPLICATE, no decrypt, no row, no receipt). A row in CONFLICT is never overridden.
     */
    suspend fun durable(
        payload: ByteArray,
        findById: suspend (String) -> MessageEntity?,
        hasMarker: suspend (payload: ByteArray, messageId: String) -> Boolean,
    ): MeshDelivery.Durable {
        val state = MeshDelivery.durableState(payload, findById)
        if (state != MeshDelivery.Durable.NONE) return state
        val id = MeshDelivery.outerMessageId(payload) ?: return state
        return if (hasMarker(payload, id)) MeshDelivery.Durable.SAME else state
    }

    /**
     * After deliverDurable returned [ok] with [outcome]: record a marker only when a durable inbound
     * row with this outer id and this ciphertext digest exists, i.e. DELIVERED with exactly one
     * inserted row, or DUPLICATE whose row is still present. Non-row records (receipts, setup
     * routes), CONFLICT, failures and marker-only duplicates record nothing.
     * Returns null when nothing was to be recorded, else whether [record] persisted the marker.
     * A failure here never changes the delivery result.
     */
    suspend fun recordAfter(
        payload: ByteArray,
        ok: Boolean,
        outcome: MeshDelivery.Outcome?,
        committed: List<MeshInboxCommit.Staged>,
        findById: suspend (String) -> MessageEntity?,
        record: suspend (payload: ByteArray, messageId: String, binding: String) -> Boolean,
    ): Boolean? {
        if (!ok) return null
        return try {
            val outerId = MeshDelivery.outerMessageId(payload) ?: return null
            val digest = MeshDelivery.outerCiphertextDigest(payload) ?: return null
            val row: MessageEntity = when (outcome) {
                MeshDelivery.Outcome.DELIVERED -> committed.singleOrNull()?.message ?: return null
                MeshDelivery.Outcome.DUPLICATE -> findById(outerId) ?: return null
                else -> return null
            }
            if (row.id != outerId || row.isOutgoing || row.receiptCiphertextSha256 != digest) return null
            record(payload, row.id, MeshDeliveredStore.bindingOf(row.conversationId, row.receiptSessionId, row.id))
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            false
        }
    }
}
