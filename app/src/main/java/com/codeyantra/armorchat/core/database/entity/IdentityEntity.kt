package com.codeyantra.armorchat.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Public identity metadata only. Private key material is never stored in Room.
 */
@Entity(tableName = "identities")
data class IdentityEntity(
    @PrimaryKey val armorChatId: String,
    /** Base64url (unpadded) Ed25519 public key. */
    val publicIdentityKey: String,
    val createdAtEpochMs: Long,
    val version: Int,
    val displayName: String = "",
    val handle: String = "",
)
