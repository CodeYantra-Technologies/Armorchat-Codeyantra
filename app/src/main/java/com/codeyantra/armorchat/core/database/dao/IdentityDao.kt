package com.codeyantra.armorchat.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.codeyantra.armorchat.core.database.entity.IdentityEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface IdentityDao {
    @Query("SELECT * FROM identities LIMIT 1")
    fun observe(): Flow<IdentityEntity?>

    @Query("SELECT * FROM identities LIMIT 1")
    suspend fun get(): IdentityEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: IdentityEntity)

    @Query("DELETE FROM identities")
    suspend fun clear()

    @Query("UPDATE identities SET displayName = :displayName, handle = :handle")
    suspend fun updateProfile(displayName: String, handle: String)
}
