package com.codeyantra.armorchat.deal.storage

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Armor Deal rows. Contract terms are not columns. [DealVersionRow.sealedDocument]
 * is ciphertext. This database is not [com.codeyantra.armorchat.core.database.ArmorChatDatabase].
 */
@Entity(tableName = "deals")
data class DealRow(
    @PrimaryKey val dealId: String,
    val status: String,
    val currentVersionId: String,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "deal_parties",
    primaryKeys = ["dealId", "partyId"],
    foreignKeys = [
        ForeignKey(
            entity = DealRow::class,
            parentColumns = ["dealId"],
            childColumns = ["dealId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("dealId")],
)
data class DealPartyRow(
    val dealId: String,
    val partyId: String,
    val role: String,
    val label: String,
    val signingPublicKey: String,
    val encryptionPublicKey: String,
)

@Entity(
    tableName = "deal_versions",
    primaryKeys = ["dealId", "versionId"],
    foreignKeys = [
        ForeignKey(
            entity = DealRow::class,
            parentColumns = ["dealId"],
            childColumns = ["dealId"],
            onDelete = ForeignKey.NO_ACTION,
        ),
    ],
    indices = [Index("dealId")],
)
data class DealVersionRow(
    val dealId: String,
    val versionId: String,
    val parentVersionId: String?,
    val documentSha256: String,
    val sealedDocument: ByteArray,
    val superseded: Boolean,
    val immutable: Boolean,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "deal_signatures",
    primaryKeys = ["dealId", "versionId", "partyId", "action"],
    indices = [Index("dealId")],
)
data class DealSignatureRow(
    val dealId: String,
    val versionId: String,
    val partyId: String,
    val action: String,
    val signature: ByteArray,
    val signerPublicKey: String,
)

@Entity(
    tableName = "deal_backup_receipts",
    primaryKeys = ["dealId", "versionId", "partyId"],
    indices = [Index("dealId")],
)
data class DealBackupReceiptRow(
    val dealId: String,
    val versionId: String,
    val partyId: String,
    val objectKey: String,
    val ciphertextSha256: String,
    val confirmedAtEpochMs: Long,
)

@Entity(tableName = "deal_audit_events")
data class DealAuditRow(
    @PrimaryKey val eventId: String,
    val dealId: String,
    val action: String,
    val outcome: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "deal_recovery_meta",
    primaryKeys = ["partyId", "dealId"],
)
data class DealRecoveryMetaRow(
    val partyId: String,
    val dealId: String,
    val formatVersion: Int,
    val publicKeyFingerprint: String,
    val createdAtEpochMs: Long,
)

@Entity(tableName = "deal_local_keys")
data class DealLocalKeyRow(
    @PrimaryKey val partyId: String,
    val sealedSigningKeyset: ByteArray,
    val sealedEncryptionKeyset: ByteArray,
    val signingPublicKey: String,
    val encryptionPublicKey: String,
)
