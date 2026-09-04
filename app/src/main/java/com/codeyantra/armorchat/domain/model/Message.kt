package com.codeyantra.armorchat.domain.model

/**
 * Domain message. Ciphertext only — plaintext never belongs in this model.
 */
data class Message(
    val id: String,
    val conversationId: String,
    val ciphertext: ByteArray?,
    val status: MessageStatus,
    val createdAtEpochMs: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Message) return false
        return id == other.id &&
            conversationId == other.conversationId &&
            ciphertext.contentEquals(other.ciphertext) &&
            status == other.status &&
            createdAtEpochMs == other.createdAtEpochMs
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + conversationId.hashCode()
        result = 31 * result + (ciphertext?.contentHashCode() ?: 0)
        result = 31 * result + status.hashCode()
        result = 31 * result + createdAtEpochMs.hashCode()
        return result
    }
}
