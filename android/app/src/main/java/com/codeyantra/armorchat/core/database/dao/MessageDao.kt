package com.codeyantra.armorchat.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.codeyantra.armorchat.core.database.entity.MessageEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE conversationId = :conversationId ORDER BY createdAtEpochMs ASC")
    fun observeByConversation(conversationId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY createdAtEpochMs ASC")
    fun observeAllMessages(): Flow<List<MessageEntity>>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): MessageEntity?

    @Query("SELECT COUNT(*) FROM messages WHERE conversationId = :conversationId AND isOutgoing = 0")
    suspend fun countInbound(conversationId: String): Int

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    suspend fun updateStatus(id: String, status: String)

    @Query("UPDATE messages SET status = :status, deliveredAtEpochMs = COALESCE(deliveredAtEpochMs, :timestamp) WHERE id = :id")
    suspend fun updateStatusDelivered(id: String, status: String, timestamp: Long)

    @Query("UPDATE messages SET status = :status, readAtEpochMs = COALESCE(readAtEpochMs, :timestamp) WHERE id = :id")
    suspend fun updateStatusRead(id: String, status: String, timestamp: Long)
}
