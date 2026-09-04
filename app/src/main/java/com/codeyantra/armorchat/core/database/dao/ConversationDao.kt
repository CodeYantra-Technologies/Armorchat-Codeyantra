package com.codeyantra.armorchat.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.codeyantra.armorchat.core.database.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ConversationDao {
    @Query("SELECT * FROM conversations ORDER BY updatedAtEpochMs DESC")
    fun observeAll(): Flow<List<ConversationEntity>>
}
