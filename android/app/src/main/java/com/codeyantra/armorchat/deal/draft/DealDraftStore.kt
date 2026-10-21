package com.codeyantra.armorchat.deal.draft

import android.database.sqlite.SQLiteConstraintException
import com.codeyantra.armorchat.BuildConfig
import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealKeyProtector
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.origin.ChatDealFactory
import com.codeyantra.armorchat.deal.origin.ChatDealIds
import com.codeyantra.armorchat.deal.origin.ChatDealOrigin
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealDraftRow
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner

/** Status strings stored for a local draft. These are not [com.codeyantra.armorchat.deal.domain.DealStatus]. */
object DealDrafts {
    const val STATUS_LOCAL = "LOCAL_DRAFT"
    const val STATUS_CANCELLED = "CANCELLED"
    const val PUBLIC_LOCAL = "Local draft. Not sent. Not an accepted agreement."
    const val PUBLIC_CANCELLED = "Cancelled local draft. Not an accepted agreement."
    const val PAYLOAD_PURPOSE = "armorchat-deal-draft-v1"
    const val SIGNING_PURPOSE = "armorchat-deal-draft-sign-v1"
    const val ENCRYPTION_PURPOSE = "armorchat-deal-draft-wrap-v1"
    const val MAX_PURPOSE_BYTES = 400

    fun publicStatus(status: String): String = when (status) {
        STATUS_LOCAL -> PUBLIC_LOCAL
        STATUS_CANCELLED -> PUBLIC_CANCELLED
        else -> throw DealFormatException("Draft status is not a local draft.")
    }
}

/**
 * A restored local draft. [acceptedAgreement] is always false. Party B's
 * binding and deal keys are never present. This object does not print terms
 * or legal names.
 */
class OpenedLocalDraft(
    val draftId: String,
    val origin: ChatDealOrigin,
    val ids: ChatDealIds,
    val editor: DealDraftEditor,
    val status: String,
    val publicStatus: String,
    val partyABinding: ByteArray,
    val partyASigningPublic: ByteArray,
    val partyAEncryptionPublic: ByteArray,
    val createdAtEpochMs: Long,
    val acceptedAgreement: Boolean = false,
    val sent: Boolean = false,
    val partyBBindingPresent: Boolean = false,
    val partyBDealKeysPresent: Boolean = false,
) {
    override fun toString(): String = "OpenedLocalDraft(status=$status, accepted=$acceptedAgreement, sent=$sent)"
}

/** Identity check for a later send. This object does not transmit anything. */
class DraftSendCheck(
    val transmitted: Boolean,
    val acceptedAgreement: Boolean,
    val identitiesMatch: Boolean,
) {
    override fun toString(): String =
        "DraftSendCheck(transmitted=$transmitted, accepted=$acceptedAgreement)"
}

/**
 * Party A's draft, revalidated for transport. Party B's deal key is absent.
 * This object does not print terms or legal names.
 */
class SendableDraft(
    val opened: OpenedLocalDraft,
    val purpose: String,
    val effectiveDate: String,
    val partyAKeys: DealKeyMaterial,
    val proposerLabel: String,
    val counterpartyLabel: String,
) {
    override fun toString(): String = "SendableDraft(acceptedAgreement=false)"
}

/**
 * Encrypts one chat-origin draft into armorchat-deal.db.
 *
 * The draft table is not deal version history. Updates replace ciphertext in
 * the same unsent row. Cancellation changes only that row's status. A deal id
 * that already has a stored record is left untouched. A conversation may hold
 * several drafts when each earlier draft is tied to its own submitted Deal.
 */
class DealDraftStore(
    private val database: DealDatabase,
    private val protector: DealKeyProtector,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val debugBuild: Boolean,
    private val productionUiEnabled: Boolean = DealFeature.PRODUCTION_UI_ENABLED,
) {
    private val dao = database.dealDao()

    fun createFromChat(
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
        editor: DealDraftEditor,
        localDealKeys: DealKeyMaterial,
        localSigner: DealMessagingSigner,
        typedPartyBId: String? = null,
        ids: ChatDealIds? = null,
    ): OpenedLocalDraft {
        requireGate()
        if (!typedPartyBId.isNullOrBlank()) {
            throw DealFormatException("A typed Party B id is not the conversation contact.")
        }
        if (conversation != null && unsentRow(conversation.id) != null) {
            throw DealStateException("This conversation already has a local draft.")
        }
        val allocated = ids ?: freshIds()
        refuseStoredAgreement(allocated.dealId)
        if (dao.findDraftByDealId(allocated.dealId) != null) {
            throw DealStateException("A local draft cannot replace a stored agreement.")
        }
        val createdAt = clock()
        if (createdAt <= 0L || createdAt > CanonicalDeal.MAX_TIMESTAMP_MS) {
            throw DealFormatException("Timestamp is outside the accepted range.")
        }
        val pending = ChatDealFactory.create(
            debugBuild = debugBuild,
            productionUiEnabled = productionUiEnabled,
            local = local,
            conversation = conversation,
            contact = contact,
            claimedPartyBId = null,
            ids = allocated,
            createdAtEpochMs = createdAt,
            localDealKeys = localDealKeys,
            localSigner = localSigner,
            terms = editor.terms,
            details = editor.details,
            currency = editor.currency,
            amountMinor = editor.amountMinor,
        )
        val envelope = DealDraftEnvelope(pending, editor.purpose, editor.effectiveDate, editor.partyALegalName)
        val draftId = freshDraftId()
        val sealed = seal(envelope, localDealKeys)
        try {
            database.runInTransaction {
                refuseStoredAgreement(allocated.dealId)
                if (dao.findDraft(draftId) != null || dao.findDraftByDealId(allocated.dealId) != null) {
                    throw DealStateException("A local draft cannot replace a stored agreement.")
                }
                if (unsentRow(pending.origin.conversationId) != null) {
                    throw DealStateException("This conversation already has a local draft.")
                }
                try {
                    dao.insertDraft(
                        DealDraftRow(
                            draftId = draftId,
                            dealId = pending.ids.dealId,
                            versionId = pending.ids.versionId,
                            conversationId = pending.origin.conversationId,
                            contactId = pending.origin.contactId,
                            status = DealDrafts.STATUS_LOCAL,
                            sealedPayload = sealed.payload.copyOf(),
                            sealedPartyASigning = sealed.signing.copyOf(),
                            sealedPartyAEncryption = sealed.encryption.copyOf(),
                            createdAtEpochMs = createdAt,
                            updatedAtEpochMs = createdAt,
                        ),
                    )
                } catch (e: SQLiteConstraintException) {
                    throw DealStateException("A local draft cannot replace a stored agreement.")
                }
            }
        } finally {
            sealed.wipe()
        }
        return open(draftId)
    }

    fun update(
        draftId: String,
        editor: DealDraftEditor,
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
    ): OpenedLocalDraft {
        requireGate()
        database.runInTransaction {
            val row = dao.findDraft(draftId) ?: throw DealStateException("The local draft is missing.")
            if (row.status != DealDrafts.STATUS_LOCAL) {
                throw DealStateException("A cancelled draft cannot be edited.")
            }
            if (hasProgress(row.dealId)) {
                throw DealStateException("A sent agreement cannot be replaced by a draft.")
            }
            refuseStoredAgreement(row.dealId)
            val loaded = load(row)
            ChatDealFactory.revalidate(loaded.envelope.pending.origin, local, conversation, contact)
            if (conversation == null || contact == null ||
                conversation.id != row.conversationId || contact.id != row.contactId
            ) {
                throw DealFormatException("Party B must be the contact in this conversation.")
            }
            val replacement = loaded.envelope.withContent(editor)
            val sealed = seal(replacement, loaded.keys)
            try {
                val updated = dao.updateDraftContent(
                    draftId = draftId,
                    sealedPayload = sealed.payload.copyOf(),
                    sealedPartyASigning = sealed.signing.copyOf(),
                    sealedPartyAEncryption = sealed.encryption.copyOf(),
                    updatedAtEpochMs = clock(),
                )
                if (updated != 1) throw DealStateException("A cancelled draft cannot be edited.")
            } finally {
                sealed.wipe()
            }
        }
        return open(draftId)
    }

    fun cancel(draftId: String): OpenedLocalDraft {
        requireGate()
        database.runInTransaction {
            val row = dao.findDraft(draftId) ?: throw DealStateException("The local draft is missing.")
            refuseStoredAgreement(row.dealId)
            if (row.status == DealDrafts.STATUS_CANCELLED) return@runInTransaction
            if (row.status != DealDrafts.STATUS_LOCAL) {
                throw DealFormatException("Draft status is not a local draft.")
            }
            val updated = dao.cancelDraft(draftId, clock())
            if (updated != 1) throw DealStateException("A cancelled draft cannot be edited.")
        }
        return open(draftId)
    }

    fun open(draftId: String): OpenedLocalDraft {
        requireGate()
        val row = dao.findDraft(draftId) ?: throw DealStateException("The local draft is missing.")
        return load(row).opened
    }

    /** The newest draft that has no stored record for its own Deal ID. */
    fun unsent(conversationId: String): OpenedLocalDraft? {
        requireGate()
        val row = unsentRow(conversationId) ?: return null
        return open(row.draftId)
    }

    /**
     * Submitted drafts whose Deal row is not stored yet.
     * The ids are opaque. This does not open the sealed draft.
     */
    fun pendingSentDealIds(conversationId: String): List<String> {
        requireGate()
        return dao.activeDrafts(conversationId)
            .asSequence()
            .filter { hasProgress(it.dealId) && dao.findDeal(it.dealId) == null }
            .map { it.dealId }
            .distinct()
            .toList()
    }

    fun active(conversationId: String): OpenedLocalDraft? = unsent(conversationId)

    /** Writes the current editor before the screen closes. Does not send. */
    fun persistBeforeLeave(
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
        editor: DealDraftEditor,
        localDealKeys: DealKeyMaterial,
        localSigner: DealMessagingSigner,
        typedPartyBId: String? = null,
    ): OpenedLocalDraft {
        requireGate()
        val existing = conversation?.let { unsentRow(it.id) }
        return if (existing == null) {
            createFromChat(local, conversation, contact, editor, localDealKeys, localSigner, typedPartyBId)
        } else {
            if (!typedPartyBId.isNullOrBlank()) {
                throw DealFormatException("A typed Party B id is not the conversation contact.")
            }
            update(existing.draftId, editor, local, conversation, contact)
        }
    }

    /**
     * Checks the live identity and contact again. Party B's binding is still
     * absent, so this cannot send and cannot call finality.
     */
    fun revalidateBeforeSend(
        draftId: String,
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
    ): DraftSendCheck {
        requireGate()
        val opened = open(draftId)
        if (opened.status != DealDrafts.STATUS_LOCAL) {
            throw DealStateException("A cancelled draft cannot be sent.")
        }
        ChatDealFactory.revalidate(opened.origin, local, conversation, contact)
        return DraftSendCheck(
            transmitted = false,
            acceptedAgreement = false,
            identitiesMatch = true,
        )
    }

    /**
     * Reloads Party A's draft keys after the same identity check used before send.
     * Party B's deal key is not created here.
     */
    fun loadSendable(
        draftId: String,
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
    ): SendableDraft {
        requireGate()
        val row = dao.findDraft(draftId) ?: throw DealStateException("The local draft is missing.")
        if (row.status != DealDrafts.STATUS_LOCAL) {
            throw DealStateException("A cancelled draft cannot be sent.")
        }
        refuseStoredAgreement(row.dealId)
        val loaded = load(row)
        ChatDealFactory.revalidate(loaded.envelope.pending.origin, local, conversation, contact)
        if (conversation == null || contact == null ||
            conversation.id != row.conversationId || contact.id != row.contactId
        ) {
            throw DealFormatException("Party B must be the contact in this conversation.")
        }
        return SendableDraft(
            opened = loaded.opened,
            purpose = loaded.envelope.purpose,
            effectiveDate = loaded.envelope.effectiveDate,
            partyAKeys = loaded.keys,
            proposerLabel = loaded.envelope.pending.proposerLabel,
            counterpartyLabel = loaded.envelope.pending.counterpartyLabel,
        )
    }

    private fun requireGate() {
        if (!BuildConfig.DEBUG || !ChatDealFactory.enabled(debugBuild, productionUiEnabled)) {
            throw DealStateException("Deal creation from chat is disabled.")
        }
    }

    private fun refuseStoredAgreement(dealId: String) {
        if (dao.findDeal(dealId) != null || dao.signatureCount(dealId) > 0 || dao.versionCount(dealId) > 0) {
            throw DealStateException("A local draft cannot replace a stored agreement.")
        }
    }

    /** Progress is counted only for this Deal ID, never for another Deal in the conversation. */
    private fun hasProgress(dealId: String): Boolean =
        dao.findDeal(dealId) != null ||
            dao.versionCount(dealId) > 0 ||
            dao.signatureCount(dealId) > 0 ||
            dao.transportCountForDeal(dealId) > 0 ||
            dao.closureCountForDeal(dealId) > 0

    private fun unsentRow(conversationId: String): DealDraftRow? =
        dao.activeDrafts(conversationId).firstOrNull { !hasProgress(it.dealId) }

    private fun load(row: DealDraftRow): LoadedDraft {
        if (row.status != DealDrafts.STATUS_LOCAL && row.status != DealDrafts.STATUS_CANCELLED) {
            throw DealFormatException("Draft status is not a local draft.")
        }
        val plain = protector.open(row.sealedPayload, DealDrafts.PAYLOAD_PURPOSE)
        val signingPlain = protector.open(row.sealedPartyASigning, DealDrafts.SIGNING_PURPOSE)
        val encryptionPlain = protector.open(row.sealedPartyAEncryption, DealDrafts.ENCRYPTION_PURPOSE)
        try {
            val envelope = DealDraftEnvelope.parse(plain)
            val pending = envelope.pending
            if (row.dealId != pending.ids.dealId || row.versionId != pending.ids.versionId ||
                row.conversationId != pending.origin.conversationId || row.contactId != pending.origin.contactId
            ) {
                throw DealFormatException("The stored draft binding does not match its row.")
            }
            val keys = DealKeyMaterial.fromSerialized(signingPlain, encryptionPlain)
            if (!keys.signingPublic.contentEquals(pending.proposerSigningPublic) ||
                !keys.encryptionPublic.contentEquals(pending.proposerEncryptionPublic)
            ) {
                throw DealFormatException("The stored draft binding does not match its row.")
            }
            val opened = OpenedLocalDraft(
                draftId = row.draftId,
                origin = pending.origin,
                ids = pending.ids,
                editor = envelope.editor(),
                status = row.status,
                publicStatus = DealDrafts.publicStatus(row.status),
                partyABinding = pending.proposerBinding.copyOf(),
                partyASigningPublic = pending.proposerSigningPublic.copyOf(),
                partyAEncryptionPublic = pending.proposerEncryptionPublic.copyOf(),
                createdAtEpochMs = pending.createdAtEpochMs,
            )
            return LoadedDraft(envelope, keys, opened)
        } finally {
            plain.fill(0)
            signingPlain.fill(0)
            encryptionPlain.fill(0)
        }
    }

    private fun seal(envelope: DealDraftEnvelope, keys: DealKeyMaterial): SealedDraft {
        val plain = DealDraftEnvelope.encode(envelope)
        val signing = keys.serializedSigning()
        val encryption = keys.serializedEncryption()
        try {
            return SealedDraft(
                payload = protector.seal(plain, DealDrafts.PAYLOAD_PURPOSE),
                signing = protector.seal(signing, DealDrafts.SIGNING_PURPOSE),
                encryption = protector.seal(encryption, DealDrafts.ENCRYPTION_PURPOSE),
            )
        } finally {
            plain.fill(0)
            signing.fill(0)
            encryption.fill(0)
        }
    }

    private fun freshIds(): ChatDealIds {
        repeat(4) {
            val ids = ChatDealIds(
                dealId = DealIds.generate(),
                versionId = DealIds.generate(),
                proposerPartyId = DealIds.generate(),
                counterpartyPartyId = DealIds.generate(),
            )
            if (dao.findDeal(ids.dealId) == null && dao.findDraftByDealId(ids.dealId) == null &&
                dao.signatureCount(ids.dealId) == 0 && dao.versionCount(ids.dealId) == 0
            ) {
                return ids
            }
        }
        throw DealStateException("A local draft could not be allocated.")
    }

    private fun freshDraftId(): String {
        repeat(4) {
            val id = DealIds.generate()
            if (dao.findDraft(id) == null) return id
        }
        throw DealStateException("A local draft could not be allocated.")
    }

    private class SealedDraft(
        val payload: ByteArray,
        val signing: ByteArray,
        val encryption: ByteArray,
    ) {
        fun wipe() {
            payload.fill(0)
            signing.fill(0)
            encryption.fill(0)
        }
    }

    private class LoadedDraft(
        val envelope: DealDraftEnvelope,
        val keys: DealKeyMaterial,
        val opened: OpenedLocalDraft,
    )
}
