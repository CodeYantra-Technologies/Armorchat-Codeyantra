package com.codeyantra.armorchat.deal.domain

import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.crypto.tink.KeysetHandle

/**
 * Mandatory signing path for a bound agreement.
 *
 * [SigningStage] values that are not [DealStatus] entries stay here.
 * The stored status is the existing lifecycle value those stages map onto.
 * [DealLifecycle] is unchanged. Finality is [DealFinality.assess] of the
 * verified SIGN signatures on this progress, never the stored status alone.
 *
 * Acknowledgment, delivery, and name confirmation do not create SIGN signatures.
 * A name confirmation is an `ArmorDeal/name-on-version/v1` statement over the
 * current document hash. It is not stored as a deal signature.
 */
enum class SigningStage(val code: Int) {
    LOCAL_DRAFT(0),
    PROPOSED(1),
    AWAITING_PARTY_B_REVIEW(2),
    AWAITING_NAME_CONFIRMATIONS(3),
    READY_TO_SIGN(4),
    PARTIALLY_SIGNED(5),
    FULLY_SIGNED(6),
    REJECTED(7),
    CANCELLED(8),
    EXPIRED(9),
    ;

    companion object {
        fun fromCode(code: Int): SigningStage =
            entries.firstOrNull { it.code == code } ?: LOCAL_DRAFT
    }
}

enum class SigningEvent {
    SEND_PROPOSAL,
    PARTY_B_VALIDATED,
    CONFIRM_OWN_NAME,
    SIGN,
    REJECT,
    CANCEL,
    EXPIRE,
    REVISE,
    ACKNOWLEDGE,
    RECORD_DELIVERY,
}

/** One party's signature over [DealIdentity.NAME_ON_VERSION_DOMAIN]. Not a SIGN row. */
class NameOnVersion(
    val partyId: String,
    signature: ByteArray,
) {
    val signature: ByteArray = signature.copyOf()

    init {
        DealIds.require(partyId)
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
    }

    override fun toString(): String = "NameOnVersion(partyId=$partyId)"
}

/**
 * Facts for one canonical version. [stage] and [storedStatus] are derived.
 * The object does not expose a way to drop a signature.
 */
class SigningProgress internal constructor(
    val deal: CanonicalDeal,
    internal val proposalSent: Boolean,
    internal val partyBValidated: Boolean,
    val nameConfirmations: List<NameOnVersion>,
    val signatures: List<DealSignature>,
    internal val terminal: SigningTerminal?,
) {
    val stage: SigningStage
        get() = SigningWorkflow.derive(this)

    val storedStatus: DealStatus
        get() = SigningWorkflow.storedStatus(stage, deal)

    val signatureCount: Int
        get() = signatures.size

    fun verdict(): FinalityVerdict = SigningWorkflow.verdict(this)

    internal fun copy(
        deal: CanonicalDeal = this.deal,
        proposalSent: Boolean = this.proposalSent,
        partyBValidated: Boolean = this.partyBValidated,
        nameConfirmations: List<NameOnVersion> = this.nameConfirmations,
        signatures: List<DealSignature> = this.signatures,
        terminal: SigningTerminal? = this.terminal,
    ): SigningProgress = SigningProgress(
        deal,
        proposalSent,
        partyBValidated,
        nameConfirmations,
        signatures,
        terminal,
    )

    override fun toString(): String =
        "SigningProgress(stage=$stage, dealId=${deal.dealId}, versionId=${deal.versionId}, " +
            "names=${nameConfirmations.size}, signatures=${signatures.size})"
}

internal enum class SigningTerminal {
    REJECTED,
    CANCELLED,
    EXPIRED,
    ;

    val stage: SigningStage
        get() = when (this) {
            REJECTED -> SigningStage.REJECTED
            CANCELLED -> SigningStage.CANCELLED
            EXPIRED -> SigningStage.EXPIRED
        }

    val event: DealEvent
        get() = when (this) {
            REJECTED -> DealEvent.REJECT
            CANCELLED -> DealEvent.CANCEL
            EXPIRED -> DealEvent.EXPIRE
        }

    companion object {
        fun fromCode(code: Int): SigningTerminal? = when (code) {
            0 -> null
            1 -> REJECTED
            2 -> CANCELLED
            3 -> EXPIRED
            else -> throw DealFormatException("Signing journal terminal is not canonical.")
        }
    }
}

object SigningWorkflow {
    const val JOURNAL_ACTION = "signing_progress"
    private val magic = byteArrayOf(0x41, 0x44, 0x53, 0x57) // ADSW
    private const val JOURNAL_VERSION = 1
    private const val MAX_ENTRIES = 16

    fun start(deal: CanonicalDeal): SigningProgress = SigningProgress(
        deal = deal,
        proposalSent = false,
        partyBValidated = false,
        nameConfirmations = emptyList(),
        signatures = emptyList(),
        terminal = null,
    )

    fun allows(stage: SigningStage, event: SigningEvent): Boolean = when (stage) {
        SigningStage.LOCAL_DRAFT -> event == SigningEvent.SEND_PROPOSAL ||
            event == SigningEvent.CANCEL ||
            event == SigningEvent.EXPIRE
        SigningStage.PROPOSED -> event == SigningEvent.PARTY_B_VALIDATED ||
            event == SigningEvent.REJECT ||
            event == SigningEvent.CANCEL ||
            event == SigningEvent.EXPIRE ||
            event == SigningEvent.REVISE ||
            event == SigningEvent.ACKNOWLEDGE ||
            event == SigningEvent.RECORD_DELIVERY
        SigningStage.AWAITING_PARTY_B_REVIEW -> event == SigningEvent.CONFIRM_OWN_NAME ||
            event == SigningEvent.PARTY_B_VALIDATED ||
            event == SigningEvent.REJECT ||
            event == SigningEvent.CANCEL ||
            event == SigningEvent.EXPIRE ||
            event == SigningEvent.REVISE ||
            event == SigningEvent.ACKNOWLEDGE ||
            event == SigningEvent.RECORD_DELIVERY
        SigningStage.AWAITING_NAME_CONFIRMATIONS -> event == SigningEvent.CONFIRM_OWN_NAME ||
            event == SigningEvent.REJECT ||
            event == SigningEvent.CANCEL ||
            event == SigningEvent.EXPIRE ||
            event == SigningEvent.REVISE ||
            event == SigningEvent.ACKNOWLEDGE ||
            event == SigningEvent.RECORD_DELIVERY
        SigningStage.READY_TO_SIGN -> event == SigningEvent.SIGN ||
            event == SigningEvent.CONFIRM_OWN_NAME ||
            event == SigningEvent.REJECT ||
            event == SigningEvent.CANCEL ||
            event == SigningEvent.EXPIRE ||
            event == SigningEvent.REVISE ||
            event == SigningEvent.ACKNOWLEDGE ||
            event == SigningEvent.RECORD_DELIVERY
        SigningStage.PARTIALLY_SIGNED -> event == SigningEvent.SIGN ||
            event == SigningEvent.REJECT ||
            event == SigningEvent.CANCEL ||
            event == SigningEvent.EXPIRE ||
            event == SigningEvent.REVISE ||
            event == SigningEvent.ACKNOWLEDGE ||
            event == SigningEvent.RECORD_DELIVERY
        SigningStage.FULLY_SIGNED -> event == SigningEvent.ACKNOWLEDGE ||
            event == SigningEvent.RECORD_DELIVERY ||
            event == SigningEvent.CONFIRM_OWN_NAME ||
            event == SigningEvent.SIGN
        SigningStage.REJECTED,
        SigningStage.CANCELLED,
        SigningStage.EXPIRED,
        -> false
    }

    fun storedStatus(stage: SigningStage, deal: CanonicalDeal): DealStatus = when (stage) {
        SigningStage.LOCAL_DRAFT -> DealStatus.DRAFT
        SigningStage.PROPOSED,
        SigningStage.AWAITING_PARTY_B_REVIEW,
        SigningStage.AWAITING_NAME_CONFIRMATIONS,
        SigningStage.READY_TO_SIGN,
        -> if (deal.parentVersionId != null) DealStatus.NEGOTIATING else DealStatus.PROPOSED
        SigningStage.PARTIALLY_SIGNED -> DealStatus.PARTIALLY_SIGNED
        SigningStage.FULLY_SIGNED -> DealStatus.FULLY_SIGNED
        SigningStage.REJECTED -> DealStatus.REJECTED
        SigningStage.CANCELLED -> DealStatus.CANCELLED
        SigningStage.EXPIRED -> DealStatus.EXPIRED
    }

    fun verdict(progress: SigningProgress): FinalityVerdict = DealFinality.assess(
        deal = progress.deal,
        signatures = verifiedSignatures(progress.deal, progress.signatures),
        pinnedSigningKeys = pinnedKeys(progress.deal),
    )

    fun sendProposal(progress: SigningProgress): SigningProgress {
        if (!allows(progress.stage, SigningEvent.SEND_PROPOSAL)) refused(progress.stage, SigningEvent.SEND_PROPOSAL)
        return progress.copy(proposalSent = true)
    }

    /** Party B attests that this device validated the proposal. Party A cannot do it. */
    fun recordPartyBReview(progress: SigningProgress, counterpartySigner: DealMessagingSigner): SigningProgress {
        authorizeCounterparty(progress.deal, counterpartySigner)
        if (!allows(progress.stage, SigningEvent.PARTY_B_VALIDATED)) {
            refused(progress.stage, SigningEvent.PARTY_B_VALIDATED)
        }
        if (progress.partyBValidated) return progress
        return progress.copy(partyBValidated = true)
    }

    /**
     * Party B signs the review statement for this version hash.
     * Party A's key is refused before a signature is produced.
     */
    fun signPartyBReview(progress: SigningProgress, counterpartySigner: DealMessagingSigner): ByteArray {
        authorizeCounterparty(progress.deal, counterpartySigner)
        if (!progress.partyBValidated && !allows(progress.stage, SigningEvent.PARTY_B_VALIDATED)) {
            refused(progress.stage, SigningEvent.PARTY_B_VALIDATED)
        }
        val deal = progress.deal
        return counterpartySigner.sign(
            DealIdentity.partyBReviewPayload(deal.dealId, deal.versionId, deal.documentHash()),
        )
    }

    /**
     * Accepts Party B's review signature. The local device does not need
     * Party B's messaging private key. An invalid signature is ignored by the
     * caller; this method rejects it.
     */
    fun adoptPartyBReview(progress: SigningProgress, signature: ByteArray): SigningProgress {
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        val counterparty = progress.deal.parties.singleOrNull { it.role == PartyRole.COUNTERPARTY }
            ?: throw DealFormatException("Phase 1 agreements have two parties.")
        val identity = progress.deal.identity ?: throw DealFormatException("This document has no identity binding.")
        val pin = identity.pin(counterparty.partyId)
        val payload = DealIdentity.partyBReviewPayload(
            progress.deal.dealId,
            progress.deal.versionId,
            progress.deal.documentHash(),
        )
        DealIdentityCrypto.verify(pin.participant.messagingPublicKey, signature, payload)
        if (progress.partyBValidated) return progress
        if (!allows(progress.stage, SigningEvent.PARTY_B_VALIDATED)) {
            refused(progress.stage, SigningEvent.PARTY_B_VALIDATED)
        }
        return progress.copy(partyBValidated = true)
    }

    /**
     * Confirms [signer]'s own legal name for this version hash.
     * The other party's name is refused before a signature is produced.
     */
    fun confirmOwnName(
        progress: SigningProgress,
        partyId: String,
        signer: DealMessagingSigner,
        legalName: String,
    ): SigningProgress {
        val pin = ownPin(progress.deal, partyId, signer)
        if (!allows(progress.stage, SigningEvent.CONFIRM_OWN_NAME)) {
            refused(progress.stage, SigningEvent.CONFIRM_OWN_NAME)
        }
        requireLegalName(legalName)
        var deal = progress.deal
        var names = progress.nameConfirmations
        if (pin.legalName != legalName) {
            if (pin.nameStatus != LegalNameStatus.UNSET || pin.legalName.isNotEmpty()) {
                throw DealConflictException()
            }
            deal = DealIdentity.confirmOwnLegalName(
                deal = deal,
                targetPartyId = partyId,
                signer = signer,
                legalName = legalName,
                signatureCount = progress.signatures.size,
                dealStatus = progress.storedStatus,
            )
            names = names.filter { verifiesName(deal, it) }
        }
        val base = if (deal === progress.deal && names === progress.nameConfirmations) {
            progress
        } else {
            progress.copy(deal = deal, nameConfirmations = names)
        }
        val payload = DealIdentity.nameOnVersionPayload(
            dealId = deal.dealId,
            versionId = deal.versionId,
            role = deal.identity!!.pin(partyId).role,
            participant = deal.identity!!.pin(partyId).participant,
            legalName = legalName,
            documentHash = deal.documentHash(),
        )
        val signature = signer.sign(payload)
        return submitNameConfirmation(base, partyId, signature)
    }

    fun submitNameConfirmation(
        progress: SigningProgress,
        partyId: String,
        signature: ByteArray,
    ): SigningProgress {
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        val existing = progress.nameConfirmations.firstOrNull { it.partyId == partyId }
        val identical = existing != null && existing.signature.contentEquals(signature)
        if (!identical && !allows(progress.stage, SigningEvent.CONFIRM_OWN_NAME)) {
            refused(progress.stage, SigningEvent.CONFIRM_OWN_NAME)
        }
        DealIdentity.verifyNameOnVersion(progress.deal, partyId, signature)
        if (existing != null) {
            if (existing.signature.contentEquals(signature)) return progress
            throw DealConflictException()
        }
        if (progress.stage == SigningStage.FULLY_SIGNED) throw ImmutableVersionException()
        return progress.copy(nameConfirmations = progress.nameConfirmations + NameOnVersion(partyId, signature))
    }

    /**
     * Fills an unset legal name by creating a new version. The stored proposal
     * version keeps its hash. Only this party's name slot and the version
     * metadata change. Terms, money, details, annex, parties, and bindings stay.
     * A name that is already on this version is confirmed in place.
     */
    fun confirmOwnNameOnSuccessor(
        progress: SigningProgress,
        partyId: String,
        signer: DealMessagingSigner,
        legalName: String,
        newVersionId: String,
        revisedAtEpochMs: Long,
    ): SigningProgress {
        val pin = ownPin(progress.deal, partyId, signer)
        if (pin.legalName == legalName && pin.nameStatus == LegalNameStatus.SELF_DECLARED_UNVERIFIED) {
            return confirmOwnName(progress, partyId, signer, legalName)
        }
        if (progress.signatures.isNotEmpty() || verdict(progress).proven) throw ImmutableVersionException()
        if (!allows(progress.stage, SigningEvent.CONFIRM_OWN_NAME)) {
            refused(progress.stage, SigningEvent.CONFIRM_OWN_NAME)
        }
        DealIds.require(newVersionId)
        if (newVersionId == progress.deal.versionId) {
            throw DealFormatException("A revision needs a new version id.")
        }
        val named = DealIdentity.confirmOwnLegalName(
            deal = progress.deal,
            targetPartyId = partyId,
            signer = signer,
            legalName = legalName,
            signatureCount = 0,
            dealStatus = progress.storedStatus,
        )
        val successor = CanonicalDeal.create(
            dealId = named.dealId,
            versionId = newVersionId,
            parentVersionId = progress.deal.versionId,
            createdAtEpochMs = revisedAtEpochMs,
            currency = named.currency,
            amountMinor = named.amountMinor,
            parties = named.parties,
            ancestry = progress.deal.ancestry + progress.deal.versionId,
            terms = named.terms,
            details = named.details,
            identity = named.identity,
            annex = named.annex,
        )
        requireNameOnlySuccessor(progress.deal, successor, partyId)
        val carried = progress.copy(
            deal = successor,
            nameConfirmations = emptyList(),
            signatures = emptyList(),
        )
        return confirmOwnName(carried, partyId, signer, legalName)
    }

    /** Accepts the other device's name-only successor after the diff check. */
    fun adoptNameSuccessor(
        progress: SigningProgress,
        successor: CanonicalDeal,
        partyId: String,
        nameSignature: ByteArray,
    ): SigningProgress {
        if (progress.signatures.isNotEmpty() || verdict(progress).proven) throw ImmutableVersionException()
        requireNameOnlySuccessor(progress.deal, successor, partyId)
        val carried = progress.copy(
            deal = successor,
            nameConfirmations = emptyList(),
            signatures = emptyList(),
        )
        return submitNameConfirmation(carried, partyId, nameSignature)
    }

    /**
     * The successor may change only [partyId]'s unset legal name and the
     * version id, parent, ancestry, and timestamp. Terms stay byte-for-byte.
     */
    fun requireNameOnlySuccessor(previous: CanonicalDeal, successor: CanonicalDeal, partyId: String) {
        if (successor.dealId != previous.dealId ||
            successor.versionId == previous.versionId ||
            successor.parentVersionId != previous.versionId ||
            successor.ancestry != previous.ancestry + previous.versionId ||
            successor.terms != previous.terms ||
            successor.currency != previous.currency ||
            successor.amountMinor != previous.amountMinor ||
            !sameDetails(previous.details, successor.details) ||
            !sameAnnex(previous.annex, successor.annex)
        ) {
            throw DealFormatException("The name confirmation does not match this agreement.")
        }
        if (previous.parties.size != successor.parties.size) {
            throw DealFormatException("The name confirmation does not match this agreement.")
        }
        for (index in previous.parties.indices) {
            val left = previous.parties[index]
            val right = successor.parties[index]
            if (left.partyId != right.partyId ||
                left.role != right.role ||
                left.label != right.label ||
                !left.signingPublicKey.contentEquals(right.signingPublicKey) ||
                !left.encryptionPublicKey.contentEquals(right.encryptionPublicKey)
            ) {
                throw DealFormatException("The name confirmation does not match this agreement.")
            }
        }
        val before = previous.identity ?: throw DealFormatException("This document has no identity binding.")
        val after = successor.identity ?: throw DealFormatException("This document has no identity binding.")
        var changed = false
        for (index in before.pins.indices) {
            val left = before.pins[index]
            val right = after.pins[index]
            if (left.partyId != right.partyId ||
                left.role != right.role ||
                left.participant.armorChatId != right.participant.armorChatId ||
                !left.participant.messagingPublicKey.contentEquals(right.participant.messagingPublicKey) ||
                !left.dealSigningPublicKey.contentEquals(right.dealSigningPublicKey) ||
                !left.dealEncryptionPublicKey.contentEquals(right.dealEncryptionPublicKey) ||
                !sameBytes(left.bindingSignature, right.bindingSignature)
            ) {
                throw DealFormatException("The name confirmation does not match this agreement.")
            }
            if (left.partyId != partyId) {
                if (left.legalName != right.legalName ||
                    left.nameStatus != right.nameStatus ||
                    !sameBytes(left.nameConfirmation, right.nameConfirmation)
                ) {
                    throw DealFormatException("The name confirmation does not match this agreement.")
                }
            } else {
                changed = true
                if (left.nameStatus != LegalNameStatus.UNSET || left.legalName.isNotEmpty() || left.nameConfirmation != null) {
                    throw DealFormatException("The name confirmation does not match this agreement.")
                }
                if (right.nameStatus != LegalNameStatus.SELF_DECLARED_UNVERIFIED ||
                    right.legalName.isEmpty() ||
                    right.nameConfirmation == null
                ) {
                    throw DealFormatException("The name confirmation does not match this agreement.")
                }
            }
        }
        if (!changed) throw DealFormatException("The name confirmation does not match this agreement.")
    }

    /** A term revision keeps every identity field and changes the terms. */
    fun requireTermRevision(previous: CanonicalDeal, successor: CanonicalDeal) {
        if (successor.dealId != previous.dealId ||
            successor.versionId == previous.versionId ||
            successor.parentVersionId != previous.versionId ||
            successor.ancestry != previous.ancestry + previous.versionId ||
            successor.currency != previous.currency ||
            successor.amountMinor != previous.amountMinor ||
            !sameDetails(previous.details, successor.details) ||
            !sameAnnex(previous.annex, successor.annex)
        ) {
            throw DealFormatException("The revision does not match this agreement.")
        }
        if (successor.terms == previous.terms) {
            throw DealFormatException("Revision did not change the canonical document.")
        }
        DealIdentity.requireSameParticipants(previous, successor)
    }

    fun sign(progress: SigningProgress, partyId: String, signingKey: KeysetHandle): SigningProgress {
        val existing = progress.signatures.firstOrNull { it.partyId == partyId && it.action == SignAction.SIGN }
        if (existing == null && !allows(progress.stage, SigningEvent.SIGN)) {
            refused(progress.stage, SigningEvent.SIGN)
        }
        val party = progress.deal.party(partyId)
        val presented = DealIdentityCrypto.publicKey(signingKey)
        if (!presented.contentEquals(party.signingPublicKey)) throw DealCryptoException.WrongSigner()
        val produced = DealSignatures.sign(signingKey, progress.deal, partyId, SignAction.SIGN)
        return submitSignature(progress, produced)
    }

    fun submitSignature(progress: SigningProgress, claimed: DealSignature): SigningProgress {
        if (claimed.action != SignAction.SIGN) throw DealCryptoException.WrongAction()
        val existing = progress.signatures.firstOrNull { it.partyId == claimed.partyId && it.action == SignAction.SIGN }
        val identical = existing != null &&
            existing.dealId == claimed.dealId &&
            existing.versionId == claimed.versionId &&
            existing.signature.contentEquals(claimed.signature) &&
            existing.signerPublicKey.contentEquals(claimed.signerPublicKey)
        if (identical) {
            if (!allows(progress.stage, SigningEvent.SIGN)) refused(progress.stage, SigningEvent.SIGN)
            return progress
        }
        val pinned = try {
            progress.deal.party(claimed.partyId).signingPublicKey
        } catch (e: DealCryptoException) {
            throw DealCryptoException.WrongSigner()
        }
        DealSignatures.verify(
            deal = progress.deal,
            claimed = claimed,
            expectedAction = SignAction.SIGN,
            expectedDealId = progress.deal.dealId,
            expectedVersionId = progress.deal.versionId,
            pinnedPublicKey = pinned,
        )
        if (!allows(progress.stage, SigningEvent.SIGN)) refused(progress.stage, SigningEvent.SIGN)
        if (progress.stage == SigningStage.FULLY_SIGNED) throw ImmutableVersionException()
        if (existing != null) throw DealConflictException()
        val stored = DealSignature(
            dealId = claimed.dealId,
            versionId = claimed.versionId,
            partyId = claimed.partyId,
            action = SignAction.SIGN,
            signature = claimed.signature.copyOf(),
            signerPublicKey = claimed.signerPublicKey.copyOf(),
        )
        return progress.copy(signatures = progress.signatures + stored)
    }

    fun reject(progress: SigningProgress): SigningProgress = terminate(progress, SigningTerminal.REJECTED)

    fun cancel(progress: SigningProgress): SigningProgress = terminate(progress, SigningTerminal.CANCELLED)

    fun expire(progress: SigningProgress): SigningProgress = terminate(progress, SigningTerminal.EXPIRED)

    /**
     * Starts a new unsigned version. [progress] and its signatures stay as they are.
     * The successor's signing stage is [SigningStage.PROPOSED] until Party B validates it.
     */
    fun revise(
        progress: SigningProgress,
        currentBytes: ByteArray,
        newVersionId: String,
        newTerms: String,
        revisedAtEpochMs: Long,
    ): SigningProgress {
        if (!allows(progress.stage, SigningEvent.REVISE)) refused(progress.stage, SigningEvent.REVISE)
        if (progress.stage == SigningStage.FULLY_SIGNED || verdict(progress).proven) throw ImmutableVersionException()
        val revised = DealVersions.revise(
            current = progress.deal,
            currentBytes = currentBytes,
            signatureCount = progress.signatures.size,
            dealStatus = progress.storedStatus,
            newVersionId = newVersionId,
            newTerms = newTerms,
            revisedAtEpochMs = revisedAtEpochMs,
        )
        DealVersions.historicalSignatures(progress.signatures, progress.deal.versionId, revised.versionId)
        return SigningProgress(
            deal = revised,
            proposalSent = true,
            partyBValidated = false,
            nameConfirmations = emptyList(),
            signatures = emptyList(),
            terminal = null,
        )
    }

    fun acknowledge(progress: SigningProgress): SigningProgress {
        if (!allows(progress.stage, SigningEvent.ACKNOWLEDGE)) refused(progress.stage, SigningEvent.ACKNOWLEDGE)
        return progress
    }

    fun recordDelivery(progress: SigningProgress): SigningProgress {
        if (!allows(progress.stage, SigningEvent.RECORD_DELIVERY)) {
            refused(progress.stage, SigningEvent.RECORD_DELIVERY)
        }
        return progress
    }

    fun encodeJournal(progress: SigningProgress): ByteArray {
        val out = ByteArrayOutput()
        out.raw(magic)
        out.u8(JOURNAL_VERSION)
        out.u8(progress.stage.code)
        out.lp(progress.deal.versionId)
        out.raw(progress.deal.documentHash())
        val parent = progress.deal.parentVersionId
        if (parent == null) {
            out.u8(0)
        } else {
            out.u8(1)
            out.lp(parent)
        }
        val names = progress.nameConfirmations.sortedBy { it.partyId }
        out.u16(names.size)
        for (name in names) {
            out.lp(name.partyId)
            out.raw(name.signature)
        }
        val signatures = progress.signatures.sortedBy { it.partyId }
        out.u16(signatures.size)
        for (signature in signatures) {
            out.lp(signature.partyId)
            out.raw(signature.signature)
            out.raw(signature.signerPublicKey)
        }
        var flags = 0
        if (progress.proposalSent) flags = flags or FLAG_PROPOSAL
        if (progress.partyBValidated) flags = flags or FLAG_PARTY_B
        if (progress.nameConfirmations.isNotEmpty()) flags = flags or FLAG_NAMES_OPEN
        out.u8(flags)
        out.u8(
            when (progress.terminal) {
                null -> 0
                SigningTerminal.REJECTED -> 1
                SigningTerminal.CANCELLED -> 2
                SigningTerminal.EXPIRED -> 3
            },
        )
        return out.toByteArray()
    }

    fun journalOutcome(progress: SigningProgress): String = "ok:" + Base64Url.encode(encodeJournal(progress))

    /**
     * Folds journal outcomes for [deal]. The informational stage byte is ignored.
     * Invalid signatures are dropped. A later row cannot remove a verified signature,
     * and it cannot cancel a version that [DealFinality.assess] already proves.
     */
    fun recover(deal: CanonicalDeal, outcomes: List<String>): SigningProgress {
        val facts = JournalFacts()
        for (outcome in outcomes) {
            if (!outcome.startsWith(OUTCOME_PREFIX)) continue
            val record = try {
                decodeJournal(Base64Url.decode(outcome.removePrefix(OUTCOME_PREFIX)))
            } catch (e: DealException) {
                continue
            } catch (e: IllegalArgumentException) {
                continue
            }
            if (record.versionId != deal.versionId) continue
            if (!record.documentHash.contentEquals(deal.documentHash())) continue
            facts.proposalSent = facts.proposalSent || record.proposalSent
            facts.partyBValidated = facts.partyBValidated || record.partyBValidated
            for (name in record.names) {
                if (facts.names.containsKey(name.partyId)) continue
                if (!verifiesName(deal, name)) continue
                facts.names[name.partyId] = name
            }
            for (raw in record.signatures) {
                if (facts.signatures.containsKey(raw.partyId)) continue
                val signature = try {
                    DealSignature(
                        dealId = deal.dealId,
                        versionId = deal.versionId,
                        partyId = raw.partyId,
                        action = SignAction.SIGN,
                        signature = raw.signature,
                        signerPublicKey = raw.signerPublicKey,
                    )
                } catch (e: DealException) {
                    continue
                }
                if (!verifiesSignature(deal, signature)) continue
                facts.signatures[raw.partyId] = signature
            }
            val progress = facts.progress(deal)
            val evidence = evidenceStage(progress)
            if (evidence == SigningStage.FULLY_SIGNED) {
                facts.terminal = null
            } else if (record.terminal != null && legalTerminal(evidence, record.terminal, deal)) {
                facts.terminal = record.terminal
            }
        }
        return facts.progress(deal)
    }

    internal fun derive(progress: SigningProgress): SigningStage {
        val evidence = evidenceStage(progress)
        val terminal = progress.terminal ?: return evidence
        if (evidence == SigningStage.FULLY_SIGNED) return SigningStage.FULLY_SIGNED
        if (!legalTerminal(evidence, terminal, progress.deal)) return evidence
        return terminal.stage
    }

    private fun evidenceStage(progress: SigningProgress): SigningStage {
        val signatures = verifiedSignatures(progress.deal, progress.signatures)
        val verdict = DealFinality.assess(progress.deal, signatures, pinnedKeys(progress.deal))
        if (verdict.proven) return SigningStage.FULLY_SIGNED
        if (signatures.size == 1) return SigningStage.PARTIALLY_SIGNED
        val names = progress.nameConfirmations.filter { verifiesName(progress.deal, it) }
        val parties = progress.deal.parties.map { it.partyId }.toSet()
        if (names.size == 2 && names.map { it.partyId }.toSet() == parties) return SigningStage.READY_TO_SIGN
        if (names.isNotEmpty()) return SigningStage.AWAITING_NAME_CONFIRMATIONS
        if (progress.partyBValidated) return SigningStage.AWAITING_PARTY_B_REVIEW
        if (progress.proposalSent) return SigningStage.PROPOSED
        return SigningStage.LOCAL_DRAFT
    }

    private fun terminate(progress: SigningProgress, terminal: SigningTerminal): SigningProgress {
        val event = when (terminal) {
            SigningTerminal.REJECTED -> SigningEvent.REJECT
            SigningTerminal.CANCELLED -> SigningEvent.CANCEL
            SigningTerminal.EXPIRED -> SigningEvent.EXPIRE
        }
        if (!allows(progress.stage, event)) refused(progress.stage, event)
        val evidence = evidenceStage(progress)
        if (evidence == SigningStage.FULLY_SIGNED || verdict(progress).proven) throw ImmutableVersionException()
        DealLifecycle.transition(storedStatus(evidence, progress.deal), terminal.event)
        return progress.copy(terminal = terminal)
    }

    private fun legalTerminal(evidence: SigningStage, terminal: SigningTerminal, deal: CanonicalDeal): Boolean {
        if (evidence == SigningStage.FULLY_SIGNED) return false
        return try {
            DealLifecycle.transition(storedStatus(evidence, deal), terminal.event)
            true
        } catch (e: DealStateException) {
            false
        }
    }

    private fun authorizeCounterparty(deal: CanonicalDeal, signer: DealMessagingSigner) {
        val counterparty = deal.parties.singleOrNull { it.role == PartyRole.COUNTERPARTY }
            ?: throw DealFormatException("Phase 1 agreements have two parties.")
        val identity = deal.identity ?: throw DealFormatException("This document has no identity binding.")
        val pin = identity.pin(counterparty.partyId)
        if (!pin.participant.messagingPublicKey.contentEquals(signer.publicKey)) throw ForeignPartyException()
    }

    private fun ownPin(deal: CanonicalDeal, partyId: String, signer: DealMessagingSigner): DealPartyPin {
        val identity = deal.identity
            ?: throw DealFormatException("This document has no identity binding.")
        val pin = identity.pin(partyId)
        if (!pin.participant.messagingPublicKey.contentEquals(signer.publicKey)) throw ForeignPartyException()
        return pin
    }

    private fun sameDetails(left: AgreementDetails?, right: AgreementDetails?): Boolean {
        if (left == null || right == null) return left == null && right == null
        return left.title == right.title &&
            left.deadlineDate == right.deadlineDate &&
            left.paymentConditions == right.paymentConditions &&
            left.proposerRole == right.proposerRole &&
            left.counterpartyRole == right.counterpartyRole
    }

    private fun sameAnnex(left: ContractAnnex?, right: ContractAnnex?): Boolean {
        if (left == null || right == null) return left == null && right == null
        return left.purpose == right.purpose && left.effectiveDate == right.effectiveDate
    }

    private fun sameBytes(left: ByteArray?, right: ByteArray?): Boolean {
        if (left == null || right == null) return left == null && right == null
        return left.contentEquals(right)
    }

    private fun verifiesName(deal: CanonicalDeal, name: NameOnVersion): Boolean = try {
        DealIdentity.verifyNameOnVersion(deal, name.partyId, name.signature)
        true
    } catch (e: DealException) {
        false
    }

    private fun verifiesSignature(deal: CanonicalDeal, signature: DealSignature): Boolean = try {
        DealSignatures.verify(
            deal = deal,
            claimed = signature,
            expectedAction = SignAction.SIGN,
            expectedDealId = deal.dealId,
            expectedVersionId = deal.versionId,
            pinnedPublicKey = deal.party(signature.partyId).signingPublicKey,
        )
        true
    } catch (e: DealException) {
        false
    }

    private fun verifiedSignatures(deal: CanonicalDeal, signatures: List<DealSignature>): List<DealSignature> =
        signatures.filter { it.action == SignAction.SIGN && verifiesSignature(deal, it) }

    private fun pinnedKeys(deal: CanonicalDeal): Map<String, ByteArray> =
        deal.parties.associate { it.partyId to it.signingPublicKey }

    private fun refused(stage: SigningStage, event: SigningEvent): Nothing {
        throw DealStateException("Transition $stage / $event is not allowed.")
    }

    private fun decodeJournal(bytes: ByteArray): JournalRecord {
        val cursor = JournalCursor(bytes)
        val magicBytes = cursor.bytes(magic.size)
        if (!magicBytes.contentEquals(magic)) throw DealFormatException("Not an Armor Deal signing journal.")
        if (cursor.u8() != JOURNAL_VERSION) throw DealFormatException("Unsupported signing journal.")
        SigningStage.fromCode(cursor.u8())
        val versionId = cursor.lp()
        DealIds.require(versionId)
        val hash = cursor.bytes(32)
        when (cursor.u8()) {
            0 -> Unit
            1 -> DealIds.require(cursor.lp())
            else -> throw DealFormatException("Signing journal parent flag is not canonical.")
        }
        val nameCount = cursor.u16()
        if (nameCount > MAX_ENTRIES) throw DealFormatException("Signing journal is not canonical.")
        val names = ArrayList<NameOnVersion>(nameCount)
        repeat(nameCount) {
            val partyId = cursor.lp()
            val signature = cursor.bytes(64)
            if (DealIds.isValid(partyId)) names += NameOnVersion(partyId, signature)
        }
        val signCount = cursor.u16()
        if (signCount > MAX_ENTRIES) throw DealFormatException("Signing journal is not canonical.")
        val signatures = ArrayList<RawSignature>(signCount)
        repeat(signCount) {
            signatures += RawSignature(cursor.lp(), cursor.bytes(64), cursor.bytes(32))
        }
        val flags = cursor.u8()
        val terminal = SigningTerminal.fromCode(cursor.u8())
        if (cursor.remaining() != 0) throw DealFormatException("Signing journal has trailing bytes.")
        return JournalRecord(
            versionId = versionId,
            documentHash = hash,
            names = names,
            signatures = signatures,
            proposalSent = (flags and FLAG_PROPOSAL) != 0,
            partyBValidated = (flags and FLAG_PARTY_B) != 0,
            terminal = terminal,
        )
    }

    private class JournalFacts {
        var proposalSent: Boolean = false
        var partyBValidated: Boolean = false
        val names: MutableMap<String, NameOnVersion> = linkedMapOf()
        val signatures: MutableMap<String, DealSignature> = linkedMapOf()
        var terminal: SigningTerminal? = null

        fun progress(deal: CanonicalDeal): SigningProgress = SigningProgress(
            deal = deal,
            proposalSent = proposalSent,
            partyBValidated = partyBValidated,
            nameConfirmations = names.values.toList(),
            signatures = signatures.values.toList(),
            terminal = terminal,
        )
    }

    private class JournalRecord(
        val versionId: String,
        val documentHash: ByteArray,
        val names: List<NameOnVersion>,
        val signatures: List<RawSignature>,
        val proposalSent: Boolean,
        val partyBValidated: Boolean,
        val terminal: SigningTerminal?,
    )

    private class RawSignature(
        val partyId: String,
        val signature: ByteArray,
        val signerPublicKey: ByteArray,
    )

    private class JournalCursor(private val data: ByteArray) {
        private var index = 0

        fun remaining(): Int = data.size - index

        fun u8(): Int {
            if (index >= data.size) throw DealFormatException("Signing journal is truncated.")
            return data[index++].toInt() and 0xff
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun bytes(count: Int): ByteArray {
            if (count < 0 || remaining() < count) throw DealFormatException("Signing journal is truncated.")
            val slice = data.copyOfRange(index, index + count)
            index += count
            return slice
        }

        fun lp(): String {
            val length = u16()
            return utf8Strict(bytes(length))
        }
    }

    private const val FLAG_PROPOSAL = 1
    private const val FLAG_PARTY_B = 2
    private const val FLAG_NAMES_OPEN = 4
    private const val OUTCOME_PREFIX = "ok:"
}
