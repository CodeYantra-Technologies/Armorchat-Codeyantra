package com.codeyantra.armorchat.domain.repository

import com.codeyantra.armorchat.domain.model.Message
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

sealed interface SendTextResult {
    /** Transport / crypto not ready — nothing was persisted as SENT/DELIVERED. */
    data object TransportUnavailable : SendTextResult
    data object Empty : SendTextResult
    data object ConversationMissing : SendTextResult
    data object SessionNotReady : SendTextResult
    data object NetworkUnavailable : SendTextResult
    data class Queued(val messageId: String, val status: com.codeyantra.armorchat.domain.model.MessageStatus) : SendTextResult
}

interface MessageRepository {
    fun observeMessages(conversationId: String): Flow<List<Message>>
    fun observeAllMessages(): Flow<List<Message>> = emptyFlow()

    /**
     * Phase 1C.1: never creates a successful outbound message.
     * Does not write SENT/DELIVERED/READ rows.
     */
    suspend fun sendText(conversationId: String, text: String): SendTextResult
}
