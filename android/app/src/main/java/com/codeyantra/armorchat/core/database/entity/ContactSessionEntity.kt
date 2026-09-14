package com.codeyantra.armorchat.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "contact_sessions")
data class ContactSessionEntity(
    @PrimaryKey val contactId: String,
    val peerPin: String,
    val relayOrigin: String,
    val mailboxId: String?,
    val encryptedOwnerToken: ByteArray?,
    val encryptedSubmitCapability: ByteArray?,
    val deviceId: String?,
    val binding: String?,
    val localRouteQr: String?,
    val peerRouteQr: String?,
    val mlsSnapshot: ByteArray?,
    val groupCreated: Boolean,
    val state: String,
    val updatedAtEpochMs: Long,
    val mlsSessionId: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as ContactSessionEntity

        if (contactId != other.contactId) return false
        if (peerPin != other.peerPin) return false
        if (relayOrigin != other.relayOrigin) return false
        if (mailboxId != other.mailboxId) return false
        if (encryptedOwnerToken != null) {
            if (other.encryptedOwnerToken == null) return false
            if (!encryptedOwnerToken.contentEquals(other.encryptedOwnerToken)) return false
        } else if (other.encryptedOwnerToken != null) return false
        if (encryptedSubmitCapability != null) {
            if (other.encryptedSubmitCapability == null) return false
            if (!encryptedSubmitCapability.contentEquals(other.encryptedSubmitCapability)) return false
        } else if (other.encryptedSubmitCapability != null) return false
        if (deviceId != other.deviceId) return false
        if (binding != other.binding) return false
        if (localRouteQr != other.localRouteQr) return false
        if (peerRouteQr != other.peerRouteQr) return false
        if (mlsSnapshot != null) {
            if (other.mlsSnapshot == null) return false
            if (!mlsSnapshot.contentEquals(other.mlsSnapshot)) return false
        } else if (other.mlsSnapshot != null) return false
        if (groupCreated != other.groupCreated) return false
        if (state != other.state) return false
        if (updatedAtEpochMs != other.updatedAtEpochMs) return false
        if (mlsSessionId != other.mlsSessionId) return false

        return true
    }

    override fun hashCode(): Int {
        var result = contactId.hashCode()
        result = 31 * result + peerPin.hashCode()
        result = 31 * result + relayOrigin.hashCode()
        result = 31 * result + (mailboxId?.hashCode() ?: 0)
        result = 31 * result + (encryptedOwnerToken?.contentHashCode() ?: 0)
        result = 31 * result + (encryptedSubmitCapability?.contentHashCode() ?: 0)
        result = 31 * result + (deviceId?.hashCode() ?: 0)
        result = 31 * result + (binding?.hashCode() ?: 0)
        result = 31 * result + (localRouteQr?.hashCode() ?: 0)
        result = 31 * result + (peerRouteQr?.hashCode() ?: 0)
        result = 31 * result + (mlsSnapshot?.contentHashCode() ?: 0)
        result = 31 * result + groupCreated.hashCode()
        result = 31 * result + state.hashCode()
        result = 31 * result + updatedAtEpochMs.hashCode()
        result = 31 * result + (mlsSessionId?.hashCode() ?: 0)
        return result
    }
}

