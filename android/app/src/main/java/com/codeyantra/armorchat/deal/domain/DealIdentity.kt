package com.codeyantra.armorchat.deal.domain

import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.UserIdentity
import java.nio.charset.StandardCharsets

/**
 * Self-declared legal name. Version 1 of Armor Deal does not verify a name
 * against a government id, phone number, or address. Those are not collected.
 */
enum class LegalNameStatus(val code: Int) {
    UNSET(0),
    SELF_DECLARED_UNVERIFIED(1),
    ;

    companion object {
        fun fromCode(code: Int): LegalNameStatus =
            entries.firstOrNull { it.code == code }
                ?: throw DealFormatException("Legal name status is not canonical.")
    }
}

/**
 * An ArmorChat ID that has been checked against its messaging public key.
 * The deal signing key is a different key and is not stored here.
 */
class VerifiedDealParticipant private constructor(
    val armorChatId: String,
    val messagingPublicKey: ByteArray,
) {
    override fun toString(): String = "VerifiedDealParticipant"

    companion object {
        fun verify(armorChatId: String, messagingPublicKey: ByteArray): VerifiedDealParticipant {
            DealIdentityCrypto.requireEd25519Point(messagingPublicKey)
            val normalized = try {
                ArmorChatId.normalize(armorChatId)
            } catch (e: IllegalArgumentException) {
                throw DealFormatException("ArmorChat ID is not valid.")
            }
            if (ArmorChatId.derive(messagingPublicKey) != normalized) {
                throw DealFormatException("ArmorChat ID does not match the messaging public key.")
            }
            return VerifiedDealParticipant(normalized, messagingPublicKey.copyOf())
        }

        fun fromRecord(armorChatId: String, encodedPublicKey: String): VerifiedDealParticipant {
            val raw = try {
                Base64Url.decode(encodedPublicKey)
            } catch (e: IllegalArgumentException) {
                throw DealFormatException("Messaging public key is not valid.")
            }
            return verify(armorChatId, raw)
        }

        fun fromUser(identity: UserIdentity): VerifiedDealParticipant =
            fromRecord(identity.armorChatId, identity.publicIdentityKey)

        fun fromContact(contact: Contact): VerifiedDealParticipant =
            fromRecord(contact.armorChatId, contact.publicIdentityKey)
    }
}

/** One party's pinned ArmorChat identity inside a canonical version 3 document. */
class DealPartyPin(
    val partyId: String,
    val role: PartyRole,
    val participant: VerifiedDealParticipant,
    val dealSigningPublicKey: ByteArray,
    val dealEncryptionPublicKey: ByteArray,
    val legalName: String,
    val nameStatus: LegalNameStatus,
    bindingSignature: ByteArray?,
    nameConfirmation: ByteArray?,
) {
    val bindingSignature: ByteArray? = bindingSignature?.copyOf()
    val nameConfirmation: ByteArray? = nameConfirmation?.copyOf()

    init {
        DealIds.require(partyId)
        requireRawKey(dealSigningPublicKey, "signing")
        requireRawKey(dealEncryptionPublicKey, "encryption")
        if (dealSigningPublicKey.contentEquals(dealEncryptionPublicKey)) {
            throw DealFormatException("Signing and encryption keys must be distinct.")
        }
        if (participant.messagingPublicKey.contentEquals(dealSigningPublicKey) ||
            participant.messagingPublicKey.contentEquals(dealEncryptionPublicKey)
        ) {
            throw DealFormatException("Deal keys must stay separate from the messaging identity key.")
        }
        when (nameStatus) {
            LegalNameStatus.UNSET -> {
                if (legalName.isNotEmpty() || nameConfirmation != null) {
                    throw DealFormatException("An empty legal name cannot be confirmed.")
                }
            }
            LegalNameStatus.SELF_DECLARED_UNVERIFIED -> {
                requireLegalName(legalName)
                if (nameConfirmation == null) {
                    throw DealFormatException("A legal name needs its owner's confirmation.")
                }
            }
        }
        if (bindingSignature != null && bindingSignature.size != 64) {
            throw DealFormatException("Ed25519 signature must be 64 bytes.")
        }
        if (nameConfirmation != null && nameConfirmation.size != 64) {
            throw DealFormatException("Ed25519 signature must be 64 bytes.")
        }
    }

    fun withBinding(signature: ByteArray): DealPartyPin = copy(bindingSignature = signature)

    fun withLegalName(name: String, confirmation: ByteArray): DealPartyPin = copy(
        legalName = name,
        nameStatus = LegalNameStatus.SELF_DECLARED_UNVERIFIED,
        nameConfirmation = confirmation,
    )

    private fun copy(
        bindingSignature: ByteArray? = this.bindingSignature,
        legalName: String = this.legalName,
        nameStatus: LegalNameStatus = this.nameStatus,
        nameConfirmation: ByteArray? = this.nameConfirmation,
    ): DealPartyPin = DealPartyPin(
        partyId = partyId,
        role = role,
        participant = participant,
        dealSigningPublicKey = dealSigningPublicKey,
        dealEncryptionPublicKey = dealEncryptionPublicKey,
        legalName = legalName,
        nameStatus = nameStatus,
        bindingSignature = bindingSignature,
        nameConfirmation = nameConfirmation,
    )

    override fun toString(): String = "DealPartyPin(partyId=$partyId, role=$role, nameStatus=$nameStatus)"
}

/** Identity appendix for canonical version 3. Absent on version 1 and version 2. */
class DealIdentityBlock(
    val pins: List<DealPartyPin>,
) {
    init {
        if (pins.size != 2) throw DealFormatException("Phase 1 agreements have two parties.")
        if (pins.map { it.partyId }.distinct().size != 2) {
            throw DealFormatException("Party identifiers must be unique.")
        }
        if (pins.map { it.participant.armorChatId }.distinct().size != 2) {
            throw DealFormatException("A participant cannot deal with themself.")
        }
        if (pins[0].participant.messagingPublicKey.contentEquals(pins[1].participant.messagingPublicKey)) {
            throw DealFormatException("A participant cannot deal with themself.")
        }
    }

    fun pin(partyId: String): DealPartyPin =
        pins.firstOrNull { it.partyId == partyId } ?: throw DealCryptoException.UnknownParty()

    fun alignedTo(parties: List<DealParty>): DealIdentityBlock {
        if (pins.size != parties.size) throw DealFormatException("Identity binding does not match the parties.")
        val aligned = parties.map { party ->
            pins.singleOrNull { it.partyId == party.partyId }
                ?: throw DealFormatException("Identity binding does not match the parties.")
        }
        return DealIdentityBlock(aligned)
    }

    fun replace(partyId: String, pin: DealPartyPin): DealIdentityBlock {
        if (pins.none { it.partyId == partyId }) throw DealCryptoException.UnknownParty()
        return DealIdentityBlock(pins.map { if (it.partyId == partyId) pin else it })
    }
}

/**
 * Inputs for one party on a new version 3 draft. A missing binding is allowed
 * while the draft is unsigned. It is not a signature by the other party.
 */
class DealPartyDraft(
    val partyId: String,
    val role: PartyRole,
    val label: String,
    val participant: VerifiedDealParticipant,
    val signingPublicKey: ByteArray,
    val encryptionPublicKey: ByteArray,
    val bindingSignature: ByteArray? = null,
    val legalName: String = "",
    val nameStatus: LegalNameStatus = LegalNameStatus.UNSET,
    val nameConfirmation: ByteArray? = null,
)

/**
 * Domain-separated statements that bind a deal signing key, and a legal name,
 * to an ArmorChat messaging identity. The messaging key authorizes only its
 * own statement.
 */
object DealIdentity {
    const val BINDING_DOMAIN = "ArmorDeal/identity-binding/v1"
    const val LEGAL_NAME_DOMAIN = "ArmorDeal/legal-name/v1"
    const val NAME_ON_VERSION_DOMAIN = "ArmorDeal/name-on-version/v1"
    const val PARTY_B_REVIEW_DOMAIN = "ArmorDeal/party-b-review/v1"
    const val TERM_REVISION_DOMAIN = "ArmorDeal/term-revision/v1"

    /**
     * The only statement domains the ArmorChat messaging identity key may sign
     * for Armor Deal. Each is matched as exact domain bytes followed by NUL.
     * TERM_REVISION_DOMAIN and the final SIGN domain are signed by the
     * per-deal signing key, never by the messaging identity, so they are
     * deliberately absent.
     */
    val MESSAGING_SIGNER_DOMAINS: List<String> = listOf(
        BINDING_DOMAIN,
        LEGAL_NAME_DOMAIN,
        NAME_ON_VERSION_DOMAIN,
        PARTY_B_REVIEW_DOMAIN,
    )
    const val LEGAL_NAME_ASSURANCE = "self-declared-unverified"
    const val MAX_LEGAL_NAME_BYTES = 200
    const val SECTION_VERSION = 1

    fun bindingPayload(
        dealId: String,
        role: PartyRole,
        participant: VerifiedDealParticipant,
        dealSigningPublicKey: ByteArray,
        dealEncryptionPublicKey: ByteArray,
    ): ByteArray {
        DealIds.require(dealId)
        requireRawKey(dealSigningPublicKey, "signing")
        requireRawKey(dealEncryptionPublicKey, "encryption")
        val out = ByteArrayOutput()
        out.raw(BINDING_DOMAIN.toByteArray(StandardCharsets.UTF_8))
        out.u8(0)
        out.u16(DealFeature.PROTOCOL_VERSION)
        out.lp(dealId)
        out.u8(role.code)
        out.lp(participant.armorChatId)
        writeKey(out, participant.messagingPublicKey)
        writeKey(out, dealSigningPublicKey)
        writeKey(out, dealEncryptionPublicKey)
        return out.toByteArray()
    }

    fun legalNamePayload(
        dealId: String,
        role: PartyRole,
        participant: VerifiedDealParticipant,
        legalName: String,
    ): ByteArray {
        DealIds.require(dealId)
        requireLegalName(legalName)
        val out = ByteArrayOutput()
        out.raw(LEGAL_NAME_DOMAIN.toByteArray(StandardCharsets.UTF_8))
        out.u8(0)
        out.u16(DealFeature.PROTOCOL_VERSION)
        out.lp(dealId)
        out.u8(role.code)
        out.lp(participant.armorChatId)
        writeKey(out, participant.messagingPublicKey)
        out.lp(legalName)
        out.lp(LEGAL_NAME_ASSURANCE)
        return out.toByteArray()
    }

    /**
     * Binds a party's own legal name to one immutable version hash.
     * This statement is not a [SignAction.SIGN] signature and is not part of
     * the canonical document.
     */
    fun nameOnVersionPayload(
        dealId: String,
        versionId: String,
        role: PartyRole,
        participant: VerifiedDealParticipant,
        legalName: String,
        documentHash: ByteArray,
    ): ByteArray {
        DealIds.require(dealId)
        DealIds.require(versionId)
        requireLegalName(legalName)
        if (documentHash.size != 32) throw DealFormatException("Document hash is the wrong length.")
        val out = ByteArrayOutput()
        out.raw(NAME_ON_VERSION_DOMAIN.toByteArray(StandardCharsets.UTF_8))
        out.u8(0)
        out.u16(DealFeature.PROTOCOL_VERSION)
        out.lp(dealId)
        out.lp(versionId)
        out.u8(role.code)
        out.lp(participant.armorChatId)
        writeKey(out, participant.messagingPublicKey)
        out.lp(legalName)
        out.u16(documentHash.size)
        out.raw(documentHash)
        return out.toByteArray()
    }

    /**
     * Party B's review of one proposal hash. This is not a [SignAction.SIGN]
     * signature and it is not part of the canonical document.
     */
    fun partyBReviewPayload(dealId: String, versionId: String, documentHash: ByteArray): ByteArray {
        DealIds.require(dealId)
        DealIds.require(versionId)
        if (documentHash.size != 32) throw DealFormatException("Document hash is the wrong length.")
        val out = ByteArrayOutput()
        out.raw(PARTY_B_REVIEW_DOMAIN.toByteArray(StandardCharsets.UTF_8))
        out.u8(0)
        out.u16(DealFeature.PROTOCOL_VERSION)
        out.lp(dealId)
        out.lp(versionId)
        out.u16(documentHash.size)
        out.raw(documentHash)
        return out.toByteArray()
    }

    /** Binds a term revision to the successor's canonical bytes. */
    fun termRevisionPayload(canonicalBytes: ByteArray): ByteArray {
        if (canonicalBytes.isEmpty()) throw DealFormatException("Deal envelope was rejected.")
        val out = ByteArrayOutput()
        out.raw(TERM_REVISION_DOMAIN.toByteArray(StandardCharsets.UTF_8))
        out.u8(0)
        out.u16(DealFeature.PROTOCOL_VERSION)
        out.u32(canonicalBytes.size)
        out.raw(canonicalBytes)
        return out.toByteArray()
    }

    /** Checks a name-on-version statement against the current document hash. */
    fun verifyNameOnVersion(deal: CanonicalDeal, partyId: String, signature: ByteArray) {
        val identity = deal.identity ?: throw DealFormatException("This document has no identity binding.")
        val pin = identity.pin(partyId)
        if (pin.nameStatus != LegalNameStatus.SELF_DECLARED_UNVERIFIED || pin.nameConfirmation == null) {
            throw DealStateException("Each participant must confirm their own legal name before signing.")
        }
        val payload = nameOnVersionPayload(
            dealId = deal.dealId,
            versionId = deal.versionId,
            role = pin.role,
            participant = pin.participant,
            legalName = pin.legalName,
            documentHash = deal.documentHash(),
        )
        DealIdentityCrypto.verify(pin.participant.messagingPublicKey, signature, payload)
    }

    fun assemble(
        dealId: String,
        versionId: String,
        parentVersionId: String?,
        createdAtEpochMs: Long,
        currency: String?,
        amountMinor: Long?,
        terms: String,
        details: AgreementDetails,
        ancestry: List<String>,
        drafts: List<DealPartyDraft>,
        annex: ContractAnnex? = null,
    ): CanonicalDeal {
        if (drafts.size != 2) throw DealFormatException("Phase 1 agreements have two parties.")
        val parties = drafts.map { draft ->
            DealParty(
                partyId = draft.partyId,
                role = draft.role,
                label = draft.label,
                signingPublicKey = draft.signingPublicKey.copyOf(),
                encryptionPublicKey = draft.encryptionPublicKey.copyOf(),
            )
        }
        val pins = drafts.map { draft ->
            DealPartyPin(
                partyId = draft.partyId,
                role = draft.role,
                participant = draft.participant,
                dealSigningPublicKey = draft.signingPublicKey.copyOf(),
                dealEncryptionPublicKey = draft.encryptionPublicKey.copyOf(),
                legalName = draft.legalName,
                nameStatus = draft.nameStatus,
                bindingSignature = draft.bindingSignature,
                nameConfirmation = draft.nameConfirmation,
            )
        }
        return CanonicalDeal.create(
            dealId = dealId,
            versionId = versionId,
            parentVersionId = parentVersionId,
            createdAtEpochMs = createdAtEpochMs,
            currency = currency,
            amountMinor = amountMinor,
            parties = parties,
            ancestry = ancestry,
            terms = terms,
            details = details,
            identity = DealIdentityBlock(pins),
            annex = annex,
        )
    }

    /**
     * Writes [signer]'s binding into [targetPartyId]. The signer is refused
     * before any signature is produced when that slot belongs to someone else.
     */
    fun authorizeOwnBinding(
        deal: CanonicalDeal,
        targetPartyId: String,
        signer: DealMessagingSigner,
        signatureCount: Int,
        dealStatus: DealStatus,
    ): CanonicalDeal {
        val pin = ownPin(deal, targetPartyId, signer)
        refuseSignedMutation(signatureCount, dealStatus)
        val payload = bindingPayload(
            dealId = deal.dealId,
            role = pin.role,
            participant = pin.participant,
            dealSigningPublicKey = pin.dealSigningPublicKey,
            dealEncryptionPublicKey = pin.dealEncryptionPublicKey,
        )
        val signature = signer.sign(payload)
        DealIdentityCrypto.verify(pin.participant.messagingPublicKey, signature, payload)
        val identity = deal.identity ?: throw DealFormatException("This document has no identity binding.")
        return deal.withIdentity(identity.replace(pin.partyId, pin.withBinding(signature)))
    }

    /**
     * Confirms [signer]'s own legal name. The other party's name slot is left
     * untouched, and no confirmation is produced for that slot.
     */
    fun confirmOwnLegalName(
        deal: CanonicalDeal,
        targetPartyId: String,
        signer: DealMessagingSigner,
        legalName: String,
        signatureCount: Int,
        dealStatus: DealStatus,
    ): CanonicalDeal {
        val pin = ownPin(deal, targetPartyId, signer)
        refuseSignedMutation(signatureCount, dealStatus)
        requireLegalName(legalName)
        val payload = legalNamePayload(deal.dealId, pin.role, pin.participant, legalName)
        val signature = signer.sign(payload)
        DealIdentityCrypto.verify(pin.participant.messagingPublicKey, signature, payload)
        val identity = deal.identity ?: throw DealFormatException("This document has no identity binding.")
        return deal.withIdentity(identity.replace(pin.partyId, pin.withLegalName(legalName, signature)))
    }

    fun verify(dealId: String, parties: List<DealParty>, identity: DealIdentityBlock) {
        if (identity.pins.size != parties.size) {
            throw DealFormatException("Identity binding does not match the parties.")
        }
        for (index in parties.indices) {
            val party = parties[index]
            val pin = identity.pins[index]
            if (pin.partyId != party.partyId || pin.role != party.role) {
                throw DealFormatException("Identity binding does not match the parties.")
            }
            if (!pin.dealSigningPublicKey.contentEquals(party.signingPublicKey) ||
                !pin.dealEncryptionPublicKey.contentEquals(party.encryptionPublicKey)
            ) {
                throw DealFormatException("Identity binding does not match the pinned deal keys.")
            }
            pin.bindingSignature?.let { signature ->
                val payload = bindingPayload(
                    dealId = dealId,
                    role = pin.role,
                    participant = pin.participant,
                    dealSigningPublicKey = pin.dealSigningPublicKey,
                    dealEncryptionPublicKey = pin.dealEncryptionPublicKey,
                )
                DealIdentityCrypto.verify(pin.participant.messagingPublicKey, signature, payload)
            }
            pin.nameConfirmation?.let { signature ->
                val payload = legalNamePayload(dealId, pin.role, pin.participant, pin.legalName)
                DealIdentityCrypto.verify(pin.participant.messagingPublicKey, signature, payload)
            }
        }
    }

    /** Both bindings and both self-declared names must already be in the snapshot. */
    fun requireSignable(deal: CanonicalDeal, signerPartyId: String) {
        val identity = deal.identity ?: return
        deal.party(signerPartyId)
        for (pin in identity.pins) {
            val bindingSignature = pin.bindingSignature
                ?: throw DealStateException("Both identity bindings are required before signing.")
            val binding = bindingPayload(
                dealId = deal.dealId,
                role = pin.role,
                participant = pin.participant,
                dealSigningPublicKey = pin.dealSigningPublicKey,
                dealEncryptionPublicKey = pin.dealEncryptionPublicKey,
            )
            DealIdentityCrypto.verify(pin.participant.messagingPublicKey, bindingSignature, binding)
            val nameConfirmation = pin.nameConfirmation
            if (pin.nameStatus != LegalNameStatus.SELF_DECLARED_UNVERIFIED ||
                pin.legalName.isEmpty() ||
                nameConfirmation == null
            ) {
                throw DealStateException("Each participant must confirm their own legal name before signing.")
            }
            val name = legalNamePayload(deal.dealId, pin.role, pin.participant, pin.legalName)
            DealIdentityCrypto.verify(pin.participant.messagingPublicKey, nameConfirmation, name)
        }
    }

    /**
     * A revision keeps the same parties, ArmorChat IDs, messaging keys, and
     * deal keys. It also keeps the canonical version, so a version 1 or 2
     * document is not rewritten as version 3.
     */
    fun requireSameParticipants(previous: CanonicalDeal, successor: CanonicalDeal) {
        if (previous.canonicalVersion != successor.canonicalVersion) {
            throw DealFormatException("A revision cannot change the canonical version.")
        }
        if (previous.parties.size != successor.parties.size) {
            throw DealFormatException("Participant identities cannot change.")
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
                throw DealFormatException("Participant identities cannot change.")
            }
        }
        val before = previous.identity
        val after = successor.identity
        if ((before == null) != (after == null)) {
            throw DealFormatException("A revision cannot change the canonical version.")
        }
        if (before == null || after == null) return
        for (index in before.pins.indices) {
            val left = before.pins[index]
            val right = after.pins[index]
            if (left.partyId != right.partyId ||
                left.role != right.role ||
                left.participant.armorChatId != right.participant.armorChatId ||
                !left.participant.messagingPublicKey.contentEquals(right.participant.messagingPublicKey) ||
                !left.dealSigningPublicKey.contentEquals(right.dealSigningPublicKey) ||
                !left.dealEncryptionPublicKey.contentEquals(right.dealEncryptionPublicKey) ||
                !sameSignature(left.bindingSignature, right.bindingSignature) ||
                left.legalName != right.legalName ||
                left.nameStatus != right.nameStatus ||
                !sameSignature(left.nameConfirmation, right.nameConfirmation)
            ) {
                throw DealFormatException("Participant identities cannot change.")
            }
        }
    }

    private fun ownPin(deal: CanonicalDeal, targetPartyId: String, signer: DealMessagingSigner): DealPartyPin {
        val identity = deal.identity
            ?: throw DealFormatException("A canonical version 1 or 2 document cannot gain an identity binding.")
        val pin = identity.pin(targetPartyId)
        if (!pin.participant.messagingPublicKey.contentEquals(signer.publicKey)) {
            throw ForeignPartyException()
        }
        return pin
    }

    private fun refuseSignedMutation(signatureCount: Int, dealStatus: DealStatus) {
        if (signatureCount < 0) throw DealFormatException("Signature count cannot be negative.")
        if (signatureCount > 0 || DealLifecycle.claimsFinality(dealStatus)) throw ImmutableVersionException()
    }

    private fun writeKey(out: ByteArrayOutput, key: ByteArray) {
        out.u16(key.size)
        out.raw(key)
    }

    private fun sameSignature(left: ByteArray?, right: ByteArray?): Boolean {
        if (left == null || right == null) return left == null && right == null
        return left.contentEquals(right)
    }
}

/** Messaging identity key that can authorize only the statement it is willing to sign. */
interface DealMessagingSigner {
    val publicKey: ByteArray
    fun sign(payload: ByteArray): ByteArray
}

internal fun requireLegalName(value: String) {
    if (value != value.trim()) throw DealFormatException("Legal name is not canonical.")
    requireCanonicalText(
        value,
        allowNewlines = false,
        maxBytes = DealIdentity.MAX_LEGAL_NAME_BYTES,
        emptyAllowed = false,
    )
}
