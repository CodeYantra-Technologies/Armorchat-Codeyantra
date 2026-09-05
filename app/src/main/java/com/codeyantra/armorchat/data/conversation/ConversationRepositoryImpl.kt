package com.codeyantra.armorchat.data.conversation

import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.dao.ConversationDao
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class ConversationRepositoryImpl @Inject constructor(
    private val conversationDao: ConversationDao,
    private val clock: AppClock,
    private val dispatchers: AppDispatchers,
) : ConversationRepository {
    override fun observeConversations(): Flow<List<Conversation>> =
        conversationDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override fun observeConversation(id: String): Flow<Conversation?> =
        conversationDao.observeById(id).map { it?.toDomain() }

    override suspend fun getConversation(id: String): Conversation? = withContext(dispatchers.io) {
        conversationDao.findById(id)?.toDomain()
    }

    override suspend fun getOrCreateForContact(contact: Contact): Conversation = withContext(dispatchers.io) {
        conversationDao.findByContactId(contact.id)?.toDomain()?.let { return@withContext it }
        val title = contact.displayName.ifBlank { "ArmorChat contact" }
        val row = ConversationEntity(
            id = UUID.randomUUID().toString(),
            contactId = contact.id,
            title = title,
            updatedAtEpochMs = clock.nowEpochMs(),
        )
        val inserted = conversationDao.insert(row)
        if (inserted != -1L) {
            row.toDomain()
        } else {
            conversationDao.findByContactId(contact.id)?.toDomain()
                ?: error("Conversation unique constraint race without row")
        }
    }

    private fun ConversationEntity.toDomain() =
        Conversation(id = id, contactId = contactId, title = title, updatedAtEpochMs = updatedAtEpochMs)
}
