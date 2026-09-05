package com.codeyantra.armorchat.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "contacts", indices = [Index(value = ["armorChatId"], unique = true)])
data class ContactEntity(
    @PrimaryKey val id: String,
    val armorChatId: String,
    val publicIdentityKey: String,
    val displayName: String,
    val createdAtEpochMs: Long,
    val verificationMethod: String,
)
