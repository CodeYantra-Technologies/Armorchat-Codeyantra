package com.codeyantra.armorchat.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.codeyantra.armorchat.core.database.entity.LocalInviteEntity

@Dao
interface LocalInviteDao {
    @Query("SELECT * FROM local_invites WHERE id = :id LIMIT 1")
    suspend fun get(id: String = LocalInviteEntity.ROW_ID): LocalInviteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: LocalInviteEntity)

    @Query("DELETE FROM local_invites")
    suspend fun clear()
}
