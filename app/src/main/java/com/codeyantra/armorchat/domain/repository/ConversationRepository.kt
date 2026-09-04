package com.codeyantra.armorchat.domain.repository

import com.codeyantra.armorchat.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
}
