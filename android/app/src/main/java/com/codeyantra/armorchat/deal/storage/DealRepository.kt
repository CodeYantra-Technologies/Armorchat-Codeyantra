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
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealKeyException
import com.codeyantra.armorchat.deal.domain.DealRecoveryException
import com.codeyantra.armorchat.deal.domain.DealSignature
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
        val signingBytes = localKeys.serializedSigning()
        val encryptionBytes = localKeys.serializedEncryption()
        val sealedSigning = protector.seal(signingBytes, SIGNING_PURPOSE)
        val sealedEncryption = protector.seal(encryptionBytes, ENCRYPTION_PURPOSE)
        signingBytes.fill(0)
        encryptionBytes.fill(0)
        val now = clock()
        val hash = Base64Url.encode(deal.documentHash())
        database.runInTransaction {
            if (dao.findDeal(deal.dealId) == null) {
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

    fun loadTerms(dealId: String, partyId: String, protector: DealKeyProtector): String {
        val keys = dao.findKeys(partyId) ?: throw DealRecoveryException.MissingObject()
        val version = dao.findDeal(dealId)?.let { dao.findVersion(dealId, it.currentVersionId) }
            ?: throw DealRecoveryException.MissingObject()
        val signing = protector.open(keys.sealedSigningKeyset, SIGNING_PURPOSE)
        val encryption = protector.open(keys.sealedEncryptionKeyset, ENCRYPTION_PURPOSE)
        try {
            val material = DealKeyMaterial.fromSerialized(signing, encryption)
            val sealed = SealedDocument.parse(version.sealedDocument)
            val plain = DealCipher.decrypt(sealed, partyId, material.encryptionHandle())
            val deal = CanonicalEncoding.parse(plain)
            if (Base64Url.encode(deal.documentHash()) != version.documentSha256) {
                throw DealRecoveryException.Rejected()
            }
            val signatures = dao.signatures(dealId, version.versionId).map { row ->
                DealSignature(
                    dealId = row.dealId,
                    versionId = row.versionId,
                    partyId = row.partyId,
                    action = SignAction.valueOf(row.action),
                    signature = row.signature,
                    signerPublicKey = Base64Url.decode(row.signerPublicKey),
                )
            }
            if (signatures.isEmpty()) throw DealRecoveryException.Rejected()
            signatures.forEach { signature ->
                DealSignatures.verify(
                    deal = deal,
                    claimed = signature,
                    expectedAction = signature.action,
                    expectedDealId = deal.dealId,
                    expectedVersionId = deal.versionId,
                    pinnedPublicKey = Base64Url.decode(
                        dao.findParty(dealId, signature.partyId)?.signingPublicKey
                            ?: throw DealRecoveryException.Rejected(),
                    ),
                )
            }
            plain.fill(0)
            return deal.terms
        } catch (e: DealKeyException) {
            throw e
        } finally {
            signing.fill(0)
            encryption.fill(0)
        }
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

    companion object {
        const val SIGNING_PURPOSE = "deal-signing-keyset"
        const val ENCRYPTION_PURPOSE = "deal-encryption-keyset"
    }
}
