package com.codeyantra.armorchat.core.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.codeyantra.armorchat.core.database.entity.MeshCustodyEntity

@Dao
interface MeshCustodyDao {
    @Query("SELECT * FROM mesh_custody")
    suspend fun all(): List<MeshCustodyEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: MeshCustodyEntity)

    @Query("DELETE FROM mesh_custody WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM mesh_custody WHERE expireAtMs <= :nowMs")
    suspend fun deleteExpired(nowMs: Long)
}
