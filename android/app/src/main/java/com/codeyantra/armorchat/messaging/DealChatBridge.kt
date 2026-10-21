package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.deal.crypto.DealKeyProtector
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealMessagingSigner
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.transport.DealDocumentResult
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.transport.DealSigningLookup
import com.codeyantra.armorchat.deal.transport.DealSigningSnapshot
import com.codeyantra.armorchat.deal.transport.DealTransportResult
import com.codeyantra.armorchat.deal.transport.DealTransportState
import com.codeyantra.armorchat.deal.draft.DealDraftStore
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.transport.DealEnvelopes
import com.codeyantra.armorchat.deal.transport.DealFrameChunk
import com.codeyantra.armorchat.deal.transport.DealFrameInbox
import com.codeyantra.armorchat.deal.transport.DealMessagingSession
import com.codeyantra.armorchat.deal.transport.DealTransportService
import com.codeyantra.armorchat.deal.transport.DealWireEvent
import com.codeyantra.armorchat.deal.transport.ExportedSessionCipher
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.UserIdentity

/**
 * Moves Armor Deal envelopes across one authenticated messaging session.
 *
 * Party A's outgoing drafts use this phone's chat id. Party B pins the
 * conversation id carried in the first authenticated proposal, because each
 * phone has its own Room conversation id. A later envelope with a different
 * id is rejected. Chat rows are not written here.
 */
class DealChatBridge(
    private val database: DealDatabase,
    private val protector: DealKeyProtector,
    private val drafts: DealDraftStore,
    private val cipher: ExportedSessionCipher,
    private val signer: DealMessagingSigner,
    private val local: VerifiedDealParticipant,
    private val peerParticipant: VerifiedDealParticipant,
    private val peer: ReceiptMessagingPeer,
    private val debugBuild: Boolean,
    private val clock: () -> Long,
    localConversationId: String,
) {
    private val proposerSession = DealMessagingSession.establish(localConversationId, local, peerParticipant)
    private val proposer = service(proposerSession, PartyRole.PROPOSER)
    private val inbox = DealFrameInbox(database, protector, localConversationId, clock)
    private var counterpartySession: DealMessagingSession? = null
    private var counterparty: DealTransportService? = null

    suspend fun recover() {
        for (frame in inbox.recover()) dispatch(frame)
    }

    fun existingProposalId(
        draftId: String,
        localIdentity: UserIdentity,
        conversation: Conversation,
        contact: Contact,
    ): String? = proposer.proposalIdForDraft(draftId, localIdentity, conversation, contact)

    fun signingSnapshot(): DealSigningSnapshot? = DealSigningLookup.find(
        database,
        protector,
        local.messagingPublicKey,
        peerParticipant.messagingPublicKey,
    )

    suspend fun reviewBoundDeal(): String = sendBound { service, dealId -> service.queuePartyBReview(dealId) }

    suspend fun confirmBoundName(legalName: String): String =
        sendBound { service, dealId -> service.queueOwnName(dealId, legalName) }

    /**
     * Phase 5.4D: records local review consent for the complete document this
     * phone can display (fail closed if it cannot render). Sends nothing.
     */
    fun recordBoundReview(): String {
        val snapshot = signingSnapshot() ?: throw DealStateException("The proposal is missing.")
        val view = (snapshot.document as? DealDocumentResult.Ready)?.view
            ?: throw DealStateException("The agreement could not be displayed.")
        if (view.hiddenCharacters.isNotEmpty()) {
            throw DealStateException("The agreement contains invisible or text-direction characters.")
        }
        val (service, _) = acting(snapshot.dealId)
        return service.recordOwnReview(snapshot.dealId, view.versionId, view.documentHash)
    }

    suspend fun signBoundDeal(): String = sendBound { service, dealId -> service.queueOwnSignature(dealId) }

    suspend fun confirmBoundClosure(): String = sendBound { service, dealId -> service.queueOwnClosure(dealId) }

    suspend fun reviseBoundDeal(terms: String): String =
        sendBound { service, dealId -> service.queueSigningRevision(dealId, terms) }

    suspend fun rejectBoundDeal(note: String): String =
        sendBound { service, dealId -> service.queueSigningRejection(dealId, note) }

    /** Test and recovery entry for one already authenticated plaintext envelope. */
    internal suspend fun acceptEnvelope(plain: ByteArray) {
        dispatch(plain.copyOf())
    }

    suspend fun sendDraft(
        draftId: String,
        localIdentity: UserIdentity,
        conversation: Conversation,
        contact: Contact,
    ): String {
        val existing = proposer.proposalIdForDraft(draftId, localIdentity, conversation, contact)
        val id = existing ?: proposer.queueProposal(draftId, localIdentity, conversation, contact).envelopeId
        deliver(proposer, proposerSession.conversationId)
        return id
    }

    suspend fun accept(inbound: InboundRecord) {
        if (inbound.kind != InnerKind.DEAL || inbound.text != null) {
            throw MessagingProtocolException("deal preview")
        }
        val body = inbound.deal ?: throw MessagingProtocolException("deal body")
        val peerKey = Base64Url.decode(inbound.peerIdentity)
        if (!peerKey.contentEquals(peerParticipant.messagingPublicKey)) {
            throw DealFormatException("The sender is not the authenticated contact.")
        }
        val chunk = DealFrameChunk(
            envelopeId = body.envelopeId,
            index = body.chunkIndex.toInt(),
            count = body.chunkCount.toInt(),
            frameSha256 = body.frameSha256,
            bytes = Base64Url.decode(body.chunk),
        )
        val frame = inbox.offer(inbound.messageId, chunk) ?: return
        dispatch(frame)
    }

    private suspend fun dispatch(frame: ByteArray) {
        var envelopeId: String? = null
        try {
            val envelope = DealEnvelopes.parse(frame)
            envelopeId = envelope.envelopeId
            if (!envelope.senderKey.contentEquals(peerParticipant.messagingPublicKey)) {
                throw DealFormatException("The sender is not the authenticated contact.")
            }
            if (!envelope.recipientKey.contentEquals(local.messagingPublicKey)) {
                throw DealFormatException("The recipient is not this device.")
            }
            val service = serviceFor(envelope.event, envelope.conversationId)
            service.acceptPlain(frame)
            inbox.release(envelope.envelopeId)
            deliver(service, envelope.conversationId)
        } catch (e: DealFormatException) {
            envelopeId?.let { inbox.release(it) }
            throw e
        } finally {
            frame.fill(0)
        }
    }

    private suspend fun deliver(service: DealTransportService, conversationId: String) {
        val queued = service.copyQueued()
        try {
            for ((envelopeId, plain) in queued) {
                val parsed = try {
                    DealEnvelopes.parse(plain)
                } catch (_: DealFormatException) {
                    null
                }
                if (parsed == null ||
                    parsed.conversationId != conversationId ||
                    !parsed.recipientKey.contentEquals(peerParticipant.messagingPublicKey)
                ) {
                    plain.fill(0)
                    continue
                }
                try {
                    peer.sendDealPayload(plain)
                } finally {
                    plain.fill(0)
                }
                service.markSent(envelopeId)
            }
        } finally {
            for ((_, plain) in queued) plain.fill(0)
        }
    }

    private suspend fun sendBound(
        block: (DealTransportService, String) -> DealTransportResult,
    ): String {
        val dealId = boundDealId()
        val (service, conversationId) = acting(dealId)
        val result = block(service, dealId)
        deliver(service, conversationId)
        return result.envelopeId
    }

    private fun boundDealId(): String =
        signingSnapshot()?.dealId ?: throw DealStateException("The proposal is missing.")

    private fun acting(dealId: String): Pair<DealTransportService, String> {
        val parties = database.dealDao().parties(dealId)
        val localParty = parties.firstOrNull { database.dealDao().findKeys(it.partyId) != null }
            ?: throw DealStateException("The local deal key is missing.")
        if (localParty.role == PartyRole.PROPOSER.name) {
            return proposer to proposerSession.conversationId
        }
        val inbound = database.dealDao().transportForDeal(dealId, DealTransportState.IN, DealWireEvent.PROPOSAL.wire)
            .firstOrNull()
            ?: database.dealDao().transportForDeal(dealId, DealTransportState.IN, DealWireEvent.REVISED_PROPOSAL.wire)
                .firstOrNull()
            ?: throw DealStateException("The proposal is missing.")
        return counterparty(inbound.conversationId) to inbound.conversationId
    }

    private fun serviceFor(event: DealWireEvent, conversationId: String): DealTransportService = when (event) {
        DealWireEvent.PROPOSAL, DealWireEvent.REVISED_PROPOSAL -> counterparty(conversationId)
        DealWireEvent.ACK, DealWireEvent.REQUEST_CHANGES, DealWireEvent.REJECTION, DealWireEvent.DELIVERY -> {
            if (conversationId != proposerSession.conversationId) {
                throw DealFormatException("The envelope is for a different conversation.")
            }
            proposer
        }
        DealWireEvent.REVIEW,
        DealWireEvent.NAME_CONFIRMATION,
        DealWireEvent.SIGNATURE,
        DealWireEvent.SIGNING_REJECTION,
        DealWireEvent.SIGNING_REVISION,
        DealWireEvent.CLOSURE,
        -> eitherParty(conversationId)
    }

    private fun eitherParty(conversationId: String): DealTransportService {
        if (conversationId == proposerSession.conversationId) return proposer
        return counterparty(conversationId)
    }

    private fun counterparty(conversationId: String): DealTransportService {
        val pinned = counterpartySession
        if (pinned == null) {
            val session = DealMessagingSession.establish(conversationId, local, peerParticipant)
            counterpartySession = session
            val created = service(session, PartyRole.COUNTERPARTY)
            counterparty = created
            return created
        }
        if (pinned.conversationId != conversationId) {
            throw DealFormatException("The envelope is for a different conversation.")
        }
        return counterparty ?: error("deal counterparty")
    }

    private fun service(session: DealMessagingSession, role: PartyRole): DealTransportService = DealTransportService(
        database = database,
        protector = protector,
        drafts = drafts,
        session = session,
        cipher = cipher,
        localRole = role,
        localMessagingSigner = signer,
        debugBuild = debugBuild,
        clock = clock,
    )
}
