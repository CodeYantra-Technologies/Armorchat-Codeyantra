package com.codeyantra.armorchat.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.codeyantra.armorchat.core.database.entity.ContactSessionEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactSessionDao {
    @Query("SELECT * FROM contact_sessions WHERE contactId = :contactId LIMIT 1")
    fun observe(contactId: String): Flow<ContactSessionEntity?>

    @Query("SELECT * FROM contact_sessions WHERE contactId = :contactId LIMIT 1")
    suspend fun find(contactId: String): ContactSessionEntity?

    @Query("SELECT * FROM contact_sessions WHERE peerPin = :peerPin LIMIT 1")
    suspend fun findByPeerPin(peerPin: String): ContactSessionEntity?

    @Query("SELECT * FROM contact_sessions")
    suspend fun all(): List<ContactSessionEntity>

    @Query("DELETE FROM contact_sessions WHERE contactId = :contactId")
    suspend fun delete(contactId: String)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: ContactSessionEntity)
}
