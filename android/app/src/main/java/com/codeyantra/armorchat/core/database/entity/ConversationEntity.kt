package com.codeyantra.armorchat.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "conversations",
    indices = [Index(value = ["contactId"], unique = true)],
)
data class ConversationEntity(
    @PrimaryKey val id: String,
    val contactId: String,
    val title: String?,
    val updatedAtEpochMs: Long,
)
