package com.codeyantra.armorchat.domain.model

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
            createdAtEpochMs == other.createdAtEpochMs
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
        return result
    }
}
