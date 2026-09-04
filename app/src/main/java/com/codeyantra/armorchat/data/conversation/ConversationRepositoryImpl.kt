package com.codeyantra.armorchat.data.conversation

import com.codeyantra.armorchat.core.database.dao.ConversationDao
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
) : ConversationRepository {
    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows ->
            rows.map { Conversation(it.id, it.title, it.updatedAtEpochMs) }
        }
}
