package com.codeyantra.armorchat.domain.repository

import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import kotlinx.coroutines.flow.Flow

interface ConversationRepository {
    fun observeConversations(): Flow<List<Conversation>>
    fun observeConversation(id: String): Flow<Conversation?>
    suspend fun getConversation(id: String): Conversation?
    /** Find or create exactly one local conversation for [contact]. Never duplicates. */
    suspend fun getOrCreateForContact(contact: Contact): Conversation
}
