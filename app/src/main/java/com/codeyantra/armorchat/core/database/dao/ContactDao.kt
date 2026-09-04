package com.codeyantra.armorchat.core.database.dao

import androidx.room.Dao
import androidx.room.Query
import com.codeyantra.armorchat.core.database.entity.ContactEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ContactDao {
    @Query("SELECT * FROM contacts ORDER BY displayName COLLATE NOCASE")
    fun observeAll(): Flow<List<ContactEntity>>
}
