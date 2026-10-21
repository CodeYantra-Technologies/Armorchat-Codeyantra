package com.codeyantra.armorchat.deal.transport

import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealKeyProtector
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.ClosureKind
import com.codeyantra.armorchat.deal.domain.ClosureState
import com.codeyantra.armorchat.deal.domain.DealClosure
import com.codeyantra.armorchat.deal.domain.DealException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.SigningStage
import com.codeyantra.armorchat.deal.domain.SigningUi
import com.codeyantra.armorchat.deal.storage.DealClosureStore
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.storage.SigningProgressStore

/**
 * Reads one live agreement between the local identity and one contact.
 * Terms are available to the debug review screen. [toString] omits them.
 */
class DealSigningSnapshot(
    val dealId: String,
    val versionId: String,
    val stage: SigningStage,
    val role: PartyRole,
    val proven: Boolean,
    val ownNameConfirmed: Boolean,
    val ownSignature: Boolean,
    val terms: String,
    val ownLegalName: String,
    val canReview: Boolean,
    val canConfirmName: Boolean,
    val canSign: Boolean,
    val canRevise: Boolean,
    val canReject: Boolean,
    /** ArmorChat id pinned to the PROPOSER role (always Party A). */
    val proposerArmorChatId: String = "",
    /** ArmorChat id pinned to the COUNTERPARTY role (always Party B). */
    val counterpartyArmorChatId: String = "",
    /** Phase 5.4B: the complete document, or an explicit fail-closed result. */
    val document: DealDocumentResult = DealDocumentResult.Failed(DealDocumentResult.NOT_LOADED),
    /** Phase 5.4C: a local review consent exists for this exact version/hash and local party. */
    val ownReviewConsent: Boolean = false,
    val closureHeadline: String = "",
    val closureRolesBound: Boolean = false,
    val closureTrusted: Boolean = true,
    val buyerConfirmed: Boolean = false,
    val supplierConfirmed: Boolean = false,
    val canConfirmClosure: Boolean = false,
    val localClosureKind: ClosureKind? = null,
    val updatedAtEpochMs: Long = 0,
) {
    override fun toString(): String = "DealSigningSnapshot(stage=$stage, role=$role, proven=$proven)"
}

/** Selector text. It names the stage and a short id. It does not include terms or names. */
fun DealSigningSnapshot.agreementChoiceLabel(): String {
    val fingerprint = (document as? DealDocumentResult.Ready)?.view?.fingerprint ?: "not open"
    return "Agreement ${dealId.takeLast(6)} · $stage · $fingerprint"
}

object DealSigningLookup {
    /** Latest agreement between this phone and the contact. */
    fun find(
        database: DealDatabase,
        protector: DealKeyProtector,
        localMessagingKey: ByteArray,
        peerMessagingKey: ByteArray,
    ): DealSigningSnapshot? = list(database, protector, localMessagingKey, peerMessagingKey).firstOrNull()

    /** One agreement between this phone and the contact. Null when the id is not theirs. */
    fun findById(
        database: DealDatabase,
        protector: DealKeyProtector,
        localMessagingKey: ByteArray,
        peerMessagingKey: ByteArray,
        dealId: String,
    ): DealSigningSnapshot? =
        list(database, protector, localMessagingKey, peerMessagingKey).firstOrNull { it.dealId == dealId }

    /** Every agreement between this phone and the contact, newest first. */
    fun list(
        database: DealDatabase,
        protector: DealKeyProtector,
        localMessagingKey: ByteArray,
        peerMessagingKey: ByteArray,
    ): List<DealSigningSnapshot> {
        val dao = database.dealDao()
        val matches = ArrayList<DealSigningSnapshot>()
        for (row in dao.listDeals()) {
            val parties = dao.parties(row.dealId)
            val localParty = parties.firstOrNull { dao.findKeys(it.partyId) != null } ?: continue
            val version = dao.findVersion(row.dealId, row.currentVersionId) ?: continue
            val keys = openKeys(dao, protector, localParty.partyId) ?: continue
            val plain = try {
                DealCipher.decrypt(
                    SealedDocument.parse(version.sealedDocument),
                    localParty.partyId,
                    keys.encryptionHandle(),
                )
            } catch (e: DealException) {
                continue
            }
            val deal = try {
                CanonicalEncoding.parse(plain)
            } catch (e: DealException) {
                continue
            } finally {
                plain.fill(0)
            }
            val pins = deal.identity?.pins ?: continue
            val localPin = pins.firstOrNull {
                it.participant.messagingPublicKey.contentEquals(localMessagingKey)
            } ?: continue
            val peer = pins.any { it.participant.messagingPublicKey.contentEquals(peerMessagingKey) }
            if (!peer || localPin.partyId == pins.firstOrNull {
                    it.participant.messagingPublicKey.contentEquals(peerMessagingKey)
                }?.partyId
            ) {
                continue
            }
            val progress = SigningProgressStore(dao).recover(deal)
            val ownName = progress.nameConfirmations.any { it.partyId == localPin.partyId }
            val ownSignature = progress.signatures.any {
                it.partyId == localPin.partyId && it.action == SignAction.SIGN
            }
            val closureStore = DealClosureStore(database)
            val projection = closureStore.project(deal)
            val proven = progress.verdict().proven || closureStore.finalityProven(deal)
            val integrityFailure = row.status == DealStatus.CLOSED.name && projection.state != ClosureState.CLOSED
            val localKind = if (projection.rolesBound) DealClosure.kindFor(deal, localPin.partyId) else null
            val localConfirmed = when (localKind) {
                ClosureKind.GOODS_RECEIVED -> projection.buyerConfirmed
                ClosureKind.PAYMENT_RECEIVED -> projection.supplierConfirmed
                null -> false
            }
            matches += DealSigningSnapshot(
                dealId = deal.dealId,
                versionId = deal.versionId,
                stage = progress.stage,
                role = localPin.role,
                proven = proven,
                ownNameConfirmed = ownName,
                ownSignature = ownSignature,
                terms = deal.terms,
                ownLegalName = localPin.legalName,
                canReview = SigningUi.canReview(progress.stage, localPin.role),
                canConfirmName = SigningUi.canConfirmName(progress.stage, ownName),
                canSign = SigningUi.canSign(progress.stage, ownSignature, proven),
                canRevise = SigningUi.canRevise(progress.stage, localPin.role, proven),
                canReject = SigningUi.canReject(progress.stage, proven),
                proposerArmorChatId = pins.firstOrNull { it.role == PartyRole.PROPOSER }
                    ?.participant?.armorChatId.orEmpty(),
                counterpartyArmorChatId = pins.firstOrNull { it.role == PartyRole.COUNTERPARTY }
                    ?.participant?.armorChatId.orEmpty(),
                document = DealDocumentView.from(deal, version.documentSha256),
                ownReviewConsent = dao.audits(deal.dealId).any {
                    it.action == DealReviewConsent.ACTION &&
                        it.outcome == DealReviewConsent.outcome(deal.versionId, version.documentSha256, localPin.partyId)
                },
                closureHeadline = DealClosure.headline(projection, proven, integrityFailure),
                closureRolesBound = projection.rolesBound,
                closureTrusted = !integrityFailure,
                buyerConfirmed = projection.buyerConfirmed,
                supplierConfirmed = projection.supplierConfirmed,
                canConfirmClosure = closureStore.finalityProven(deal) && !integrityFailure && projection.rolesBound &&
                    localKind != null && !localConfirmed && projection.state != ClosureState.CLOSED,
                localClosureKind = localKind,
                updatedAtEpochMs = row.updatedAtEpochMs,
            )
        }
        matches.sortWith(
            compareByDescending<DealSigningSnapshot> { it.updatedAtEpochMs }.thenByDescending { it.dealId },
        )
        return matches
    }

    private fun openKeys(
        dao: com.codeyantra.armorchat.deal.storage.DealDao,
        protector: DealKeyProtector,
        partyId: String,
    ): DealKeyMaterial? {
        val row = dao.findKeys(partyId) ?: return null
        val signing = protector.open(row.sealedSigningKeyset, DealRepository.SIGNING_PURPOSE)
        val encryption = protector.open(row.sealedEncryptionKeyset, DealRepository.ENCRYPTION_PURPOSE)
        return try {
            DealKeyMaterial.fromSerialized(signing, encryption)
        } finally {
            signing.fill(0)
            encryption.fill(0)
        }
    }
}
