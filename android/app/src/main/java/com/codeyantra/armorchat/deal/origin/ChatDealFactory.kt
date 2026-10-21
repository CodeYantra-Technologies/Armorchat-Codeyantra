package com.codeyantra.armorchat.deal.origin

import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.domain.DealPartyDraft
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.domain.requireCanonicalText
import com.codeyantra.armorchat.deal.domain.requireRawKey
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.UserIdentity

/**
 * Identifiers chosen for one chat-origin deal. Party ids are deal-local.
 * The ArmorChat IDs are pinned separately and do not come from these values.
 */
class ChatDealIds(
    val dealId: String,
    val versionId: String,
    val proposerPartyId: String,
    val counterpartyPartyId: String,
) {
    init {
        DealIds.require(dealId)
        DealIds.require(versionId)
        DealIds.require(proposerPartyId)
        DealIds.require(counterpartyPartyId)
        val distinct = setOf(dealId, versionId, proposerPartyId, counterpartyPartyId)
        if (distinct.size != 4) throw DealFormatException("Deal identifiers must be unique.")
    }
}

/** The conversation and the two verified messaging identities a deal is bound to. */
class ChatDealOrigin(
    val conversationId: String,
    val contactId: String,
    val partyA: VerifiedDealParticipant,
    val partyB: VerifiedDealParticipant,
) {
    override fun toString(): String = "ChatDealOrigin"
}

/**
 * Party A's local half of a chat deal. Party B's deal key and binding are absent.
 * This device has not signed Party B's binding.
 */
class PendingChatDeal(
    val origin: ChatDealOrigin,
    val ids: ChatDealIds,
    val createdAtEpochMs: Long,
    val currency: String?,
    val amountMinor: Long?,
    val terms: String,
    val details: AgreementDetails,
    val proposerLabel: String,
    val counterpartyLabel: String,
    val proposerSigningPublic: ByteArray,
    val proposerEncryptionPublic: ByteArray,
    val proposerBinding: ByteArray,
) {
    override fun toString(): String = "PendingChatDeal"
}

/** A canonical version 3 draft bound to the chat. Legal names still start empty. */
class ReadyChatDeal(
    val origin: ChatDealOrigin,
    val deal: CanonicalDeal,
) {
    override fun toString(): String = "ReadyChatDeal"
}

/**
 * Creates a deal from one existing one-to-one conversation.
 *
 * Party A is the local ArmorChat identity. Party B is that conversation's
 * contact. A typed Party B id is rejected unless it is the same contact, and
 * it is never the source of the pin. Party B's binding must arrive already
 * signed by Party B's messaging key. This factory does not generate Party B's
 * deal keys and does not sign Party B's binding.
 *
 * The gate stays closed for release builds and while production deal UI is enabled.
 */
object ChatDealFactory {
    fun enabled(
        debugBuild: Boolean,
        productionUiEnabled: Boolean = DealFeature.PRODUCTION_UI_ENABLED,
    ): Boolean = debugBuild && !productionUiEnabled

    fun create(
        debugBuild: Boolean,
        productionUiEnabled: Boolean = DealFeature.PRODUCTION_UI_ENABLED,
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
        claimedPartyBId: String?,
        ids: ChatDealIds,
        createdAtEpochMs: Long,
        localDealKeys: DealKeyMaterial,
        localSigner: DealMessagingSigner,
        terms: String,
        details: AgreementDetails,
        currency: String? = null,
        amountMinor: Long? = null,
    ): PendingChatDeal {
        requireEnabled(debugBuild, productionUiEnabled)
        val origin = bindOrigin(local, conversation, contact, claimedPartyBId)
        if (!localSigner.publicKey.contentEquals(origin.partyA.messagingPublicKey)) {
            throw DealFormatException("The local signing key does not match the ArmorChat identity.")
        }
        requireSeparateDealKeys(origin.partyA, localDealKeys.signingPublic, localDealKeys.encryptionPublic)
        val payload = DealIdentity.bindingPayload(
            dealId = ids.dealId,
            role = PartyRole.PROPOSER,
            participant = origin.partyA,
            dealSigningPublicKey = localDealKeys.signingPublic,
            dealEncryptionPublicKey = localDealKeys.encryptionPublic,
        )
        val binding = localSigner.sign(payload)
        DealIdentityCrypto.verify(origin.partyA.messagingPublicKey, binding, payload)
        return PendingChatDeal(
            origin = origin,
            ids = ids,
            createdAtEpochMs = createdAtEpochMs,
            currency = currency,
            amountMinor = amountMinor,
            terms = terms,
            details = details,
            proposerLabel = chatLabel(local?.displayName.orEmpty(), "Party A"),
            counterpartyLabel = chatLabel(contact?.displayName.orEmpty(), "Party B"),
            proposerSigningPublic = localDealKeys.signingPublic.copyOf(),
            proposerEncryptionPublic = localDealKeys.encryptionPublic.copyOf(),
            proposerBinding = binding.copyOf(),
        )
    }

    /**
     * Installs Party B's already-signed binding. There is no signer argument:
     * Party A's device cannot produce this signature.
     */
    fun acceptCounterparty(
        debugBuild: Boolean,
        productionUiEnabled: Boolean = DealFeature.PRODUCTION_UI_ENABLED,
        pending: PendingChatDeal,
        counterpartySigningPublic: ByteArray,
        counterpartyEncryptionPublic: ByteArray,
        counterpartyBinding: ByteArray,
    ): ReadyChatDeal {
        requireEnabled(debugBuild, productionUiEnabled)
        requireRawKey(counterpartySigningPublic, "signing")
        requireRawKey(counterpartyEncryptionPublic, "encryption")
        if (counterpartyBinding.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        requireSeparateDealKeys(pending.origin.partyB, counterpartySigningPublic, counterpartyEncryptionPublic)
        if (counterpartySigningPublic.contentEquals(pending.proposerSigningPublic) ||
            counterpartyEncryptionPublic.contentEquals(pending.proposerEncryptionPublic)
        ) {
            throw DealFormatException("Parties must have distinct public keys.")
        }
        val payload = DealIdentity.bindingPayload(
            dealId = pending.ids.dealId,
            role = PartyRole.COUNTERPARTY,
            participant = pending.origin.partyB,
            dealSigningPublicKey = counterpartySigningPublic,
            dealEncryptionPublicKey = counterpartyEncryptionPublic,
        )
        DealIdentityCrypto.verify(pending.origin.partyB.messagingPublicKey, counterpartyBinding, payload)
        val deal = DealIdentity.assemble(
            dealId = pending.ids.dealId,
            versionId = pending.ids.versionId,
            parentVersionId = null,
            createdAtEpochMs = pending.createdAtEpochMs,
            currency = pending.currency,
            amountMinor = pending.amountMinor,
            terms = pending.terms,
            details = pending.details,
            ancestry = emptyList(),
            drafts = listOf(
                DealPartyDraft(
                    partyId = pending.ids.proposerPartyId,
                    role = PartyRole.PROPOSER,
                    label = pending.proposerLabel,
                    participant = pending.origin.partyA,
                    signingPublicKey = pending.proposerSigningPublic,
                    encryptionPublicKey = pending.proposerEncryptionPublic,
                    bindingSignature = pending.proposerBinding,
                ),
                DealPartyDraft(
                    partyId = pending.ids.counterpartyPartyId,
                    role = PartyRole.COUNTERPARTY,
                    label = pending.counterpartyLabel,
                    participant = pending.origin.partyB,
                    signingPublicKey = counterpartySigningPublic,
                    encryptionPublicKey = counterpartyEncryptionPublic,
                    bindingSignature = counterpartyBinding,
                ),
            ),
        )
        return ReadyChatDeal(pending.origin, deal)
    }

    /** Runs the same identity checks again against the records that would be sent. */
    fun prepareToSend(
        debugBuild: Boolean,
        productionUiEnabled: Boolean = DealFeature.PRODUCTION_UI_ENABLED,
        ready: ReadyChatDeal,
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
    ) {
        requireEnabled(debugBuild, productionUiEnabled)
        revalidate(ready.origin, local, conversation, contact)
        val pins = ready.deal.identity?.pins ?: throw DealFormatException("This document has no identity binding.")
        val proposer = pins.single { it.role == PartyRole.PROPOSER }
        val counterparty = pins.single { it.role == PartyRole.COUNTERPARTY }
        if (!sameParticipant(proposer.participant, ready.origin.partyA) ||
            !sameParticipant(counterparty.participant, ready.origin.partyB)
        ) {
            throw DealFormatException("The ArmorChat identity changed before the deal was sent.")
        }
    }

    fun revalidate(
        origin: ChatDealOrigin,
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
    ) {
        val bound = bindOrigin(local, conversation, contact, claimedPartyBId = null)
        if (bound.conversationId != origin.conversationId || bound.contactId != origin.contactId ||
            !sameParticipant(bound.partyA, origin.partyA) ||
            !sameParticipant(bound.partyB, origin.partyB)
        ) {
            throw DealFormatException("The ArmorChat identity changed before the deal was sent.")
        }
    }

    private fun bindOrigin(
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
        claimedPartyBId: String?,
    ): ChatDealOrigin {
        if (local == null) throw DealFormatException("The local ArmorChat identity is missing.")
        if (conversation == null) throw DealFormatException("The conversation is missing.")
        if (contact == null) throw DealFormatException("The conversation contact is missing.")
        val partyA = VerifiedDealParticipant.fromUser(local)
        val partyB = VerifiedDealParticipant.fromContact(contact)
        if (conversation.contactId != contact.id) {
            throw DealFormatException("Party B must be the contact in this conversation.")
        }
        if (!claimedPartyBId.isNullOrBlank()) {
            val claimed = try {
                ArmorChatId.normalize(claimedPartyBId)
            } catch (e: IllegalArgumentException) {
                throw DealFormatException("A typed Party B id is not the conversation contact.")
            }
            if (claimed != partyB.armorChatId) {
                throw DealFormatException("A typed Party B id is not the conversation contact.")
            }
        }
        if (partyA.armorChatId == partyB.armorChatId ||
            partyA.messagingPublicKey.contentEquals(partyB.messagingPublicKey)
        ) {
            throw DealFormatException("A participant cannot deal with themself.")
        }
        return ChatDealOrigin(conversation.id, contact.id, partyA, partyB)
    }

    private fun requireEnabled(debugBuild: Boolean, productionUiEnabled: Boolean) {
        if (!enabled(debugBuild, productionUiEnabled)) {
            throw DealStateException("Deal creation from chat is disabled.")
        }
    }

    private fun requireSeparateDealKeys(
        participant: VerifiedDealParticipant,
        signingPublic: ByteArray,
        encryptionPublic: ByteArray,
    ) {
        requireRawKey(signingPublic, "signing")
        requireRawKey(encryptionPublic, "encryption")
        if (signingPublic.contentEquals(encryptionPublic)) {
            throw DealFormatException("Signing and encryption keys must be distinct.")
        }
        if (participant.messagingPublicKey.contentEquals(signingPublic) ||
            participant.messagingPublicKey.contentEquals(encryptionPublic)
        ) {
            throw DealFormatException("Deal keys must stay separate from the messaging identity key.")
        }
    }

    private fun sameParticipant(left: VerifiedDealParticipant, right: VerifiedDealParticipant): Boolean =
        left.armorChatId == right.armorChatId &&
            left.messagingPublicKey.contentEquals(right.messagingPublicKey)

    private fun chatLabel(value: String, fallback: String): String {
        return try {
            requireCanonicalText(value, allowNewlines = false, maxBytes = 80, emptyAllowed = false)
            value
        } catch (e: DealFormatException) {
            fallback
        }
    }
}
