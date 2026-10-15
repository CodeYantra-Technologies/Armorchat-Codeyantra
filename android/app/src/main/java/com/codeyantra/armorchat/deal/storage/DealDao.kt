package com.codeyantra.armorchat.deal.storage

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface DealDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDeal(row: DealRow)

    @Query("SELECT * FROM deals WHERE dealId = :dealId")
    fun findDeal(dealId: String): DealRow?

    @Query("UPDATE deals SET status = :status, currentVersionId = :versionId, updatedAtEpochMs = :updatedAt WHERE dealId = :dealId")
    fun updateDeal(dealId: String, status: String, versionId: String, updatedAt: Long)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertParty(row: DealPartyRow)

    @Query("SELECT * FROM deal_parties WHERE dealId = :dealId AND partyId = :partyId")
    fun findParty(dealId: String, partyId: String): DealPartyRow?

    @Query("SELECT * FROM deal_parties WHERE dealId = :dealId ORDER BY partyId")
    fun parties(dealId: String): List<DealPartyRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertVersion(row: DealVersionRow)

    @Query("SELECT * FROM deal_versions WHERE dealId = :dealId AND versionId = :versionId")
    fun findVersion(dealId: String, versionId: String): DealVersionRow?

    @Query("UPDATE deal_versions SET superseded = 1 WHERE dealId = :dealId AND versionId = :versionId")
    fun supersede(dealId: String, versionId: String)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertSignature(row: DealSignatureRow)

    @Query("SELECT * FROM deal_signatures WHERE dealId = :dealId AND versionId = :versionId")
    fun signatures(dealId: String, versionId: String): List<DealSignatureRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertReceipt(row: DealBackupReceiptRow)

    @Query("SELECT * FROM deal_backup_receipts WHERE dealId = :dealId AND versionId = :versionId")
    fun receipts(dealId: String, versionId: String): List<DealBackupReceiptRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertAudit(row: DealAuditRow)

    @Query("SELECT COUNT(*) FROM deal_audit_events")
    fun auditCount(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertRecovery(row: DealRecoveryMetaRow)

    @Query("SELECT * FROM deal_recovery_meta WHERE partyId = :partyId AND dealId = :dealId")
    fun findRecovery(partyId: String, dealId: String): DealRecoveryMetaRow?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertKeys(row: DealLocalKeyRow)

    @Query("SELECT * FROM deal_local_keys WHERE partyId = :partyId")
    fun findKeys(partyId: String): DealLocalKeyRow?

    @Query("SELECT sealedDocument FROM deal_versions WHERE dealId = :dealId AND versionId = :versionId")
    fun sealedBytes(dealId: String, versionId: String): ByteArray?
}
