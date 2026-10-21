package com.codeyantra.armorchat.deal.storage

import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealKeyProtector
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealConflictException
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealIntegrityException
import com.codeyantra.armorchat.deal.domain.FinalityDefect
import com.codeyantra.armorchat.deal.domain.FinalityVerdict
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealKeyException
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealRecoveryException
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.DealVersions
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.deal.recovery.DealRecovery
import com.codeyantra.armorchat.deal.recovery.RecoveredDeal
import com.codeyantra.armorchat.domain.identity.Base64Url

/**
 * Encrypts contract bytes before they touch the Deal database. A failed
 * recovery throws before the write transaction starts.
 */
class DealRepository(
    private val database: DealDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val recovery: DealRecovery = DealRecovery(),
) {
    private val dao = database.dealDao()

    fun saveVerified(
        deal: CanonicalDeal,
        sealed: SealedDocument,
        signatures: List<DealSignature>,
        localPartyId: String,
        localKeys: DealKeyMaterial,
        protector: DealKeyProtector,
        status: String,
    ) {
        verifyStored(deal, sealed, signatures, localPartyId, localKeys)
        val requested = DealStatus.valueOf(status)
        if (DealLifecycle.claimsFinality(requested)) {
            val verdict = DealFinality.assess(
                deal = deal,
                signatures = signatures,
                pinnedSigningKeys = deal.parties.associate { it.partyId to it.signingPublicKey },
            )
            if (!verdict.proven) throw DealIntegrityException(verdict.defect ?: FinalityDefect.MISSING_SIGNATURE)
        }
        val signingBytes = localKeys.serializedSigning()
        val encryptionBytes = localKeys.serializedEncryption()
        val sealedSigning = protector.seal(signingBytes, SIGNING_PURPOSE)
        val sealedEncryption = protector.seal(encryptionBytes, ENCRYPTION_PURPOSE)
        signingBytes.fill(0)
        encryptionBytes.fill(0)
        val now = clock()
        val hash = Base64Url.encode(deal.documentHash())
        database.runInTransaction {
            val existingDeal = dao.findDeal(deal.dealId)
            if (existingDeal != null) {
                val from = DealStatus.valueOf(existingDeal.status)
                if (DealLifecycle.claimsFinality(from)) {
                    val to = DealStatus.valueOf(status)
                    if (!DealLifecycle.allowsFinalizedStatus(from, to) || existingDeal.currentVersionId != deal.versionId) {
                        throw ImmutableVersionException()
                    }
                }
            }
            if (existingDeal == null) {
                dao.insertDeal(
                    DealRow(
                        dealId = deal.dealId,
                        status = status,
                        currentVersionId = deal.versionId,
                        createdAtEpochMs = deal.createdAtEpochMs,
                        updatedAtEpochMs = now,
                    ),
                )
                deal.parties.forEach { party ->
                    dao.insertParty(
                        DealPartyRow(
                            dealId = deal.dealId,
                            partyId = party.partyId,
                            role = party.role.name,
                            label = party.label,
                            signingPublicKey = Base64Url.encode(party.signingPublicKey),
                            encryptionPublicKey = Base64Url.encode(party.encryptionPublicKey),
                        ),
                    )
                }
            }
            val existing = dao.findVersion(deal.dealId, deal.versionId)
            val encoded = sealed.encode()
            if (existing == null) {
                if (deal.parentVersionId != null) dao.supersede(deal.dealId, deal.parentVersionId)
                dao.insertVersion(
                    DealVersionRow(
                        dealId = deal.dealId,
                        versionId = deal.versionId,
                        parentVersionId = deal.parentVersionId,
                        documentSha256 = hash,
                        sealedDocument = encoded,
                        superseded = false,
                        immutable = signatures.count { it.action == SignAction.SIGN } == 2,
                        createdAtEpochMs = deal.createdAtEpochMs,
                    ),
                )
            } else if (existing.documentSha256 != hash || !existing.sealedDocument.contentEquals(encoded)) {
                throw DealConflictException()
            }
            signatures.forEach { signature ->
                val already = dao.signatures(deal.dealId, deal.versionId).any {
                    it.partyId == signature.partyId && it.action == signature.action.name
                }
                if (!already) {
                    dao.insertSignature(
                        DealSignatureRow(
                            dealId = deal.dealId,
                            versionId = deal.versionId,
                            partyId = signature.partyId,
                            action = signature.action.name,
                            signature = signature.signature,
                            signerPublicKey = Base64Url.encode(signature.signerPublicKey),
                        ),
                    )
                }
            }
            if (dao.findKeys(localPartyId) == null) {
                dao.insertKeys(
                    DealLocalKeyRow(
                        partyId = localPartyId,
                        sealedSigningKeyset = sealedSigning,
                        sealedEncryptionKeyset = sealedEncryption,
                        signingPublicKey = Base64Url.encode(localKeys.signingPublic),
                        encryptionPublicKey = Base64Url.encode(localKeys.encryptionPublic),
                    ),
                )
            }
            if (dao.findRecovery(localPartyId, deal.dealId) == null) {
                dao.insertRecovery(
                    DealRecoveryMetaRow(
                        partyId = localPartyId,
                        dealId = deal.dealId,
                        formatVersion = DealFeature.RECOVERY_FORMAT_VERSION,
                        publicKeyFingerprint = Base64Url.encode(sha256(localKeys.signingPublic)),
                        createdAtEpochMs = now,
                    ),
                )
            }
            dao.updateDeal(deal.dealId, status, deal.versionId, now)
            dao.insertAudit(
                DealAuditRow(
                    eventId = DealIds.generate(),
                    dealId = deal.dealId,
                    action = "local_save",
                    outcome = "ok",
                    createdAtEpochMs = now,
                ),
            )
        }
    }

    fun loadTerms(dealId: String, partyId: String, protector: DealKeyProtector): String =
        loadCanonical(dealId, partyId, protector).terms

    /**
     * Reads finality from signatures and the canonical hash. Does not write.
     * A stored status is ignored here.
     */
    fun assessStored(dealId: String, partyId: String, protector: DealKeyProtector): FinalityVerdict =
        readStored(dealId, partyId, protector).verdict

    /** Opens the canonical document. A claimed-final row must prove two signatures. */
    fun loadCanonical(dealId: String, partyId: String, protector: DealKeyProtector): CanonicalDeal {
        val stored = readStored(dealId, partyId, protector)
        val status = DealStatus.valueOf(stored.status)
        val defect = stored.verdict.defect
        if (defect == FinalityDefect.DIFFERENT_HASH ||
            defect == FinalityDefect.INCONSISTENT_VERSION ||
            defect == FinalityDefect.WRONG_DEAL ||
            defect == FinalityDefect.WRONG_VERSION
        ) {
            throw DealIntegrityException(defect)
        }
        if (DealLifecycle.claimsFinality(status)) {
            if (!stored.verdict.proven) {
                throw DealIntegrityException(defect ?: FinalityDefect.MISSING_SIGNATURE)
            }
            return stored.deal
        }
        if (stored.signatures.isEmpty()) throw DealRecoveryException.Rejected()
        stored.signatures.forEach { signature ->
            DealSignatures.verify(
                deal = stored.deal,
                claimed = signature,
                expectedAction = signature.action,
                expectedDealId = stored.deal.dealId,
                expectedVersionId = stored.deal.versionId,
                pinnedPublicKey = stored.deal.party(signature.partyId).signingPublicKey,
            )
        }
        return stored.deal
    }

    fun restore(packageBytes: ByteArray, secret: ByteArray, protector: DealKeyProtector): RecoveredDeal {
        val recovered = recovery.open(packageBytes, secret)
        saveVerified(
            deal = recovered.deal,
            sealed = recovered.sealed,
            signatures = recovered.signatures,
            localPartyId = recovered.partyId,
            localKeys = recovered.keys,
            protector = protector,
            status = "FULLY_SIGNED",
        )
        return recovered
    }

    /**
     * Cancels an agreement that is not yet finalized. A proven two-signature
     * document is refused even when its stored status does not say so.
     * Signature rows, version rows, and earlier audit rows stay in place.
     */
    fun cancel(dealId: String, protector: DealKeyProtector) = applyPreFinal(dealId, DealEvent.CANCEL, protector)

    /** Expires an agreement that is not yet finalized. Stored evidence stays. */
    fun expire(dealId: String, protector: DealKeyProtector) = applyPreFinal(dealId, DealEvent.EXPIRE, protector)

    /**
     * Stores a new version after exactly one SIGN signature. The parent version
     * and that signature remain. No signature is written for [successor].
     * A second call with the same successor bytes is a no-op.
     */
    fun saveChangeRequest(
        successor: CanonicalDeal,
        sealed: SealedDocument,
        localPartyId: String,
        localKeys: DealKeyMaterial,
    ) {
        val plain = DealCipher.decrypt(sealed, localPartyId, localKeys.encryptionHandle())
        if (!plain.contentEquals(successor.canonicalBytes())) throw DealRecoveryException.Rejected()
        plain.fill(0)
        val parentId = successor.parentVersionId
            ?: throw DealStateException("A change request needs the signed version as its parent.")
        val now = clock()
        val hash = Base64Url.encode(successor.documentHash())
        database.runInTransaction {
            val row = dao.findDeal(successor.dealId) ?: throw DealRecoveryException.MissingObject()
            val parent = dao.findVersion(successor.dealId, parentId) ?: throw DealRecoveryException.MissingObject()
            val stored = dao.signatures(successor.dealId, parentId).map { it.toSignature() }
            val historical = DealVersions.historicalSignatures(stored, parentId, successor.versionId)
            val signCount = historical.count { it.action == SignAction.SIGN }
            if (parent.immutable || signCount >= 2) throw ImmutableVersionException()
            if (signCount != 1) {
                throw DealStateException("A change request after one signature needs that signature on the parent version.")
            }
            val existing = dao.findVersion(successor.dealId, successor.versionId)
            if (existing == null) {
                if (row.currentVersionId != parentId) throw DealConflictException()
                val from = DealStatus.valueOf(row.status)
                if (from != DealStatus.PARTIALLY_SIGNED) throw ImmutableVersionException()
                requireStoredParticipants(parent.sealedDocument, successor, localPartyId, localKeys)
                val next = DealLifecycle.transition(from, DealEvent.SUPERSEDE)
                if (hash == parent.documentSha256) {
                    throw DealFormatException("Revision did not change the canonical document.")
                }
                dao.supersede(successor.dealId, parentId)
                dao.insertVersion(
                    DealVersionRow(
                        dealId = successor.dealId,
                        versionId = successor.versionId,
                        parentVersionId = parentId,
                        documentSha256 = hash,
                        sealedDocument = sealed.encode(),
                        superseded = false,
                        immutable = false,
                        createdAtEpochMs = successor.createdAtEpochMs,
                    ),
                )
                if (dao.signatures(successor.dealId, successor.versionId).isNotEmpty()) {
                    throw DealConflictException()
                }
                dao.updateDeal(successor.dealId, next.name, successor.versionId, now)
                dao.insertAudit(
                    DealAuditRow(
                        eventId = DealIds.generate(),
                        dealId = successor.dealId,
                        action = "supersede",
                        outcome = "ok",
                        createdAtEpochMs = now,
                    ),
                )
            } else if (existing.documentSha256 != hash || !existing.sealedDocument.contentEquals(sealed.encode())) {
                throw DealConflictException()
            } else if (dao.signatures(successor.dealId, successor.versionId).isNotEmpty()) {
                throw DealConflictException()
            }
        }
    }

    fun recordReceipt(row: DealBackupReceiptRow) {
        database.runInTransaction {
            if (dao.findDeal(row.dealId) == null) throw DealRecoveryException.MissingObject()
            val existing = dao.receipts(row.dealId, row.versionId).firstOrNull { it.partyId == row.partyId }
            if (existing == null) {
                dao.insertReceipt(row)
            } else if (existing.ciphertextSha256 != row.ciphertextSha256 || existing.objectKey != row.objectKey) {
                throw DealConflictException()
            }
        }
    }

    fun sealedDocument(dealId: String, versionId: String): ByteArray? = dao.sealedBytes(dealId, versionId)

    fun listDeals(): List<DealRow> = dao.listDeals()

    fun audits(dealId: String): List<DealAuditRow> = dao.audits(dealId)

    fun storedLocalPartyId(dealId: String): String? =
        dao.parties(dealId).firstOrNull { dao.findKeys(it.partyId) != null }?.partyId

    /**
     * Records a local status change that the caller has already verified.
     * Armor Deal Lab uses this only after a local recovery package opens and
     * matches. It does not upload anything.
     */
    fun recordMilestone(
        dealId: String,
        status: String,
        action: String,
        outcome: String,
        protector: DealKeyProtector,
    ) {
        val now = clock()
        val row = dao.findDeal(dealId) ?: throw DealRecoveryException.MissingObject()
        val from = DealStatus.valueOf(row.status)
        val to = DealStatus.valueOf(status)
        val reader = storedLocalPartyId(dealId) ?: throw DealRecoveryException.MissingObject()
        val verdict = assessStored(dealId, reader, protector)
        if ((DealLifecycle.claimsFinality(from) || DealLifecycle.claimsFinality(to)) && !verdict.proven) {
            rejectFinality(dealId, now)
            throw DealIntegrityException(verdict.defect ?: FinalityDefect.MISSING_SIGNATURE)
        }
        if (verdict.proven && !DealLifecycle.claimsFinality(from)) {
            rejectFinality(dealId, now)
            throw ImmutableVersionException()
        }
        if (DealLifecycle.claimsFinality(from) && !DealLifecycle.allowsFinalizedStatus(from, to)) {
            throw ImmutableVersionException()
        }
        val defect = verdict.defect
        if (!verdict.proven && defect != null && defect != FinalityDefect.MISSING_SIGNATURE) {
            rejectFinality(dealId, now)
            throw DealIntegrityException(defect)
        }
        database.runInTransaction {
            dao.updateDeal(dealId, status, row.currentVersionId, now)
            dao.insertAudit(
                DealAuditRow(
                    eventId = DealIds.generate(),
                    dealId = dealId,
                    action = action,
                    outcome = outcome,
                    createdAtEpochMs = now,
                ),
            )
        }
    }

    private fun applyPreFinal(dealId: String, event: DealEvent, protector: DealKeyProtector) {
        if (event != DealEvent.CANCEL && event != DealEvent.EXPIRE) {
            throw DealStateException("Only cancellation or expiry can be recorded here.")
        }
        val now = clock()
        val row = dao.findDeal(dealId) ?: throw DealRecoveryException.MissingObject()
        val next = try {
            DealLifecycle.transition(DealStatus.valueOf(row.status), event)
        } catch (e: DealStateException) {
            rejectStatus(dealId, event.name.lowercase(), now)
            throw e
        }
        val reader = storedLocalPartyId(dealId) ?: throw DealRecoveryException.MissingObject()
        val verdict = assessStored(dealId, reader, protector)
        if (verdict.proven) {
            rejectStatus(dealId, event.name.lowercase(), now)
            throw ImmutableVersionException()
        }
        val defect = verdict.defect
        if (defect != null && defect != FinalityDefect.MISSING_SIGNATURE) {
            rejectFinality(dealId, now)
            throw DealIntegrityException(defect)
        }
        database.runInTransaction {
            val current = dao.findDeal(dealId) ?: throw DealRecoveryException.MissingObject()
            if (current.status != row.status || current.currentVersionId != row.currentVersionId) {
                throw DealConflictException()
            }
            dao.updateDeal(dealId, next.name, current.currentVersionId, now)
            dao.insertAudit(
                DealAuditRow(
                    eventId = DealIds.generate(),
                    dealId = dealId,
                    action = event.name.lowercase(),
                    outcome = "ok",
                    createdAtEpochMs = now,
                ),
            )
        }
    }

    private fun rejectStatus(dealId: String, action: String, atEpochMs: Long) {
        if (dao.findDeal(dealId) == null) return
        database.runInTransaction {
            dao.insertAudit(
                DealAuditRow(
                    eventId = DealIds.generate(),
                    dealId = dealId,
                    action = action,
                    outcome = "rejected",
                    createdAtEpochMs = atEpochMs,
                ),
            )
        }
    }

    private fun rejectFinality(dealId: String, atEpochMs: Long) {
        if (dao.findDeal(dealId) == null) return
        database.runInTransaction {
            dao.insertAudit(
                DealAuditRow(
                    eventId = DealIds.generate(),
                    dealId = dealId,
                    action = "finality",
                    outcome = "rejected",
                    createdAtEpochMs = atEpochMs,
                ),
            )
        }
    }

    /**
     * The parent document's parties and identity pins must still be the successor's.
     * A version 1 or 2 parent cannot be stored again as version 3.
     */
    private fun requireStoredParticipants(
        sealedParent: ByteArray,
        successor: CanonicalDeal,
        localPartyId: String,
        localKeys: DealKeyMaterial,
    ) {
        val plain = DealCipher.decrypt(SealedDocument.parse(sealedParent), localPartyId, localKeys.encryptionHandle())
        try {
            val parent = CanonicalEncoding.parse(plain)
            if (parent.dealId != successor.dealId) throw DealRecoveryException.Rejected()
            DealIdentity.requireSameParticipants(parent, successor)
        } finally {
            plain.fill(0)
        }
    }

    private class StoredAgreement(
        val status: String,
        val deal: CanonicalDeal,
        val signatures: List<DealSignature>,
        val verdict: FinalityVerdict,
    )

    private fun readStored(dealId: String, partyId: String, protector: DealKeyProtector): StoredAgreement {
        val row = dao.findDeal(dealId) ?: throw DealRecoveryException.MissingObject()
        val version = dao.findVersion(dealId, row.currentVersionId) ?: throw DealRecoveryException.MissingObject()
        val keys = dao.findKeys(partyId) ?: throw DealRecoveryException.MissingObject()
        var signing = ByteArray(0)
        var encryption = ByteArray(0)
        try {
            signing = protector.open(keys.sealedSigningKeyset, SIGNING_PURPOSE)
            encryption = protector.open(keys.sealedEncryptionKeyset, ENCRYPTION_PURPOSE)
            val material = DealKeyMaterial.fromSerialized(signing, encryption)
            val sealed = SealedDocument.parse(version.sealedDocument)
            val plain = DealCipher.decrypt(sealed, partyId, material.encryptionHandle())
            try {
                val deal = CanonicalEncoding.parse(plain)
                val signatures = try {
                    dao.signatures(dealId, version.versionId).map { it.toSignature() }
                } catch (e: IllegalArgumentException) {
                    return StoredAgreement(row.status, deal, emptyList(), FinalityVerdict.failed(FinalityDefect.MISMATCHED_KEY))
                } catch (e: DealFormatException) {
                    return StoredAgreement(row.status, deal, emptyList(), FinalityVerdict.failed(FinalityDefect.INVALID_SIGNATURE))
                }
                val pinned = try {
                    dao.parties(dealId).associate { party ->
                        party.partyId to Base64Url.decode(party.signingPublicKey)
                    }
                } catch (e: IllegalArgumentException) {
                    return StoredAgreement(row.status, deal, signatures, FinalityVerdict.failed(FinalityDefect.MISMATCHED_KEY))
                }
                val storedHash = try {
                    Base64Url.decode(version.documentSha256)
                } catch (e: IllegalArgumentException) {
                    ByteArray(0)
                }
                val verdict = DealFinality.assess(
                    deal = deal,
                    signatures = signatures,
                    pinnedSigningKeys = pinned,
                    storedVersionId = version.versionId,
                    storedDocumentSha256 = storedHash,
                )
                return StoredAgreement(row.status, deal, signatures, verdict)
            } finally {
                plain.fill(0)
            }
        } catch (e: DealKeyException) {
            throw e
        } finally {
            signing.fill(0)
            encryption.fill(0)
        }
    }

    private fun verifyStored(
        deal: CanonicalDeal,
        sealed: SealedDocument,
        signatures: List<DealSignature>,
        partyId: String,
        keys: DealKeyMaterial,
    ) {
        val plain = DealCipher.decrypt(sealed, partyId, keys.encryptionHandle())
        if (!plain.contentEquals(deal.canonicalBytes())) throw DealRecoveryException.Rejected()
        plain.fill(0)
        if (signatures.none { it.action == SignAction.SIGN && it.partyId == partyId }) {
            throw DealRecoveryException.Rejected()
        }
        signatures.forEach { signature ->
            DealSignatures.verify(
                deal, signature, signature.action, deal.dealId, deal.versionId,
                deal.party(signature.partyId).signingPublicKey,
            )
        }
    }

    private fun DealSignatureRow.toSignature(): DealSignature = DealSignature(
        dealId = dealId,
        versionId = versionId,
        partyId = partyId,
        action = SignAction.valueOf(action),
        signature = signature,
        signerPublicKey = Base64Url.decode(signerPublicKey),
    )

    companion object {
        const val SIGNING_PURPOSE = "deal-signing-keyset"
        const val ENCRYPTION_PURPOSE = "deal-encryption-keyset"
    }
}
