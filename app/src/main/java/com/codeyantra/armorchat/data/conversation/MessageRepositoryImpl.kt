package com.codeyantra.armorchat.data.conversation

import com.codeyantra.armorchat.core.common.AppDispatchers
import com.codeyantra.armorchat.core.database.dao.ConversationDao
import com.codeyantra.armorchat.core.database.dao.MessageDao
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.network.MessageTransport
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.domain.model.MessageType
import com.codeyantra.armorchat.domain.repository.MessageRepository
import com.codeyantra.armorchat.domain.repository.SendTextResult
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

@Singleton
class MessageRepositoryImpl @Inject constructor(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val transport: MessageTransport,
    private val dispatchers: AppDispatchers,
) : MessageRepository {
    override fun observeMessages(conversationId: String): Flow<List<Message>> =
        messageDao.observeByConversation(conversationId).map { rows -> rows.map { it.toDomain() } }

    override suspend fun sendText(conversationId: String, text: String): SendTextResult =
        withContext(dispatchers.io) {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return@withContext SendTextResult.Empty
            if (conversationDao.findById(conversationId) == null) {
                return@withContext SendTextResult.ConversationMissing
            }
            // Never invent SENT/DELIVERED. Transport is unavailable in this phase.
            val available = transport.isAvailable.first()
            if (!available) return@withContext SendTextResult.TransportUnavailable
            // Future: encrypt + enqueue. Still no fake receipts.
            SendTextResult.TransportUnavailable
        }

    private fun MessageEntity.toDomain() = Message(
        id = id,
        conversationId = conversationId,
        type = MessageType.valueOf(type),
        isOutgoing = isOutgoing,
        localPlaintext = localPlaintext,
        ciphertext = ciphertext,
        status = MessageStatus.valueOf(status),
        createdAtEpochMs = createdAtEpochMs,
    )
}
