package com.codeyantra.armorchat.core.database.dao

import androidx.room.*
import com.codeyantra.armorchat.core.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE, createdAtEpochMs, id")
    fun observeAll(): Flow<List<ContactEntity>>

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    fun observeById(id: String): Flow<ContactEntity?>

    @Query("SELECT * FROM contacts WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE armorChatId = :armorChatId LIMIT 1")
    suspend fun findByArmorChatId(armorChatId: String): ContactEntity?

    @Query("SELECT * FROM contacts WHERE publicIdentityKey = :publicIdentityKey LIMIT 1")
    suspend fun findByPublicIdentityKey(publicIdentityKey: String): ContactEntity?

    @Query("SELECT armorChatId FROM identities LIMIT 1")
    suspend fun localArmorChatId(): String?

    // IGNORE never overwrites pinned identity material, including concurrent imports.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(contact: ContactEntity): Long

    @Query("UPDATE contacts SET displayName = :name WHERE id = :id")
    suspend fun rename(id: String, name: String)

    @Query("DELETE FROM contacts WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query(
        """
        UPDATE contacts SET
            displayName = :displayName,
            handle = :handle,
            previousHandle = :previousHandle,
            handleChangedAtEpochMs = :changedAt
        WHERE id = :id
        """,
    )
    suspend fun updateSignedProfile(
        id: String,
        displayName: String,
        handle: String?,
        previousHandle: String?,
        changedAt: Long?,
    )
}
