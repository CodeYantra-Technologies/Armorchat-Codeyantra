package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.database.entity.MessageEntity
import com.codeyantra.armorchat.core.mesh.CustodyBundle
import com.codeyantra.armorchat.core.mesh.MeshDeliveredStore
import com.codeyantra.armorchat.core.mesh.MeshHoldResult
import com.codeyantra.armorchat.core.mesh.MeshIds
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.domain.model.MessageType
import kotlinx.coroutines.CancellationException

/**
 * Mesh-1K-O (P3): authenticated offline delivery ACK over Mesh, built ONLY from existing contracts:
 *
 *  - The ACK is the existing MLS DELIVERY receipt (InnerKind.DELIVERY + InnerBody.Receipt:
 *    targetMessageId, targetSessionId, targetCiphertextSha256, targetEpoch), created by the existing
 *    ReceiptMessagingPeer receipt intent and sealed by the existing encryptReceipt (same inner record,
 *    same MLS application message, same relay envelope). No new frame, field, kind or protocol version.
 *  - Receiver: after the inbox row + MLS state are durably committed (MeshDelivery.deliverDurable
 *    afterCommit), [sealAfterCommit] seals the receipt, persists the advanced MLS state (contact_sessions)
 *    BEFORE the ciphertext leaves memory, then holds the sealed envelope in Mesh custody. The same
 *    ciphertext stays in the in-memory receipt outbox, so a later relay flush submits identical bytes.
 *  - Sender: the receipt is validated by the existing ReceiptMessagingPeer.bindInbound/previewReceipt/
 *    applyReceipt checks (MLS decrypt, mailbox, recipient pin/device/generation, sender pin/device/
 *    generation, receipt session == MLS session, target session, target ciphertext digest, target epoch,
 *    target peer == receipt sender). Only after that does [stageRelease] produce a [MeshInboxCommit.Release]
 *    that MeshInboxCommit applies (status DELIVERED + delete of this phone's OWN custody rows for that
 *    exact envelope) in the same Room transaction as the contact_sessions upsert.
 *  - After a restart the in-memory outgoing record is gone; [durableTargets] rebuilds a verification-only
 *    target from the durable outgoing row (receipt* columns filled at send by [sendBinding]) for messages
 *    this phone still holds in its own custody. Rows without those columns are never trusted (fail closed).
 *
 * Inventory ids are never used here: nothing in this file reads remote advertised ids.
 */
internal object MeshReceiptAck {

    /** sendText: bind the durable outgoing row to the exact sealed ciphertext (columns exist in v13). */
    fun sendBinding(row: MessageEntity, record: OutboundRecord): MessageEntity =
        if (!row.isOutgoing) row else row.copy(
            receiptSessionId = record.sessionId,
            receiptCiphertextSha256 = record.ciphertextSha256,
            receiptEpoch = record.epoch,
        )

    /**
     * Verification-only target for [ReceiptMessagingPeer.verificationTargets], from a durable outgoing TEXT
     * row whose send binding matches [digest] (the digest of this phone's own custody envelope). The record
     * has no envelope and attempt FAILED, so the outbox can never re-encrypt or resubmit it.
     */
    fun verificationTarget(row: MessageEntity, peerPin: String, digest: String): OutboundRecord? {
        if (!row.isOutgoing || row.type != MessageType.TEXT.name) return null
        val sessionId = row.receiptSessionId ?: return null
        val sha = row.receiptCiphertextSha256 ?: return null
        val epoch = row.receiptEpoch ?: return null
        if (sha != digest) return null
        val evidence = when (row.status) {
            MessageStatus.READ.name -> DeliveryEvidence.READ
            MessageStatus.DELIVERED.name -> DeliveryEvidence.DELIVERED
            MessageStatus.SENT.name -> DeliveryEvidence.SENT
            else -> DeliveryEvidence.NONE
        }
        return OutboundRecord(
            messageId = row.id,
            kind = InnerKind.TEXT,
            sessionId = sessionId,
            epoch = epoch,
            ciphertextSha256 = sha,
            expiresAt = 0L,
            createdAt = row.createdAtEpochMs / 1000,
            peerIdentity = peerPin,
            peerDeviceId = "",
            peerGeneration = 0L,
            progress = MessageProgress(attempt = AttemptState.FAILED, evidence = evidence),
        )
    }

    /**
     * Sender, before a Mesh offer: peerPin -> (messageId -> verification target) for this phone's OWN live
     * custody rows ([own] = EmergencyMeshEngine.ownHeld, hopCount 0) whose outgoing row carries a matching
     * send binding. [pinForConversation] maps the row's conversation to its contact pin (null = skip).
     */
    suspend fun durableTargets(
        own: List<CustodyBundle>,
        findById: suspend (String) -> MessageEntity?,
        pinForConversation: suspend (String) -> String?,
    ): Map<String, Map<String, OutboundRecord>> {
        val out = linkedMapOf<String, MutableMap<String, OutboundRecord>>()
        for (bundle in own) {
            if (bundle.hopCount != 0) continue
            val id = MeshDelivery.outerMessageId(bundle.payload) ?: continue
            val digest = MeshDelivery.outerCiphertextDigest(bundle.payload) ?: continue
            val row = findById(id) ?: continue
            val pin = pinForConversation(row.conversationId) ?: continue
            val target = verificationTarget(row, pin, digest) ?: continue
            out.getOrPut(pin) { linkedMapOf() }[id] = target
        }
        return out
    }

    /** This phone's own custody ids (hopCount 0) holding exactly [targetMessageId] sealed as [digest]. */
    fun custodyIdsFor(own: List<CustodyBundle>, targetMessageId: String, digest: String): List<String> =
        own.filter {
            it.hopCount == 0 &&
                MeshDelivery.outerMessageId(it.payload) == targetMessageId &&
                MeshDelivery.outerCiphertextDigest(it.payload) == digest
        }.map { it.idHex }.distinct().sorted()

    /**
     * Sender, from deliverMesh's persistInbound: [inbound] is what ReceiptMessagingPeer.processLeased hands
     * out ONLY after the receipt passed bindInbound + previewReceipt + applyReceipt (or an in-process replay
     * of such a receipt). [target] = the store's record for the target after applyReceipt. Returns null for
     * anything that is not a validated DELIVERY receipt for a user message with DELIVERED+ evidence.
     */
    fun stageRelease(
        peerPin: String,
        conversationId: String,
        inbound: InboundRecord,
        target: OutboundRecord?,
        own: List<CustodyBundle>,
        nowMs: Long,
    ): MeshInboxCommit.Release? {
        if (inbound.kind != InnerKind.DELIVERY || !inbound.accepted) return null
        val targetId = inbound.receiptTargetMessageId ?: return null
        if (target == null || target.messageId != targetId || !target.kind.isUserMessage) return null
        if (target.peerIdentity != peerPin || inbound.peerIdentity != peerPin) return null
        if (target.progress.evidence.rank < DeliveryEvidence.DELIVERED.rank) return null
        val digest = target.ciphertextSha256 ?: return null
        return MeshInboxCommit.Release(
            peerPin = peerPin,
            conversationId = conversationId,
            targetMessageId = targetId,
            targetCiphertextSha256 = digest,
            custodyIds = custodyIdsFor(own, targetId, digest),
            receiptMessageId = inbound.messageId,
            receiptSessionId = inbound.sessionId,
            deliveredAtMs = nowMs,
        )
    }

    /**
     * Mesh-1K-R (D1): HELD = every sealed receipt is durably in custody (Held or already-held Duplicate);
     * HOLD_FAILED = at least one hold THREW (Room failure); HOLD_REJECTED = none threw but at least one was
     * refused and NOT kept for retry; HOLD_QUEUED = every refusal was kept in the in-memory retry list.
     * In every non-HELD case the sealed receipt also stays in the in-memory relay outbox (unchanged).
     */
    enum class Sealed { NONE, HELD, HOLD_FAILED, ROLLED_BACK, HOLD_QUEUED, HOLD_REJECTED }

    /**
     * Receiver, run by deliverDurable's afterCommit (under the messaging mutex, after the inbox row and the
     * MLS state are durable). For every newly committed (peerPin, messageId): [seal] =
     * ReceiptMessagingPeer.sealDeliveryReceipt. Then [persist] the advanced MLS state of each sealing link.
     * Only when every persist succeeded does any sealed byte leave memory ([admit] = Mesh custody). Any
     * failure before that restores memory to [checkpoint] (= what is durable): no receipt exists anywhere.
     * A failed or refused hold leaves the sealed receipt in the in-memory outbox (relay flush can still send it).
     *
     * Mesh-1K-R (D1): [admit] returns the typed custody result, so `held` counts ONLY confirmed durable
     * admissions (MeshHoldResult.Held / Duplicate); refusals and throws are counted separately. Nothing here
     * retries or re-seals: a "full" refusal is retried by EmergencyMeshEngine with the identical bytes.
     */
    suspend fun <C> sealAfterCommit(
        targets: List<Pair<String, String>>,
        checkpoint: () -> C,
        rollback: (C) -> Unit,
        seal: (peerPin: String, messageId: String) -> String?,
        persist: suspend (peerPin: String) -> Unit,
        admit: suspend (ByteArray) -> MeshHoldResult,
        log: (String) -> Unit = {},
    ): Sealed {
        if (targets.isEmpty()) return Sealed.NONE
        val saved = checkpoint()
        val sealed = mutableListOf<Pair<String, String>>()
        try {
            for ((pin, id) in targets) seal(pin, id)?.let { sealed += pin to it }
            if (sealed.isEmpty()) {
                rollback(saved)
                log("receipt=none")
                return Sealed.NONE
            }
            for (pin in sealed.map { it.first }.distinct()) persist(pin)
        } catch (c: CancellationException) {
            rollback(saved)
            throw c
        } catch (_: Exception) {
            rollback(saved)
            log("receipt=rolled_back")
            return Sealed.ROLLED_BACK
        }
        var held = 0
        var queued = 0
        var rejected = 0
        var failed = 0
        for ((_, canonical) in sealed) {
            try {
                when (val r = admit(canonical.toByteArray(Charsets.UTF_8))) {
                    is MeshHoldResult.Held, is MeshHoldResult.Duplicate -> held++
                    is MeshHoldResult.Rejected -> if (r.retryQueued) queued++ else rejected++
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Exception) {
                failed++
            }
        }
        log("receipt=sealed count=${sealed.size} held=$held queued=$queued rejected=$rejected failed=$failed")
        return when {
            held == sealed.size -> Sealed.HELD
            failed > 0 -> Sealed.HOLD_FAILED
            rejected > 0 -> Sealed.HOLD_REJECTED
            else -> Sealed.HOLD_QUEUED
        }
    }

    /**
     * Mesh-1K-O shape kept for existing callers/tests whose [hold] returns Unit: a hold that returns
     * normally is treated as a durable admission (1K-O semantics). Production (ChatMessagingService) uses
     * the typed [admit] overload above since Mesh-1K-R.
     */
    @JvmName("sealAfterCommitUnitHold")
    suspend fun <C> sealAfterCommit(
        targets: List<Pair<String, String>>,
        checkpoint: () -> C,
        rollback: (C) -> Unit,
        seal: (peerPin: String, messageId: String) -> String?,
        persist: suspend (peerPin: String) -> Unit,
        hold: suspend (ByteArray) -> Unit,
        log: (String) -> Unit = {},
    ): Sealed = sealAfterCommit(
        targets = targets,
        checkpoint = checkpoint,
        rollback = rollback,
        seal = seal,
        persist = persist,
        admit = { bytes ->
            hold(bytes)
            MeshHoldResult.Held(MeshIds.hex(MeshIds.idFor(bytes)))
        },
        log = log,
    )

    /**
     * Sender, after deliverDurable: a delivered-here marker for the RECEIPT bundle (so the receiver stops
     * re-offering it), only when exactly one validated receipt release was durably committed for this
     * payload. Returns null when nothing is to be recorded, else whether the marker reached disk.
     */
    suspend fun recordReceiptMarker(
        payload: ByteArray,
        ok: Boolean,
        outcome: MeshDelivery.Outcome?,
        applied: List<MeshInboxCommit.Release>,
        record: suspend (payload: ByteArray, messageId: String, binding: String) -> Boolean,
    ): Boolean? {
        if (!ok || outcome != MeshDelivery.Outcome.DELIVERED) return null
        return try {
            val release = applied.singleOrNull() ?: return null
            val outerId = MeshDelivery.outerMessageId(payload) ?: return null
            if (outerId != release.receiptMessageId) return null
            record(payload, outerId, MeshDeliveredStore.bindingOf(release.conversationId, release.receiptSessionId, outerId))
        } catch (c: CancellationException) {
            throw c
        } catch (_: Exception) {
            false
        }
    }
}
