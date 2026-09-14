package com.codeyantra.armorchat.core.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/** Local v3 invite metadata. Token is not a mailbox owner credential. */
@Entity(tableName = "local_invites")
data class LocalInviteEntity(
    @PrimaryKey val id: String = ROW_ID,
    val token: String,
    val expiresAtEpochSeconds: Long,
    val inviteId: String,
    val updatedAtEpochMs: Long,
    @ColumnInfo(defaultValue = "ACTIVE") val state: String = "ACTIVE",
) {
    companion object {
        const val ROW_ID = "v3"
    }
}
