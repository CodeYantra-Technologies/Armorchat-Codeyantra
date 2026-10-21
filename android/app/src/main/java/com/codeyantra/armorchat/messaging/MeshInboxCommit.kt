package com.codeyantra.armorchat.messaging

import androidx.room.withTransaction
import com.codeyantra.armorchat.core.database.ArmorChatDatabase
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.domain.model.AttachmentMetadata
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.domain.model.MessageType
import kotlinx.coroutines.CancellationException

/**
 * Mesh-1H-1: atomic durable boundary for Mesh inbound delivery (Mesh path only; relay receive
 * keeps ChatMessagingService.persistInbound + persist(contact) unchanged).
 *
 * deliverMesh stages inbox rows during offerMesh (nothing written), prepares the contact_sessions
 * row (Keystore encryption included) BEFORE the transaction, then [commit] writes, in ONE
 * ArmorChatDatabase transaction, the staged inbox rows (findById dedup), the conversation touch
 * and the contact_sessions upsert. Either all commit or none. Notification / attachment work is
 * returned as [Staged.afterCommit] and run by the caller only after commit, outside the mutex.
 *
 * Existing inbox row (findById != null) during a Mesh commit: decrypt has already succeeded under
 * the mutex, so the in-memory MLS state accepted this ciphertext from the durable snapshot. The row
 * can only pre-exist when (a) a pre-1H-1 or relay-path delivery committed the row but not the MLS
 * advance (window 2), (b) the same message id arrived first by relay and now by Mesh, or (c) an
 * in-memory replay (seen store) re-offers an accepted record without a new decrypt. In every case
 * the row is kept as-is (no second insert, no notification) and the session row is still upserted:
 * in (a)/(b) the advanced MLS state is the one that consumed this ciphertext, and not persisting it
 * would leave durable MLS behind memory; in (c) the snapshot is unchanged, so the upsert is a no-op.
 * A genuine replay of an already-durable ciphertext is rejected by MLS before staging (hypothesis
 * for native mls-rs; JVM tests use a fake MLS engine) and never reaches this point.
 */
internal class MeshInboxCommit(
    private val database: ArmorChatDatabase,
    // Test seams only (fault injection inside the real transaction). Production passes none.
    private val beforeInsert: suspend (MessageEntity) -> Unit = {},
    private val beforeSessionUpsert: suspend (ContactSessionEntity) -> Unit = {},
    private val beforeRelease: suspend (Release) -> Unit = {},
) {
    /**
     * Mesh-1K-O (P3): sender-side effect of ONE validated DELIVERY receipt (MeshReceiptAck.stageRelease):
     * the outgoing row [targetMessageId] of [conversationId] becomes DELIVERED (never regressing READ) and
     * this phone's OWN custody rows [custodyIds] (hopCount 0, holding exactly that envelope) are deleted.
     * Applied in the same transaction as the staged rows and the contact_sessions upsert.
     */
    class Release(
        val peerPin: String,
        val conversationId: String,
        val targetMessageId: String,
        val targetCiphertextSha256: String,
        val custodyIds: List<String>,
        val receiptMessageId: String,
        val receiptSessionId: String,
        val deliveredAtMs: Long,
    )

    class Staged(
        val peerPin: String,
        val message: MessageEntity,
        val conversationTitle: String?,
        val afterCommit: suspend () -> Unit,
    )

    /**
     * [existing] = staged rows whose id already had a row (kept as-is, as before). Mesh-1K-M:
     * [conflicts] = how many of those did NOT match the staged envelope (outgoing row, or a
     * different receiptCiphertextSha256); they are still never overwritten.
     */
    class Result(
        val inserted: List<Staged>,
        val existing: Int,
        val conflicts: Int = 0,
        /** Mesh-1K-O (P3): releases actually applied, and the own custody ids actually deleted. */
        val released: List<Release> = emptyList(),
        val releasedCustodyIds: List<String> = emptyList(),
    ) {
        /** Mesh-1K-M: what this commit did, for MeshDelivery.deliverDurable's committedAs. */
        fun outcome(): MeshDelivery.Outcome = when {
            inserted.isNotEmpty() -> MeshDelivery.Outcome.DELIVERED
            conflicts > 0 -> MeshDelivery.Outcome.CONFLICT
            existing > 0 -> MeshDelivery.Outcome.DUPLICATE
            else -> MeshDelivery.Outcome.DELIVERED // non-row record (e.g. receipt, setup route)
        }
    }

    suspend fun commit(
        staged: List<Staged>,
        session: ContactSessionEntity?,
        releases: List<Release> = emptyList(),
    ): Result =
        database.withTransaction {
            val messages = database.messageDao()
            val inserted = mutableListOf<Staged>()
            var existing = 0
            var conflicts = 0
            for (s in staged) {
                val prior = messages.findById(s.message.id)
                if (prior != null) {
                    existing++
                    if (prior.isOutgoing || prior.receiptCiphertextSha256 != s.message.receiptCiphertextSha256) conflicts++
                    continue
                }
                beforeInsert(s.message)
                messages.insert(s.message)
                database.conversationDao().touch(s.message.conversationId, s.conversationTitle, s.message.createdAtEpochMs)
                inserted += s
            }
            // Mesh-1K-O (P3): validated receipt effects. A row that exists but is not this conversation's
            // own outgoing message bound to that ciphertext is never touched (release skipped). Only own
            // custody rows (hopCount 0) are deleted. Any failure rolls back the whole transaction.
            val released = mutableListOf<Release>()
            val deleted = mutableListOf<String>()
            if (releases.isNotEmpty()) {
                val custody = database.meshCustodyDao()
                val ownIds = custody.all().filter { it.hopCount == 0 }.mapTo(HashSet()) { it.id }
                for (r in releases) {
                    val row = messages.findById(r.targetMessageId)
                    if (row != null) {
                        if (!row.isOutgoing || row.conversationId != r.conversationId) continue
                        val bound = row.receiptCiphertextSha256
                        if (bound != null && bound != r.targetCiphertextSha256) continue
                    }
                    beforeRelease(r)
                    if (row != null && row.status != MessageStatus.DELIVERED.name && row.status != MessageStatus.READ.name) {
                        messages.updateStatusDelivered(row.id, MessageStatus.DELIVERED.name, r.deliveredAtMs)
                    }
                    for (id in r.custodyIds) {
                        if (id in ownIds && id !in deleted) {
                            custody.delete(id)
                            deleted += id
                        }
                    }
                    released += r
                }
            }
            if (session != null) {
                beforeSessionUpsert(session)
                database.contactSessionDao().upsert(session)
            }
            Result(inserted, existing, conflicts, released, deleted)
        }

    companion object {
        /** Post-commit effects; a failing effect never un-reports a committed delivery. */
        suspend fun runAfterCommit(inserted: List<Staged>) {
            for (s in inserted) {
                try {
                    s.afterCommit()
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Exception) {
                    runCatching { SafeLog.event("mesh_inbox", "after_commit_effect=failed") }
                }
            }
        }

        /** Same row mapping as ChatMessagingService.persistInbound; null where it returns early. */
        fun inboundRow(conversationId: String, inbound: InboundRecord, nowMs: Long): MessageEntity? {
            if (inbound.kind == InnerKind.DEAL || inbound.deal != null) return null
            if (!inbound.kind.isUserMessage || inbound.text == null) return null
            val meta = attachmentMeta(inbound)
            return MessageEntity(
                id = inbound.messageId,
                conversationId = conversationId,
                type = messageType(meta),
                isOutgoing = false,
                localPlaintext = inbound.text,
                ciphertext = null,
                status = MessageStatus.DELIVERED.name,
                createdAtEpochMs = nowMs,
                receiptSessionId = inbound.sessionId,
                receiptCiphertextSha256 = inbound.ciphertextSha256,
                receiptEpoch = inbound.epoch,
                replyToJson = inbound.replyTo?.toJson(),
            )
        }

        fun attachmentMeta(inbound: InboundRecord): AttachmentMetadata? =
            if (inbound.kind == InnerKind.ATTACHMENT && inbound.text != null) {
                AttachmentMetadata.parseOrNull(inbound.text)
            } else {
                null
            }

        fun messageType(meta: AttachmentMetadata?): String = when {
            meta == null -> MessageType.TEXT.name
            meta.mediaClass == "image" || meta.mimeType.startsWith("image/") -> MessageType.IMAGE.name
            meta.mediaClass == "voice" || meta.mimeType.startsWith("audio/") -> MessageType.VOICE.name
            else -> MessageType.DOCUMENT.name
        }
    }
}
