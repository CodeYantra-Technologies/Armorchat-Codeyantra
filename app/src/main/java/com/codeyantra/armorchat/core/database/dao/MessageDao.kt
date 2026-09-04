package com.codeyantra.armorchat.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>
}
