package com.codeyantra.armorchat.domain.repository

import com.codeyantra.armorchat.domain.model.Message
import kotlinx.coroutines.flow.Flow

sealed interface SendTextResult {
    /** Transport / crypto not ready — nothing was persisted as SENT/DELIVERED. */
    data object TransportUnavailable : SendTextResult
    data object Empty : SendTextResult
    data object ConversationMissing : SendTextResult
}

interface MessageRepository {
    fun observeMessages(conversationId: String): Flow<List<Message>>

    /**
     * Phase 1C.1: never creates a successful outbound message.
     * Does not write SENT/DELIVERED/READ rows.
     */
    suspend fun sendText(conversationId: String, text: String): SendTextResult
}
