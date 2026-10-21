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

    @Query("SELECT * FROM deals ORDER BY updatedAtEpochMs DESC, dealId ASC")
    fun listDeals(): List<DealRow>

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

    @Query("UPDATE deal_versions SET immutable = 1 WHERE dealId = :dealId AND versionId = :versionId")
    fun markVersionImmutable(dealId: String, versionId: String)

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

    @Query("SELECT * FROM deal_audit_events WHERE dealId = :dealId ORDER BY createdAtEpochMs ASC, eventId ASC")
    fun audits(dealId: String): List<DealAuditRow>

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

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertDraft(row: DealDraftRow)

    @Query("SELECT * FROM deal_drafts WHERE draftId = :draftId")
    fun findDraft(draftId: String): DealDraftRow?

    @Query("SELECT * FROM deal_drafts WHERE dealId = :dealId")
    fun findDraftByDealId(dealId: String): DealDraftRow?

    @Query(
        "SELECT * FROM deal_drafts WHERE conversationId = :conversationId AND status = 'LOCAL_DRAFT' " +
            "ORDER BY updatedAtEpochMs DESC, draftId ASC",
    )
    fun activeDrafts(conversationId: String): List<DealDraftRow>

    @Query("SELECT COUNT(*) FROM deal_drafts")
    fun draftCount(): Int

    @Query("SELECT COUNT(*) FROM deal_signatures WHERE dealId = :dealId")
    fun signatureCount(dealId: String): Int

    @Query("SELECT COUNT(*) FROM deal_versions WHERE dealId = :dealId")
    fun versionCount(dealId: String): Int

    @Query("SELECT * FROM deal_versions WHERE dealId = :dealId")
    fun versions(dealId: String): List<DealVersionRow>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertTransport(row: DealTransportRow)

    @Query("SELECT * FROM deal_transport WHERE envelopeId = :envelopeId")
    fun findTransport(envelopeId: String): DealTransportRow?

    @Query(
        "SELECT * FROM deal_transport WHERE direction = :direction AND status = :status " +
            "ORDER BY createdAtEpochMs ASC, envelopeId ASC",
    )
    fun transportByStatus(direction: String, status: String): List<DealTransportRow>

    @Query(
        "SELECT * FROM deal_transport WHERE dealId = :dealId AND direction = :direction AND eventType = :eventType " +
            "ORDER BY createdAtEpochMs ASC, envelopeId ASC",
    )
    fun transportForDeal(dealId: String, direction: String, eventType: String): List<DealTransportRow>

    @Query(
        "UPDATE deal_transport SET status = :status, updatedAtEpochMs = :updatedAtEpochMs WHERE envelopeId = :envelopeId",
    )
    fun updateTransportStatus(envelopeId: String, status: String, updatedAtEpochMs: Long): Int

    @Query("SELECT COUNT(*) FROM deal_transport")
    fun transportCount(): Int

    @Query("SELECT COUNT(*) FROM deal_transport WHERE dealId = :dealId")
    fun transportCountForDeal(dealId: String): Int

    @Query("SELECT COUNT(*) FROM deal_closures WHERE dealId = :dealId")
    fun closureCountForDeal(dealId: String): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    fun insertClosure(row: DealClosureRow)

    @Query(
        "SELECT * FROM deal_closures WHERE dealId = :dealId AND versionId = :versionId " +
            "AND partyId = :partyId AND kind = :kind",
    )
    fun findClosure(dealId: String, versionId: String, partyId: String, kind: String): DealClosureRow?

    @Query("SELECT * FROM deal_closures WHERE dealId = :dealId AND versionId = :versionId")
    fun closures(dealId: String, versionId: String): List<DealClosureRow>

    @Query(
        "UPDATE deal_drafts SET sealedPayload = :sealedPayload, sealedPartyASigning = :sealedPartyASigning, " +
            "sealedPartyAEncryption = :sealedPartyAEncryption, updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE draftId = :draftId AND status = 'LOCAL_DRAFT'",
    )
    fun updateDraftContent(
        draftId: String,
        sealedPayload: ByteArray,
        sealedPartyASigning: ByteArray,
        sealedPartyAEncryption: ByteArray,
        updatedAtEpochMs: Long,
    ): Int

    @Query(
        "UPDATE deal_drafts SET status = 'CANCELLED', updatedAtEpochMs = :updatedAtEpochMs " +
            "WHERE draftId = :draftId AND status = 'LOCAL_DRAFT'",
    )
    fun cancelDraft(draftId: String, updatedAtEpochMs: Long): Int
}
