package com.codeyantra.armorchat.domain.model

import com.codeyantra.armorchat.messaging.ReplyMetadata

/**
 * Domain message. Ciphertext is the durable payload once crypto exists.
 * [localPlaintext] is reserved for local UI drafts / failed attempts only —
 * it must never be treated as proof of encryption or successful delivery.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val type: MessageType,
    val isOutgoing: Boolean,
    val localPlaintext: String?,
    val ciphertext: ByteArray?,
    val status: MessageStatus,
    val createdAtEpochMs: Long,
    val replyTo: ReplyMetadata? = null,
    val deliveredAtEpochMs: Long? = null,
    val readAtEpochMs: Long? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id &&
            conversationId == other.conversationId &&
            type == other.type &&
            isOutgoing == other.isOutgoing &&
            localPlaintext == other.localPlaintext &&
            ciphertext.contentEquals(other.ciphertext) &&
            status == other.status &&
            createdAtEpochMs == other.createdAtEpochMs &&
            replyTo == other.replyTo &&
            deliveredAtEpochMs == other.deliveredAtEpochMs &&
            readAtEpochMs == other.readAtEpochMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + type.hashCode()
        result = 31 * result + isOutgoing.hashCode()
        result = 31 * result + (localPlaintext?.hashCode() ?: 0)
        result = 31 * result + (ciphertext?.contentHashCode() ?: 0)
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAtEpochMs.hashCode()
        result = 31 * result + (replyTo?.hashCode() ?: 0)
        result = 31 * result + (deliveredAtEpochMs?.hashCode() ?: 0)
        result = 31 * result + (readAtEpochMs?.hashCode() ?: 0)
        return result
    }
}
