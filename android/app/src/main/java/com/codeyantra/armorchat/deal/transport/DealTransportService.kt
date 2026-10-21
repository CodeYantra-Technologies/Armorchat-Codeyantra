package com.codeyantra.armorchat.deal.transport

import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.PartyWrapTarget
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.ClosureAdmit
import com.codeyantra.armorchat.deal.domain.ClosureConfirmation
import com.codeyantra.armorchat.deal.domain.ContractAnnex
import com.codeyantra.armorchat.deal.domain.DealClosure
import com.codeyantra.armorchat.deal.domain.DealAlreadySignedException
import com.codeyantra.armorchat.deal.domain.DealConflictException
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealException
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealReviewRequiredException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.domain.DealPartyDraft
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import com.codeyantra.armorchat.deal.domain.LegalNameStatus
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.SigningProgress
import com.codeyantra.armorchat.deal.domain.SigningStage
import com.codeyantra.armorchat.deal.domain.SigningWorkflow
import com.codeyantra.armorchat.deal.storage.DealClosureStore
import com.codeyantra.armorchat.deal.storage.DealSignatureRow
import com.codeyantra.armorchat.deal.storage.SigningProgressStore
import com.codeyantra.armorchat.deal.draft.DealDraftStore
import com.codeyantra.armorchat.deal.draft.SendableDraft
import com.codeyantra.armorchat.deal.origin.ChatDealFactory
import com.codeyantra.armorchat.deal.storage.DealAuditRow
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealLocalKeyRow
import com.codeyantra.armorchat.deal.storage.DealPartyRow
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.storage.DealRow
import com.codeyantra.armorchat.deal.storage.DealTransportRow
import com.codeyantra.armorchat.deal.storage.DealVersionRow
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.google.crypto.tink.KeysetHandle
import java.util.concurrent.Callable

/**
 * Sends and receives Armor Deal envelopes for one local identity.
 *
 * The session cipher is an exporter from the established one-to-one messaging
 * session. This service does not write chat text, attachments, or Mesh packets.
 * A SIGN row is written only after [SigningWorkflow] verifies it. Name
 * confirmation and review are not SIGN rows. Each device uses only the deal
 * private key stored for its own party. Party B's private deal key is created
 * only when this device is Party B.
 */
class DealTransportService(
    private val database: DealDatabase,
    private val protector: com.codeyantra.armorchat.deal.crypto.DealKeyProtector,
    private val drafts: DealDraftStore,
    private val session: DealMessagingSession,
    private val cipher: ExportedSessionCipher,
    private val localRole: PartyRole,
    private val localMessagingSigner: DealMessagingSigner,
    private val debugBuild: Boolean,
    private val productionUiEnabled: Boolean = DealFeature.PRODUCTION_UI_ENABLED,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val dao = database.dealDao()

    init {
        if (!localMessagingSigner.publicKey.contentEquals(session.localMessagingPublic)) {
            throw DealFormatException("The local signing key does not match the ArmorChat identity.")
        }
    }

    fun previewLabel(): String = DealTransportPreview.label()

    fun queueProposal(
        draftId: String,
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
    ): DealTransportResult {
        gate()
        if (localRole != PartyRole.PROPOSER) {
            throw DealFormatException("The event is not authorized for this party.")
        }
        val sendable = drafts.loadSendable(draftId, local, conversation, contact)
        bindDraft(sendable)
        if (dao.transportForDeal(sendable.opened.ids.dealId, DealTransportState.OUT, DealWireEvent.PROPOSAL.wire).isNotEmpty()) {
            throw DealStateException("The proposal is already queued.")
        }
        val pending = sendable.opened
        val offer = DealOffer(
            dealId = pending.ids.dealId,
            versionId = pending.ids.versionId,
            parentVersionId = null,
            createdAtEpochMs = pending.createdAtEpochMs,
            currency = pending.editor.currency,
            amountMinor = pending.editor.amountMinor,
            terms = pending.editor.terms,
            details = pending.editor.details,
            annex = ContractAnnex(sendable.purpose, sendable.effectiveDate.ifEmpty { null }),
            ancestry = emptyList(),
            proposerPartyId = pending.ids.proposerPartyId,
            counterpartyPartyId = pending.ids.counterpartyPartyId,
            proposerLabel = sendable.proposerLabel,
            counterpartyLabel = sendable.counterpartyLabel,
            partyA = pending.origin.partyA,
            partyB = pending.origin.partyB,
            partyASigningPublic = pending.partyASigningPublic,
            partyAEncryptionPublic = pending.partyAEncryptionPublic,
            partyABinding = pending.partyABinding,
            partyAPublicEncryptionKeyset = sendable.partyAKeys.serializedPublicEncryption(),
            conversationId = pending.origin.conversationId,
        )
        val envelope = signOffer(offer, sendable.partyAKeys.signingHandle(), DealWireEvent.PROPOSAL)
        return database.runInTransaction(Callable {
            saveKeys(pending.ids.proposerPartyId, sendable.partyAKeys)
            queue(envelope, "proposal_queued")
        })
    }

    fun queueRevision(dealId: String, terms: String): DealTransportResult {
        gate()
        if (localRole != PartyRole.PROPOSER) {
            throw DealFormatException("The event is not authorized for this party.")
        }
        val previous = latestOutboundOffer(dealId) ?: throw DealStateException("The proposal is missing.")
        if (terms == previous.offer.terms) throw DealFormatException("Revision did not change the canonical document.")
        val versionId = DealIds.generate()
        val createdAt = clock()
        val offer = DealOffer(
            dealId = previous.offer.dealId,
            versionId = versionId,
            parentVersionId = previous.offer.versionId,
            createdAtEpochMs = createdAt,
            currency = previous.offer.currency,
            amountMinor = previous.offer.amountMinor,
            terms = terms,
            details = previous.offer.details,
            annex = previous.offer.annex,
            ancestry = previous.offer.ancestry + previous.offer.versionId,
            proposerPartyId = previous.offer.proposerPartyId,
            counterpartyPartyId = previous.offer.counterpartyPartyId,
            proposerLabel = previous.offer.proposerLabel,
            counterpartyLabel = previous.offer.counterpartyLabel,
            partyA = previous.offer.partyA,
            partyB = previous.offer.partyB,
            partyASigningPublic = previous.offer.partyASigningPublic,
            partyAEncryptionPublic = previous.offer.partyAEncryptionPublic,
            partyABinding = previous.offer.partyABinding,
            partyAPublicEncryptionKeyset = previous.offer.partyAPublicEncryptionKeyset,
            conversationId = previous.offer.conversationId,
        )
        val keys = loadKeys(offer.proposerPartyId) ?: throw DealStateException("The local deal key is missing.")
        val envelope = signOffer(offer, keys.signingHandle(), DealWireEvent.REVISED_PROPOSAL)
        return database.runInTransaction(Callable { queue(envelope, "revision_queued") })
    }

    fun queueChanges(dealId: String, note: String): DealTransportResult =
        queueNote(dealId, note, DealWireEvent.REQUEST_CHANGES, DealEvent.NEGOTIATE, "changes_requested")

    fun queueRejection(dealId: String, note: String): DealTransportResult =
        queueNote(dealId, note, DealWireEvent.REJECTION, DealEvent.REJECT, "rejected")

    /** Party B attests that this device reviewed the current proposal hash. */
    fun queuePartyBReview(dealId: String): DealTransportResult {
        gate()
        if (localRole != PartyRole.COUNTERPARTY) {
            throw DealFormatException("The event is not authorized for this party.")
        }
        return database.runInTransaction(Callable {
            val deal = openCurrent(dealId)
            requireReviewConsent(deal, localPartyId(deal))
            val progress = recover(deal)
            val signature = SigningWorkflow.signPartyBReview(progress, localMessagingSigner)
            val next = SigningWorkflow.adoptPartyBReview(progress, signature)
            saveProgress(next)
            val envelope = message(
                DealWireEvent.REVIEW,
                deal.dealId,
                next.deal.versionId,
                DealEnvelopes.reviewPayload(next.deal.documentHash(), signature),
            )
            queue(envelope, "signing_review_queued")
        })
    }

    /** Confirms this device's own legal name. A new version is stored when the slot was empty. */
    fun queueOwnName(dealId: String, legalName: String): DealTransportResult {
        gate()
        return database.runInTransaction(Callable {
            val deal = openCurrent(dealId)
            val progress = recover(deal)
            val partyId = localPartyId(deal)
            requireReviewConsent(deal, partyId)
            val pin = deal.identity?.pin(partyId) ?: throw DealFormatException("This document has no identity binding.")
            val next = if (
                pin.legalName == legalName &&
                pin.nameStatus == LegalNameStatus.SELF_DECLARED_UNVERIFIED
            ) {
                SigningWorkflow.confirmOwnName(progress, partyId, localMessagingSigner, legalName)
            } else {
                SigningWorkflow.confirmOwnNameOnSuccessor(
                    progress = progress,
                    partyId = partyId,
                    signer = localMessagingSigner,
                    legalName = legalName,
                    newVersionId = DealIds.generate(),
                    revisedAtEpochMs = clock(),
                )
            }
            if (next.deal.versionId != deal.versionId) storeIfAbsent(next.deal)
            saveProgress(next)
            val nameSignature = next.nameConfirmations.first { it.partyId == partyId }.signature
            val successor = if (next.deal.versionId == deal.versionId) null else next.deal.canonicalBytes()
            val reviewSignature = if (localRole == PartyRole.COUNTERPARTY && progress.partyBValidated) {
                SigningWorkflow.signPartyBReview(progress, localMessagingSigner)
            } else {
                null
            }
            try {
                val envelope = message(
                    DealWireEvent.NAME_CONFIRMATION,
                    next.deal.dealId,
                    next.deal.versionId,
                    DealEnvelopes.namePayload(partyId, nameSignature, successor, reviewSignature),
                )
                queue(envelope, "signing_name_queued")
            } finally {
                successor?.fill(0)
            }
        })
    }

    /** Signs the current version with this device's deal key and no other private key. */
    fun queueOwnSignature(dealId: String): DealTransportResult {
        gate()
        return database.runInTransaction(Callable {
            val deal = openCurrent(dealId)
            val progress = recover(deal)
            val partyId = localPartyId(deal)
            requireLocalSignAllowed(deal, progress, partyId)
            requireReviewConsent(deal, partyId)
            val keys = loadKeys(partyId) ?: throw DealStateException("The local deal key is missing.")
            val next = SigningWorkflow.sign(progress, partyId, keys.signingHandle())
            val produced = next.signatures.first { it.partyId == partyId && it.action == SignAction.SIGN }
            persistSignatures(next)
            saveProgress(next)
            val envelope = message(
                DealWireEvent.SIGNATURE,
                next.deal.dealId,
                next.deal.versionId,
                DealEnvelopes.signaturePayload(partyId, produced.signature, produced.signerPublicKey),
            )
            queue(envelope, "signing_sign_queued")
        })
    }

    /**
     * Confirms this phone's own closure kind with this phone's deal key.
     * A duplicate confirmation is queued again for the peer and does not
     * write a second CLOSED transition.
     */
    fun queueOwnClosure(dealId: String): DealTransportResult {
        gate()
        return database.runInTransaction(Callable {
            val deal = openCurrent(dealId)
            val partyId = localPartyId(deal)
            val keys = loadKeys(partyId) ?: throw DealStateException("The local deal key is missing.")
            val confirmation = DealClosure.sign(deal, partyId, keys.signingHandle())
            val admitted = DealClosureStore(database, clock).admit(deal, confirmation)
            if (admitted.admit == ClosureAdmit.REJECTED) {
                throw DealStateException("Closure is not available for this agreement.")
            }
            val envelope = message(
                DealWireEvent.CLOSURE,
                deal.dealId,
                deal.versionId,
                DealEnvelopes.closurePayload(
                    confirmation.kind,
                    confirmation.partyId,
                    confirmation.signature,
                    confirmation.signerPublicKey,
                ),
            )
            queue(envelope, "closure_queued")
        })
    }

    /** Party A starts a new unsigned version. The previous version stays in place. */
    fun queueSigningRevision(dealId: String, terms: String): DealTransportResult {
        gate()
        if (localRole != PartyRole.PROPOSER) {
            throw DealFormatException("The event is not authorized for this party.")
        }
        return database.runInTransaction(Callable {
            val deal = openCurrent(dealId)
            val progress = recover(deal)
            val currentBytes = deal.canonicalBytes()
            val revised = try {
                SigningWorkflow.revise(
                    progress,
                    currentBytes,
                    DealIds.generate(),
                    terms,
                    clock(),
                )
            } finally {
                currentBytes.fill(0)
            }
            val canonical = revised.deal.canonicalBytes()
            try {
                val partyId = localPartyId(deal)
                val keys = loadKeys(partyId) ?: throw DealStateException("The local deal key is missing.")
                val payload = DealIdentity.termRevisionPayload(canonical)
                val signature = DealIdentityCrypto.sign(keys.signingHandle(), payload)
                DealIdentityCrypto.verify(keys.signingPublic, signature, payload)
                storeIfAbsent(revised.deal)
                saveProgress(revised)
                val envelope = message(
                    DealWireEvent.SIGNING_REVISION,
                    revised.deal.dealId,
                    revised.deal.versionId,
                    DealEnvelopes.revisionPayload(canonical, signature),
                )
                queue(envelope, "signing_revision_queued")
            } finally {
                canonical.fill(0)
            }
        })
    }

    /** Rejects the current unsigned or partly signed version. A proven version is refused. */
    fun queueSigningRejection(dealId: String, note: String): DealTransportResult {
        gate()
        return database.runInTransaction(Callable {
            val deal = openCurrent(dealId)
            val progress = recover(deal)
            val next = SigningWorkflow.reject(progress)
            saveProgress(next)
            val envelope = message(
                DealWireEvent.SIGNING_REJECTION,
                deal.dealId,
                deal.versionId,
                DealEnvelopes.notePayload(deal.documentHash(), note),
            )
            queue(envelope, "signing_reject_queued")
        })
    }

    /**
     * Phase 5.4D: records that this device's party read the complete agreement
     * at [versionId] / [documentHash]. Inside one transaction the persisted
     * current version is re-read and must match exactly; nothing is sent.
     * Returns the consent audit event id (idempotent per version/hash/party).
     */
    fun recordOwnReview(dealId: String, versionId: String, documentHash: ByteArray): String {
        gate()
        return database.runInTransaction(Callable {
            val row = dao.findDeal(dealId) ?: throw DealStateException("The proposal is missing.")
            if (row.currentVersionId != versionId) throw DealStateException("The agreement changed.")
            val stored = dao.findVersion(dealId, versionId) ?: throw DealStateException("The agreement changed.")
            val presented = Base64Url.encode(documentHash)
            if (stored.documentSha256 != presented) throw DealStateException("The agreement changed.")
            val deal = openCurrent(dealId)
            if (deal.versionId != versionId || !deal.documentHash().contentEquals(documentHash)) {
                throw DealStateException("The agreement changed.")
            }
            val partyId = localPartyId(deal)
            val outcome = DealReviewConsent.outcome(versionId, stored.documentSha256, partyId)
            dao.audits(dealId).firstOrNull { it.action == DealReviewConsent.ACTION && it.outcome == outcome }
                ?.let { return@Callable it.eventId }
            val eventId = DealIds.generate()
            dao.insertAudit(
                DealAuditRow(
                    eventId = eventId,
                    dealId = dealId,
                    action = DealReviewConsent.ACTION,
                    outcome = outcome,
                    createdAtEpochMs = clock(),
                ),
            )
            eventId
        })
    }

    /** Phase 5.4D: consent must exist for the persisted current version, its hash, and [partyId]. */
    private fun requireReviewConsent(deal: CanonicalDeal, partyId: String) {
        val row = dao.findDeal(deal.dealId) ?: throw DealReviewRequiredException()
        if (row.currentVersionId != deal.versionId) throw DealReviewRequiredException()
        val stored = dao.findVersion(deal.dealId, deal.versionId)?.documentSha256 ?: throw DealReviewRequiredException()
        if (stored != Base64Url.encode(deal.documentHash())) throw DealReviewRequiredException()
        val outcome = DealReviewConsent.outcome(deal.versionId, stored, partyId)
        if (dao.audits(deal.dealId).none { it.action == DealReviewConsent.ACTION && it.outcome == outcome }) {
            throw DealReviewRequiredException()
        }
    }

    /**
     * Phase 5.3N-B finality guard for a NEW local SIGN only. Runs inside the
     * caller's transaction and re-reads persisted rows, so nothing is signed,
     * stored, audited or queued once this party signed or the version is final.
     * The receive path (applySignature / submitSignature) is unchanged.
     */
    private fun requireLocalSignAllowed(deal: CanonicalDeal, progress: SigningProgress, partyId: String) {
        val row = dao.findDeal(deal.dealId) ?: throw DealStateException("The proposal is missing.")
        if (row.currentVersionId != deal.versionId) throw DealStateException("The agreement changed.")
        val persistedOwn = dao.signatures(deal.dealId, deal.versionId)
            .any { it.partyId == partyId && it.action == SignAction.SIGN.name }
        val progressOwn = progress.signatures.any { it.partyId == partyId && it.action == SignAction.SIGN }
        val final = row.status == DealStatus.FULLY_SIGNED.name ||
            progress.stage == SigningStage.FULLY_SIGNED ||
            progress.verdict().proven ||
            dao.findVersion(deal.dealId, deal.versionId)?.immutable == true
        if (persistedOwn || progressOwn || final) throw DealAlreadySignedException()
    }

    fun flush(deliver: (ByteArray) -> Unit) {
        gate()
        val queued = dao.transportByStatus(DealTransportState.OUT, DealTransportState.QUEUED)
        for (row in queued) {
            val plain = openSealed(row.sealedEnvelope)
            val frame = try {
                cipher.seal(session, plain)
            } finally {
                plain.fill(0)
            }
            try {
                deliver(frame)
            } finally {
                frame.fill(0)
            }
            dao.updateTransportStatus(row.envelopeId, DealTransportState.SENT, clock())
        }
    }

    /**
     * Copies each queued envelope for the messaging session. The caller encrypts
     * the copy before it leaves the device, then [markSent] after acceptance.
     * A failure before [markSent] leaves the row queued.
     */
    fun copyQueued(): List<Pair<String, ByteArray>> {
        gate()
        return dao.transportByStatus(DealTransportState.OUT, DealTransportState.QUEUED).map { row ->
            val plain = openSealed(row.sealedEnvelope)
            try {
                row.envelopeId to plain.copyOf()
            } finally {
                plain.fill(0)
            }
        }
    }

    fun markSent(envelopeId: String) {
        gate()
        val row = dao.findTransport(envelopeId) ?: return
        if (row.direction != DealTransportState.OUT || row.status != DealTransportState.QUEUED) return
        dao.updateTransportStatus(envelopeId, DealTransportState.SENT, clock())
    }

    /**
     * The outbound proposal for this draft, if one was already queued.
     * A retry uses that row instead of signing a second version.
     */
    fun proposalIdForDraft(
        draftId: String,
        local: UserIdentity?,
        conversation: Conversation?,
        contact: Contact?,
    ): String? {
        gate()
        if (local != null && !localMessagingSigner.publicKey.contentEquals(Base64Url.decode(local.publicIdentityKey))) {
            return null
        }
        val draft = dao.findDraft(draftId) ?: return null
        if (conversation != null && draft.conversationId != conversation.id) return null
        if (contact != null && draft.contactId != contact.id) return null
        return dao.transportForDeal(draft.dealId, DealTransportState.OUT, DealWireEvent.PROPOSAL.wire)
            .firstOrNull()
            ?.envelopeId
    }

    fun receive(frame: ByteArray): DealTransportResult {
        gate()
        val plain = cipher.open(session, frame)
        try {
            return acceptPlain(plain)
        } finally {
            plain.fill(0)
        }
    }

    /** Processes one already-authenticated plaintext envelope. Tests use this for substituted fields. */
    internal fun acceptPlain(plain: ByteArray): DealTransportResult {
        gate()
        val envelope = DealEnvelopes.parse(plain)
        val existing = dao.findTransport(envelope.envelopeId)
        if (existing != null && existing.status != DealTransportState.WAITING) {
            if (existing.status == DealTransportState.REJECTED) {
                throw DealFormatException("Deal envelope was rejected.")
            }
            return result(envelope, DealTransportState.DUPLICATE)
        }
        try {
            requirePeer(envelope)
        } catch (e: DealFormatException) {
            database.runInTransaction { record(envelope, DealTransportState.REJECTED, "envelope_rejected") }
            throw e
        }
        val outcome = database.runInTransaction(Callable {
            try {
                TransportOutcome.Done(dispatch(envelope))
            } catch (e: DealFormatException) {
                record(envelope, DealTransportState.REJECTED, "envelope_rejected")
                TransportOutcome.Failed(e.message ?: "Deal envelope was rejected.")
            } catch (e: DealCryptoException) {
                record(envelope, DealTransportState.REJECTED, "envelope_rejected")
                TransportOutcome.Failed(e.message ?: "Deal envelope was rejected.")
            }
        })
        return when (outcome) {
            is TransportOutcome.Failed -> throw DealFormatException(outcome.reason)
            is TransportOutcome.Done -> {
                promoteWaiting()
                result(envelope, outcome.status)
            }
        }
    }

    private fun queueNote(
        dealId: String,
        note: String,
        event: DealWireEvent,
        lifecycle: DealEvent,
        auditAction: String,
    ): DealTransportResult {
        gate()
        if (localRole != PartyRole.COUNTERPARTY) {
            throw DealFormatException("The event is not authorized for this party.")
        }
        return database.runInTransaction(Callable {
            val deal = dao.findDeal(dealId) ?: throw DealStateException("The proposal is missing.")
            val from = DealStatus.valueOf(deal.status)
            if (DealLifecycle.claimsFinality(from)) throw DealStateException("A finalized agreement cannot change.")
            val next = DealLifecycle.transition(from, lifecycle)
            val offerBytes = appliedOfferBytes(dealId, deal.currentVersionId)
                ?: throw DealStateException("The proposal is missing.")
            dao.updateDeal(dealId, next.name, deal.currentVersionId, clock())
            audit(dealId, auditAction, "ok")
            val envelope = message(
                event = event,
                dealId = dealId,
                versionId = deal.currentVersionId,
                payload = DealEnvelopes.notePayload(DealOffers.hash(offerBytes), note),
            )
            queue(envelope, auditAction)
        })
    }

    private fun dispatch(envelope: DealEnvelope): String = when (envelope.event) {
        DealWireEvent.PROPOSAL -> {
            requireRole(PartyRole.COUNTERPARTY)
            applyOffer(envelope, revised = false)
        }
        DealWireEvent.REVISED_PROPOSAL -> {
            requireRole(PartyRole.COUNTERPARTY)
            applyOffer(envelope, revised = true)
        }
        DealWireEvent.ACK -> {
            requireRole(PartyRole.PROPOSER)
            applyAck(envelope)
        }
        DealWireEvent.REQUEST_CHANGES -> {
            requireRole(PartyRole.PROPOSER)
            applyIncomingNote(envelope, DealEvent.NEGOTIATE, DealStatus.NEGOTIATING, "changes_requested")
        }
        DealWireEvent.REJECTION -> {
            requireRole(PartyRole.PROPOSER)
            applyIncomingNote(envelope, DealEvent.REJECT, DealStatus.REJECTED, "rejected")
        }
        DealWireEvent.DELIVERY -> applyDelivery(envelope)
        DealWireEvent.REVIEW -> applyReview(envelope)
        DealWireEvent.NAME_CONFIRMATION -> applyName(envelope)
        DealWireEvent.SIGNATURE -> applySignature(envelope)
        DealWireEvent.SIGNING_REJECTION -> applySigningRejection(envelope)
        DealWireEvent.SIGNING_REVISION -> applySigningRevision(envelope)
        DealWireEvent.CLOSURE -> applyClosure(envelope)
    }

    private fun applyOffer(envelope: DealEnvelope, revised: Boolean): String {
        val parsed = DealEnvelopes.parseProposal(envelope.payload)
        bindOffer(envelope, parsed)
        val offer = parsed.offer
        if (revised != (offer.parentVersionId != null)) {
            throw DealFormatException("The event is not authorized for this party.")
        }
        DealOffers.verify(offer.partyASigningPublic, parsed.signature, parsed.offerBytes)
        verifyPartyABinding(offer)
        val existing = dao.findVersion(offer.dealId, offer.versionId)
        if (existing != null) {
            val keys = loadKeys(offer.counterpartyPartyId) ?: return record(envelope, DealTransportState.CONFLICT, "version_conflict")
            val deal = assembleCounterparty(offer, keys)
            val hash = Base64Url.encode(deal.documentHash())
            val status = if (hash == existing.documentSha256) DealTransportState.DUPLICATE else DealTransportState.CONFLICT
            return record(envelope, status, if (status == DealTransportState.DUPLICATE) "duplicate" else "version_conflict")
        }
        when (gateVersion(offer)) {
            DealTransportState.STALE -> return record(envelope, DealTransportState.STALE, "stale_version")
            DealTransportState.CONFLICT -> return record(envelope, DealTransportState.CONFLICT, "version_conflict")
            DealTransportState.WAITING -> return record(envelope, DealTransportState.WAITING, "waiting")
        }
        val keys = loadOrCreate(offer.counterpartyPartyId)
        if (keys.signingPublic.contentEquals(offer.partyASigningPublic) ||
            keys.encryptionPublic.contentEquals(offer.partyAEncryptionPublic)
        ) {
            throw DealFormatException("Parties must have distinct public keys.")
        }
        val deal = assembleCounterparty(offer, keys)
        if (deal.canonicalVersion != DealFeature.CANONICAL_VERSION_V4 ||
            deal.terms != offer.terms ||
            deal.annex?.purpose != offer.annex.purpose ||
            deal.annex?.effectiveDate != offer.annex.effectiveDate
        ) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        storeVersion(deal, keys, DealKeyMaterial.parsePublicEncryption(offer.partyAPublicEncryptionKeyset))
        rememberProposal(deal)
        queueAck(offer, keys, parsed.offerBytes, envelope.envelopeId)
        return record(envelope, DealTransportState.APPLIED, "proposal_received")
    }

    private fun applyAck(envelope: DealEnvelope): String {
        val ack = DealEnvelopes.parseAck(envelope.payload)
        val stored = outboundOffer(envelope.dealId, envelope.versionId)
            ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (!DealOffers.hash(stored.offerBytes).contentEquals(ack.offerHash)) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val offer = stored.offer
        if (ack.signingPublic.contentEquals(ack.encryptionPublic) ||
            ack.signingPublic.contentEquals(offer.partyASigningPublic) ||
            ack.encryptionPublic.contentEquals(offer.partyAEncryptionPublic) ||
            ack.signingPublic.contentEquals(offer.partyB.messagingPublicKey) ||
            ack.encryptionPublic.contentEquals(offer.partyB.messagingPublicKey)
        ) {
            throw DealFormatException("Parties must have distinct public keys.")
        }
        val public = DealKeyMaterial.parsePublicEncryption(ack.publicEncryptionKeyset)
        if (!DealKeyMaterial.publicEncryptionRaw(public).contentEquals(ack.encryptionPublic)) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val bindingPayload = DealIdentity.bindingPayload(
            dealId = offer.dealId,
            role = PartyRole.COUNTERPARTY,
            participant = offer.partyB,
            dealSigningPublicKey = ack.signingPublic,
            dealEncryptionPublicKey = ack.encryptionPublic,
        )
        DealIdentityCrypto.verify(offer.partyB.messagingPublicKey, ack.binding, bindingPayload)
        val localKeys = loadKeys(offer.proposerPartyId) ?: throw DealStateException("The local deal key is missing.")
        val deal = assemble(
            offer = offer,
            counterpartySigning = ack.signingPublic,
            counterpartyEncryption = ack.encryptionPublic,
            counterpartyBinding = ack.binding,
        )
        val existing = dao.findVersion(deal.dealId, deal.versionId)
        val hash = Base64Url.encode(deal.documentHash())
        if (existing != null) {
            val status = if (existing.documentSha256 == hash) DealTransportState.DUPLICATE else DealTransportState.CONFLICT
            return record(envelope, status, if (status == DealTransportState.DUPLICATE) "duplicate" else "version_conflict")
        }
        when (gateVersion(offer)) {
            DealTransportState.STALE -> return record(envelope, DealTransportState.STALE, "stale_version")
            DealTransportState.CONFLICT -> return record(envelope, DealTransportState.CONFLICT, "version_conflict")
            DealTransportState.WAITING -> return record(envelope, DealTransportState.WAITING, "waiting")
        }
        storeVersion(deal, localKeys, public)
        rememberProposal(deal)
        advance(stored.envelopeId, DealTransportState.ACKNOWLEDGED)
        return record(envelope, DealTransportState.APPLIED, "acknowledged")
    }

    private fun applyIncomingNote(
        envelope: DealEnvelope,
        event: DealEvent,
        target: DealStatus,
        action: String,
    ): String {
        val note = DealEnvelopes.parseNote(envelope.payload)
        if (note.note.isEmpty()) throw DealFormatException("Deal envelope was rejected.")
        val deal = dao.findDeal(envelope.dealId) ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (deal.currentVersionId != envelope.versionId) {
            return record(envelope, DealTransportState.STALE, "stale_version")
        }
        val offerBytes = appliedOfferBytes(envelope.dealId, envelope.versionId)
            ?: outboundOffer(envelope.dealId, envelope.versionId)?.offerBytes
            ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (!DealOffers.hash(offerBytes).contentEquals(note.offerHash)) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val from = DealStatus.valueOf(deal.status)
        if (from == target) return record(envelope, DealTransportState.DUPLICATE, "duplicate")
        if (DealLifecycle.claimsFinality(from)) throw DealStateException("A finalized agreement cannot change.")
        val next = DealLifecycle.transition(from, event)
        dao.updateDeal(deal.dealId, next.name, deal.currentVersionId, clock())
        return record(envelope, DealTransportState.APPLIED, action)
    }

    private fun applyDelivery(envelope: DealEnvelope): String {
        val delivery = DealEnvelopes.parseDelivery(envelope.payload)
        val referenced = dao.findTransport(delivery.referencedEnvelopeId)
            ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (referenced.direction == DealTransportState.OUT && referenced.dealId == envelope.dealId) {
            val incoming = when (delivery.stateCode) {
                1 -> DealTransportState.QUEUED
                2 -> DealTransportState.SENT
                3 -> DealTransportState.DELIVERED
                else -> DealTransportState.ACKNOWLEDGED
            }
            advance(referenced.envelopeId, incoming)
        }
        return record(envelope, DealTransportState.APPLIED, "delivery")
    }

    private fun queueAck(offer: DealOffer, keys: DealKeyMaterial, offerBytes: ByteArray, referencedId: String) {
        val payload = DealEnvelopes.ackPayload(
            offerHash = DealOffers.hash(offerBytes),
            signingPublic = keys.signingPublic,
            encryptionPublic = keys.encryptionPublic,
            binding = bindingFor(offer, keys),
            publicEncryptionKeyset = keys.serializedPublicEncryption(),
        )
        queue(
            message(DealWireEvent.ACK, offer.dealId, offer.versionId, payload),
            "ack_queued",
        )
        queue(
            message(
                DealWireEvent.DELIVERY,
                offer.dealId,
                offer.versionId,
                DealEnvelopes.deliveryPayload(referencedId, 3),
            ),
            "delivery_queued",
        )
    }

    private fun bindingFor(offer: DealOffer, keys: DealKeyMaterial): ByteArray {
        val payload = DealIdentity.bindingPayload(
            dealId = offer.dealId,
            role = PartyRole.COUNTERPARTY,
            participant = offer.partyB,
            dealSigningPublicKey = keys.signingPublic,
            dealEncryptionPublicKey = keys.encryptionPublic,
        )
        val signature = localMessagingSigner.sign(payload)
        DealIdentityCrypto.verify(session.localMessagingPublic, signature, payload)
        return signature
    }

    private fun assembleCounterparty(offer: DealOffer, keys: DealKeyMaterial): CanonicalDeal =
        assemble(offer, keys.signingPublic, keys.encryptionPublic, bindingFor(offer, keys))

    private fun assemble(
        offer: DealOffer,
        counterpartySigning: ByteArray,
        counterpartyEncryption: ByteArray,
        counterpartyBinding: ByteArray,
    ): CanonicalDeal = DealIdentity.assemble(
        dealId = offer.dealId,
        versionId = offer.versionId,
        parentVersionId = offer.parentVersionId,
        createdAtEpochMs = offer.createdAtEpochMs,
        currency = offer.currency,
        amountMinor = offer.amountMinor,
        terms = offer.terms,
        details = offer.details,
        ancestry = offer.ancestry,
        annex = offer.annex,
        drafts = listOf(
            DealPartyDraft(
                partyId = offer.proposerPartyId,
                role = PartyRole.PROPOSER,
                label = offer.proposerLabel,
                participant = offer.partyA,
                signingPublicKey = offer.partyASigningPublic,
                encryptionPublicKey = offer.partyAEncryptionPublic,
                bindingSignature = offer.partyABinding,
            ),
            DealPartyDraft(
                partyId = offer.counterpartyPartyId,
                role = PartyRole.COUNTERPARTY,
                label = offer.counterpartyLabel,
                participant = offer.partyB,
                signingPublicKey = counterpartySigning,
                encryptionPublicKey = counterpartyEncryption,
                bindingSignature = counterpartyBinding,
            ),
        ),
    )

    private fun storeVersion(deal: CanonicalDeal, localKeys: DealKeyMaterial, peerPublic: KeysetHandle) {
        val localParty = deal.parties.first { it.role == localRole }
        val peerParty = deal.parties.first { it.role != localRole }
        val plain = deal.canonicalBytes()
        val sealed = try {
            DealCipher.encrypt(
                deal.dealId,
                deal.versionId,
                plain,
                listOf(
                    PartyWrapTarget(localParty.partyId, localKeys.encryptionPublicHandle()),
                    PartyWrapTarget(peerParty.partyId, peerPublic),
                ),
            )
        } finally {
            plain.fill(0)
        }
        val opened = DealCipher.decrypt(sealed, localParty.partyId, localKeys.encryptionHandle())
        try {
            if (!opened.contentEquals(deal.canonicalBytes())) throw DealFormatException("Deal envelope was rejected.")
        } finally {
            opened.fill(0)
        }
        val now = clock()
        val hash = Base64Url.encode(deal.documentHash())
        val existing = dao.findDeal(deal.dealId)
        val status = if (existing == null) {
            DealStatus.PROPOSED
        } else {
            val from = DealStatus.valueOf(existing.status)
            if (!DealLifecycle.allowsRevision(from)) throw DealStateException("This deal can no longer be revised.")
            DealLifecycle.transition(from, DealEvent.SUPERSEDE)
        }
        if (existing == null) {
            dao.insertDeal(
                DealRow(
                    dealId = deal.dealId,
                    status = status.name,
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
        } else {
            if (deal.parentVersionId != null) dao.supersede(deal.dealId, deal.parentVersionId)
            dao.updateDeal(deal.dealId, status.name, deal.versionId, now)
        }
        dao.insertVersion(
            DealVersionRow(
                dealId = deal.dealId,
                versionId = deal.versionId,
                parentVersionId = deal.parentVersionId,
                documentSha256 = hash,
                sealedDocument = sealed.encode(),
                superseded = false,
                immutable = false,
                createdAtEpochMs = deal.createdAtEpochMs,
            ),
        )
        saveKeys(localParty.partyId, localKeys)
    }

    private fun gateVersion(offer: DealOffer): String {
        val deal = dao.findDeal(offer.dealId)
        val parent = offer.parentVersionId
        if (parent == null) return if (deal == null) DealTransportState.APPLIED else DealTransportState.STALE
        if (deal == null || dao.findVersion(offer.dealId, parent) == null) return DealTransportState.WAITING
        if (deal.currentVersionId == parent) return DealTransportState.APPLIED
        if (isAncestor(offer.dealId, offer.versionId, deal.currentVersionId)) return DealTransportState.STALE
        val current = dao.findVersion(offer.dealId, deal.currentVersionId)
        if (current?.parentVersionId == parent) return DealTransportState.CONFLICT
        if (isAncestor(offer.dealId, parent, deal.currentVersionId)) return DealTransportState.STALE
        return DealTransportState.CONFLICT
    }

    private fun isAncestor(dealId: String, older: String, newer: String): Boolean {
        var cursor = newer
        repeat(64) {
            val row = dao.findVersion(dealId, cursor) ?: return false
            val parent = row.parentVersionId ?: return false
            if (parent == older) return true
            cursor = parent
        }
        return false
    }

    private fun signOffer(offer: DealOffer, signingKey: KeysetHandle, event: DealWireEvent): DealEnvelope {
        val offerBytes = DealOffers.encode(offer)
        try {
            val signature = DealOffers.sign(signingKey, offerBytes)
            DealOffers.verify(offer.partyASigningPublic, signature, offerBytes)
            return message(event, offer.dealId, offer.versionId, DealEnvelopes.proposalPayload(offerBytes, signature))
        } finally {
            offerBytes.fill(0)
        }
    }

    private fun applyReview(envelope: DealEnvelope): String {
        val current = findCurrent(envelope.dealId) ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (current.versionId != envelope.versionId) return staleOrWaiting(envelope)
        val parsed = DealEnvelopes.parseReview(envelope.payload)
        if (!parsed.documentHash.contentEquals(current.documentHash())) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val counterparty = current.parties.first { it.role == PartyRole.COUNTERPARTY }
        if (!envelope.senderKey.contentEquals(current.identity!!.pin(counterparty.partyId).participant.messagingPublicKey)) {
            throw DealFormatException("The sender is not the authenticated contact.")
        }
        val next = try {
            SigningWorkflow.adoptPartyBReview(recover(current), parsed.signature)
        } catch (e: DealException) {
            return refuseSigning(envelope, e)
        }
        saveProgress(next)
        return record(envelope, DealTransportState.APPLIED, "signing_review")
    }

    private fun applyName(envelope: DealEnvelope): String {
        val parsed = DealEnvelopes.parseName(envelope.payload)
        val successorBytes = parsed.successor
        if (successorBytes == null) {
            val current = findCurrent(envelope.dealId) ?: return record(envelope, DealTransportState.WAITING, "waiting")
            if (current.versionId != envelope.versionId) return staleOrWaiting(envelope)
            requireSenderIsParty(current, envelope, parsed.partyId)
            val next = try {
                var progress = recover(current)
                val attached = parsed.reviewSignature
                if (attached != null) progress = SigningWorkflow.adoptPartyBReview(progress, attached)
                SigningWorkflow.submitNameConfirmation(progress, parsed.partyId, parsed.signature)
            } catch (e: DealException) {
                return refuseSigning(envelope, e)
            }
            saveProgress(next)
            return record(envelope, DealTransportState.APPLIED, "signing_name")
        }
        val successor = CanonicalEncoding.parse(successorBytes)
        if (successor.dealId != envelope.dealId || successor.versionId != envelope.versionId) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val parentId = successor.parentVersionId ?: throw DealFormatException("Deal envelope was rejected.")
        val row = dao.findDeal(envelope.dealId) ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (dao.findVersion(envelope.dealId, parentId) == null) {
            return record(envelope, DealTransportState.WAITING, "waiting")
        }
        if (row.currentVersionId != parentId && row.currentVersionId != successor.versionId) {
            return record(envelope, DealTransportState.STALE, "stale_version")
        }
        val base = if (row.currentVersionId == successor.versionId) {
            openVersion(envelope.dealId, parentId)
        } else {
            openVersion(envelope.dealId, parentId)
        }
        requireSenderIsParty(base, envelope, parsed.partyId)
        val existing = dao.findVersion(successor.dealId, successor.versionId)
        if (existing != null && existing.documentSha256 != Base64Url.encode(successor.documentHash())) {
            return record(envelope, DealTransportState.CONFLICT, "version_conflict")
        }
        val reviewed = try {
            val parentProgress = recover(base)
            val attached = parsed.reviewSignature
            if (attached != null) SigningWorkflow.adoptPartyBReview(parentProgress, attached) else parentProgress
        } catch (e: DealException) {
            return refuseSigning(envelope, e)
        }
        val next = try {
            SigningWorkflow.adoptNameSuccessor(reviewed, successor, parsed.partyId, parsed.signature)
        } catch (e: DealException) {
            return refuseSigning(envelope, e)
        }
        if (existing == null) storeIfAbsent(next.deal)
        saveProgress(next)
        return record(
            envelope,
            if (existing == null) DealTransportState.APPLIED else DealTransportState.DUPLICATE,
            "signing_name",
        )
    }

    private fun applySignature(envelope: DealEnvelope): String {
        val current = findCurrent(envelope.dealId) ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (current.versionId != envelope.versionId) return staleOrWaiting(envelope)
        val parsed = DealEnvelopes.parseSignature(envelope.payload)
        requireSenderIsParty(current, envelope, parsed.partyId)
        val claimed = DealSignature(
            dealId = current.dealId,
            versionId = current.versionId,
            partyId = parsed.partyId,
            action = SignAction.SIGN,
            signature = parsed.signature,
            signerPublicKey = parsed.signerPublicKey,
        )
        val next = try {
            SigningWorkflow.submitSignature(recover(current), claimed)
        } catch (e: DealException) {
            return refuseSigning(envelope, e)
        }
        persistSignatures(next)
        saveProgress(next)
        return record(envelope, DealTransportState.APPLIED, "signing_sign")
    }

    private fun applyClosure(envelope: DealEnvelope): String {
        val current = findCurrent(envelope.dealId) ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (current.versionId != envelope.versionId) return staleOrWaiting(envelope)
        val parsed = DealEnvelopes.parseClosure(envelope.payload)
        requireSenderIsParty(current, envelope, parsed.partyId)
        val confirmation = ClosureConfirmation(
            dealId = envelope.dealId,
            versionId = envelope.versionId,
            partyId = parsed.partyId,
            kind = parsed.kind,
            signature = parsed.signature,
            signerPublicKey = parsed.signerPublicKey,
        )
        val admitted = DealClosureStore(database, clock).admit(current, confirmation)
        return when (admitted.admit) {
            ClosureAdmit.ACCEPTED, ClosureAdmit.CLOSED ->
                record(envelope, DealTransportState.APPLIED, "closure_received")
            ClosureAdmit.DUPLICATE -> record(envelope, DealTransportState.DUPLICATE, "closure_duplicate")
            ClosureAdmit.REJECTED -> record(envelope, DealTransportState.REJECTED, "closure_rejected")
        }
    }

    private fun applySigningRejection(envelope: DealEnvelope): String {
        val current = findCurrent(envelope.dealId) ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (current.versionId != envelope.versionId) return staleOrWaiting(envelope)
        val note = DealEnvelopes.parseNote(envelope.payload)
        if (!note.offerHash.contentEquals(current.documentHash())) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val identity = current.identity ?: throw DealFormatException("This document has no identity binding.")
        if (identity.pins.none { it.participant.messagingPublicKey.contentEquals(envelope.senderKey) }) {
            throw DealFormatException("The sender is not the authenticated contact.")
        }
        val next = try {
            SigningWorkflow.reject(recover(current))
        } catch (e: DealException) {
            return refuseSigning(envelope, e)
        }
        saveProgress(next)
        return record(envelope, DealTransportState.APPLIED, "signing_reject")
    }

    private fun applySigningRevision(envelope: DealEnvelope): String {
        if (localRole != PartyRole.COUNTERPARTY) {
            throw DealFormatException("The event is not authorized for this party.")
        }
        val parsed = DealEnvelopes.parseRevision(envelope.payload)
        val successor = CanonicalEncoding.parse(parsed.canonicalBytes)
        if (successor.dealId != envelope.dealId || successor.versionId != envelope.versionId) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        val parentId = successor.parentVersionId ?: throw DealFormatException("Deal envelope was rejected.")
        val row = dao.findDeal(envelope.dealId) ?: return record(envelope, DealTransportState.WAITING, "waiting")
        if (row.status == DealStatus.CLOSED.name) {
            return record(envelope, DealTransportState.REJECTED, "signing_rejected")
        }
        if (dao.findVersion(envelope.dealId, parentId) == null) {
            return record(envelope, DealTransportState.WAITING, "waiting")
        }
        if (row.currentVersionId != parentId && row.currentVersionId != successor.versionId) {
            return record(envelope, DealTransportState.STALE, "stale_version")
        }
        val parent = openVersion(envelope.dealId, parentId)
        val proposer = parent.parties.first { it.role == PartyRole.PROPOSER }
        if (!envelope.senderKey.contentEquals(parent.identity!!.pin(proposer.partyId).participant.messagingPublicKey)) {
            throw DealFormatException("The sender is not the authenticated contact.")
        }
        DealIdentityCrypto.verify(
            proposer.signingPublicKey,
            parsed.signature,
            DealIdentity.termRevisionPayload(parsed.canonicalBytes),
        )
        SigningWorkflow.requireTermRevision(parent, successor)
        val existing = dao.findVersion(successor.dealId, successor.versionId)
        if (existing != null && existing.documentSha256 != Base64Url.encode(successor.documentHash())) {
            return record(envelope, DealTransportState.CONFLICT, "version_conflict")
        }
        if (existing == null) storeIfAbsent(successor)
        rememberProposal(if (existing == null) successor else openVersion(successor.dealId, successor.versionId))
        return record(
            envelope,
            if (existing == null) DealTransportState.APPLIED else DealTransportState.DUPLICATE,
            "signing_revision",
        )
    }

    private fun rememberProposal(deal: CanonicalDeal) {
        saveProgress(SigningWorkflow.sendProposal(SigningWorkflow.start(deal)))
    }

    private fun recover(deal: CanonicalDeal): SigningProgress = SigningProgressStore(dao).recover(deal)

    private fun saveProgress(progress: SigningProgress) {
        SigningProgressStore(dao).save(progress, DealIds.generate(), clock())
    }

    private fun persistSignatures(progress: SigningProgress) {
        for (signature in progress.signatures) {
            if (signature.action != SignAction.SIGN) continue
            val existing = dao.signatures(signature.dealId, signature.versionId).firstOrNull {
                it.partyId == signature.partyId && it.action == SignAction.SIGN.name
            }
            if (existing == null) {
                dao.insertSignature(
                    DealSignatureRow(
                        dealId = signature.dealId,
                        versionId = signature.versionId,
                        partyId = signature.partyId,
                        action = SignAction.SIGN.name,
                        signature = signature.signature.copyOf(),
                        signerPublicKey = Base64Url.encode(signature.signerPublicKey),
                    ),
                )
            } else if (!existing.signature.contentEquals(signature.signature)) {
                throw DealConflictException()
            }
        }
        if (progress.stage == SigningStage.FULLY_SIGNED) {
            dao.markVersionImmutable(progress.deal.dealId, progress.deal.versionId)
        }
    }

    private fun storeIfAbsent(deal: CanonicalDeal) {
        val existing = dao.findVersion(deal.dealId, deal.versionId)
        if (existing != null) {
            if (existing.documentSha256 != Base64Url.encode(deal.documentHash())) throw DealConflictException()
            return
        }
        val localParty = deal.parties.first { it.role == localRole }
        val keys = loadKeys(localParty.partyId) ?: throw DealStateException("The local deal key is missing.")
        storeVersion(deal, keys, peerEncryption(deal.dealId))
    }

    private fun peerEncryption(dealId: String): com.google.crypto.tink.KeysetHandle {
        if (localRole == PartyRole.COUNTERPARTY) {
            val row = appliedTransport(dealId, DealWireEvent.PROPOSAL)
                ?: appliedTransport(dealId, DealWireEvent.REVISED_PROPOSAL)
                ?: throw DealStateException("The proposal is missing.")
            val plain = openSealed(row.sealedEnvelope)
            try {
                val offer = DealEnvelopes.parseProposal(DealEnvelopes.parse(plain).payload).offer
                return DealKeyMaterial.parsePublicEncryption(offer.partyAPublicEncryptionKeyset)
            } finally {
                plain.fill(0)
            }
        }
        val row = appliedTransport(dealId, DealWireEvent.ACK) ?: throw DealStateException("The proposal is missing.")
        val plain = openSealed(row.sealedEnvelope)
        try {
            val ack = DealEnvelopes.parseAck(DealEnvelopes.parse(plain).payload)
            return DealKeyMaterial.parsePublicEncryption(ack.publicEncryptionKeyset)
        } finally {
            plain.fill(0)
        }
    }

    private fun appliedTransport(dealId: String, event: DealWireEvent): DealTransportRow? =
        dao.transportForDeal(dealId, DealTransportState.IN, event.wire)
            .firstOrNull { it.status == DealTransportState.APPLIED }

    private fun findCurrent(dealId: String): CanonicalDeal? {
        val row = dao.findDeal(dealId) ?: return null
        if (dao.findVersion(dealId, row.currentVersionId) == null) return null
        return openVersion(dealId, row.currentVersionId)
    }

    private fun openCurrent(dealId: String): CanonicalDeal =
        findCurrent(dealId) ?: throw DealStateException("The proposal is missing.")

    private fun openVersion(dealId: String, versionId: String): CanonicalDeal {
        val version = dao.findVersion(dealId, versionId) ?: throw DealStateException("The proposal is missing.")
        val localParty = dao.parties(dealId).firstOrNull { it.role == localRole.name }
            ?: throw DealStateException("The local deal key is missing.")
        val keys = loadKeys(localParty.partyId) ?: throw DealStateException("The local deal key is missing.")
        val plain = DealCipher.decrypt(
            SealedDocument.parse(version.sealedDocument),
            localParty.partyId,
            keys.encryptionHandle(),
        )
        try {
            return CanonicalEncoding.parse(plain)
        } finally {
            plain.fill(0)
        }
    }

    private fun localPartyId(deal: CanonicalDeal): String {
        val pin = deal.identity?.pins?.firstOrNull {
            it.participant.messagingPublicKey.contentEquals(localMessagingSigner.publicKey)
        } ?: throw DealStateException("The local deal key is missing.")
        if (pin.role != localRole) throw DealFormatException("The event is not authorized for this party.")
        return pin.partyId
    }

    private fun requireSenderIsParty(deal: CanonicalDeal, envelope: DealEnvelope, partyId: String) {
        val pin = deal.identity?.pin(partyId) ?: throw DealFormatException("Deal envelope was rejected.")
        if (!pin.participant.messagingPublicKey.contentEquals(envelope.senderKey)) {
            throw DealFormatException("The sender is not the authenticated contact.")
        }
    }

    private fun staleOrWaiting(envelope: DealEnvelope): String {
        val version = dao.findVersion(envelope.dealId, envelope.versionId)
        val status = if (version == null) DealTransportState.WAITING else DealTransportState.STALE
        return record(envelope, status, if (version == null) "waiting" else "stale_version")
    }

    private fun refuseSigning(envelope: DealEnvelope, error: DealException): String {
        if (error is DealCryptoException || error is DealFormatException) throw error
        if (error is DealStateException ||
            error is ImmutableVersionException ||
            error is DealConflictException ||
            error is com.codeyantra.armorchat.deal.domain.ForeignPartyException
        ) {
            return record(envelope, DealTransportState.REJECTED, "signing_rejected")
        }
        throw error
    }

    private fun message(event: DealWireEvent, dealId: String, versionId: String, payload: ByteArray): DealEnvelope =
        DealEnvelope(
            event = event,
            envelopeId = DealIds.generate(),
            dealId = dealId,
            versionId = versionId,
            senderId = session.localArmorChatId,
            recipientId = session.peerArmorChatId,
            senderKey = session.localMessagingPublic.copyOf(),
            recipientKey = session.peerMessagingPublic.copyOf(),
            conversationId = session.conversationId,
            createdAtEpochMs = clock(),
            payload = payload,
        )

    private fun queue(envelope: DealEnvelope, action: String): DealTransportResult {
        val encoded = DealEnvelopes.encode(envelope)
        try {
            dao.insertTransport(
                DealTransportRow(
                    envelopeId = envelope.envelopeId,
                    dealId = envelope.dealId,
                    versionId = envelope.versionId,
                    conversationId = envelope.conversationId,
                    direction = DealTransportState.OUT,
                    eventType = envelope.event.wire,
                    status = DealTransportState.QUEUED,
                    sealedEnvelope = protector.seal(encoded, PURPOSE),
                    createdAtEpochMs = envelope.createdAtEpochMs,
                    updatedAtEpochMs = clock(),
                ),
            )
        } finally {
            encoded.fill(0)
        }
        audit(envelope.dealId, action, "ok")
        return result(envelope, DealTransportState.QUEUED)
    }

    private fun record(envelope: DealEnvelope, status: String, action: String): String {
        val existing = dao.findTransport(envelope.envelopeId)
        if (existing == null) {
            val encoded = DealEnvelopes.encode(envelope)
            try {
                dao.insertTransport(
                    DealTransportRow(
                        envelopeId = envelope.envelopeId,
                        dealId = envelope.dealId,
                        versionId = envelope.versionId,
                        conversationId = envelope.conversationId,
                        direction = DealTransportState.IN,
                        eventType = envelope.event.wire,
                        status = status,
                        sealedEnvelope = protector.seal(encoded, PURPOSE),
                        createdAtEpochMs = envelope.createdAtEpochMs,
                        updatedAtEpochMs = clock(),
                    ),
                )
            } finally {
                encoded.fill(0)
            }
            audit(envelope.dealId, action, "ok")
        } else if (existing.status != status) {
            dao.updateTransportStatus(envelope.envelopeId, status, clock())
            audit(envelope.dealId, action, "ok")
        }
        return status
    }

    private fun advance(envelopeId: String, status: String) {
        val row = dao.findTransport(envelopeId) ?: return
        if (DealTransportState.rank(status) > DealTransportState.rank(row.status)) {
            dao.updateTransportStatus(envelopeId, status, clock())
        }
    }

    private fun promoteWaiting() {
        repeat(4) {
            var progressed = false
            val waiting = dao.transportByStatus(DealTransportState.IN, DealTransportState.WAITING)
            for (row in waiting) {
                val plain = openSealed(row.sealedEnvelope)
                try {
                    val envelope = DealEnvelopes.parse(plain)
                    val status = database.runInTransaction(Callable { dispatch(envelope) })
                    if (status != DealTransportState.WAITING) progressed = true
                } finally {
                    plain.fill(0)
                }
            }
            if (!progressed) return
        }
    }

    private fun latestOutboundOffer(dealId: String): StoredOffer? {
        val rows = dao.transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.REVISED_PROPOSAL.wire) +
            dao.transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.PROPOSAL.wire)
        return rows.maxByOrNull { it.createdAtEpochMs }?.let { outboundFrom(it) }
    }

    private fun outboundOffer(dealId: String, versionId: String): StoredOffer? {
        val rows = dao.transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.PROPOSAL.wire) +
            dao.transportForDeal(dealId, DealTransportState.OUT, DealWireEvent.REVISED_PROPOSAL.wire)
        return rows.firstOrNull { it.versionId == versionId }?.let { outboundFrom(it) }
    }

    private fun outboundFrom(row: DealTransportRow): StoredOffer {
        val plain = openSealed(row.sealedEnvelope)
        try {
            val envelope = DealEnvelopes.parse(plain)
            val parsed = DealEnvelopes.parseProposal(envelope.payload)
            return StoredOffer(row.envelopeId, parsed.offer, parsed.offerBytes.copyOf())
        } finally {
            plain.fill(0)
        }
    }

    private fun appliedOfferBytes(dealId: String, versionId: String): ByteArray? {
        val rows = dao.transportForDeal(dealId, DealTransportState.IN, DealWireEvent.PROPOSAL.wire) +
            dao.transportForDeal(dealId, DealTransportState.IN, DealWireEvent.REVISED_PROPOSAL.wire)
        val row = rows.firstOrNull { it.versionId == versionId && it.status == DealTransportState.APPLIED } ?: return null
        val plain = openSealed(row.sealedEnvelope)
        try {
            val envelope = DealEnvelopes.parse(plain)
            return DealEnvelopes.parseProposal(envelope.payload).offerBytes.copyOf()
        } finally {
            plain.fill(0)
        }
    }

    private fun loadOrCreate(partyId: String): DealKeyMaterial {
        loadKeys(partyId)?.let { return it }
        val created = DealKeyMaterial.generate()
        saveKeys(partyId, created)
        return created
    }

    private fun loadKeys(partyId: String): DealKeyMaterial? {
        val row = dao.findKeys(partyId) ?: return null
        val signing = protector.open(row.sealedSigningKeyset, DealRepository.SIGNING_PURPOSE)
        val encryption = protector.open(row.sealedEncryptionKeyset, DealRepository.ENCRYPTION_PURPOSE)
        try {
            return DealKeyMaterial.fromSerialized(signing, encryption)
        } finally {
            signing.fill(0)
            encryption.fill(0)
        }
    }

    private fun saveKeys(partyId: String, keys: DealKeyMaterial) {
        if (dao.findKeys(partyId) != null) return
        val signing = keys.serializedSigning()
        val encryption = keys.serializedEncryption()
        try {
            dao.insertKeys(
                DealLocalKeyRow(
                    partyId = partyId,
                    sealedSigningKeyset = protector.seal(signing, DealRepository.SIGNING_PURPOSE),
                    sealedEncryptionKeyset = protector.seal(encryption, DealRepository.ENCRYPTION_PURPOSE),
                    signingPublicKey = Base64Url.encode(keys.signingPublic),
                    encryptionPublicKey = Base64Url.encode(keys.encryptionPublic),
                ),
            )
        } finally {
            signing.fill(0)
            encryption.fill(0)
        }
    }

    private fun bindDraft(sendable: SendableDraft) {
        val origin = sendable.opened.origin
        if (origin.conversationId != session.conversationId ||
            origin.partyA.armorChatId != session.localArmorChatId ||
            !origin.partyA.messagingPublicKey.contentEquals(session.localMessagingPublic) ||
            origin.partyB.armorChatId != session.peerArmorChatId ||
            !origin.partyB.messagingPublicKey.contentEquals(session.peerMessagingPublic)
        ) {
            throw DealFormatException("The ArmorChat identity changed before the deal was sent.")
        }
    }

    private fun bindOffer(envelope: DealEnvelope, parsed: ParsedProposal) {
        val offer = parsed.offer
        if (offer.dealId != envelope.dealId || offer.versionId != envelope.versionId) {
            throw DealFormatException("Deal envelope was rejected.")
        }
        if (offer.conversationId != envelope.conversationId || offer.conversationId != session.conversationId) {
            throw DealFormatException("The deal is bound to a different conversation.")
        }
        if (offer.partyA.armorChatId != envelope.senderId ||
            !offer.partyA.messagingPublicKey.contentEquals(envelope.senderKey)
        ) {
            throw DealFormatException("The sender is not the authenticated contact.")
        }
        if (offer.partyB.armorChatId != envelope.recipientId ||
            !offer.partyB.messagingPublicKey.contentEquals(envelope.recipientKey)
        ) {
            throw DealFormatException("The recipient is not this device.")
        }
    }

    private fun verifyPartyABinding(offer: DealOffer) {
        val payload = DealIdentity.bindingPayload(
            dealId = offer.dealId,
            role = PartyRole.PROPOSER,
            participant = offer.partyA,
            dealSigningPublicKey = offer.partyASigningPublic,
            dealEncryptionPublicKey = offer.partyAEncryptionPublic,
        )
        DealIdentityCrypto.verify(offer.partyA.messagingPublicKey, offer.partyABinding, payload)
    }

    private fun requirePeer(envelope: DealEnvelope) {
        if (envelope.conversationId != session.conversationId) {
            throw DealFormatException("The deal is bound to a different conversation.")
        }
        if (envelope.senderId != session.peerArmorChatId ||
            !envelope.senderKey.contentEquals(session.peerMessagingPublic)
        ) {
            throw DealFormatException("The sender is not the authenticated contact.")
        }
        if (envelope.recipientId != session.localArmorChatId ||
            !envelope.recipientKey.contentEquals(session.localMessagingPublic)
        ) {
            throw DealFormatException("The recipient is not this device.")
        }
    }

    private fun requireRole(role: PartyRole) {
        if (localRole != role) throw DealFormatException("The event is not authorized for this party.")
    }

    private fun audit(dealId: String, action: String, outcome: String) {
        dao.insertAudit(
            DealAuditRow(
                eventId = DealIds.generate(),
                dealId = dealId,
                action = action,
                outcome = outcome,
                createdAtEpochMs = clock(),
            ),
        )
    }

    private fun result(envelope: DealEnvelope, status: String) = DealTransportResult(
        envelopeId = envelope.envelopeId,
        dealId = envelope.dealId,
        versionId = envelope.versionId,
        event = envelope.event.wire,
        status = status,
        acceptedAgreement = false,
        finalized = false,
    )

    private fun openSealed(bytes: ByteArray): ByteArray = protector.open(bytes, PURPOSE)

    private fun gate() {
        if (!debugBuild || !ChatDealFactory.enabled(debugBuild, productionUiEnabled)) {
            throw DealStateException("Deal creation from chat is disabled.")
        }
    }

    private class StoredOffer(
        val envelopeId: String,
        val offer: DealOffer,
        val offerBytes: ByteArray,
    )

    private sealed class TransportOutcome {
        class Done(val status: String) : TransportOutcome()
        class Failed(val reason: String) : TransportOutcome()
    }

    companion object {
        const val PURPOSE = "armorchat-deal-transport-v1"
    }
}
