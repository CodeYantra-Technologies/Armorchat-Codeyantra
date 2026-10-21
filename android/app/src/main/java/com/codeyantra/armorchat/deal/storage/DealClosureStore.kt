package com.codeyantra.armorchat.deal.storage

import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.ClosureAdmit
import com.codeyantra.armorchat.deal.domain.ClosureAdmitResult
import com.codeyantra.armorchat.deal.domain.ClosureConfirmation
import com.codeyantra.armorchat.deal.domain.ClosureDefect
import com.codeyantra.armorchat.deal.domain.ClosureKind
import com.codeyantra.armorchat.deal.domain.ClosureProjection
import com.codeyantra.armorchat.deal.domain.ClosureState
import com.codeyantra.armorchat.deal.domain.DealClosure
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.domain.identity.Base64Url
import java.util.concurrent.Callable

/**
 * Stores closure confirmations in [DealClosureRow]. A confirmation is not a
 * signature row. The deal status becomes CLOSED only inside the transaction
 * that stores the second valid confirmation. A rejection writes an audit and
 * no confirmation row.
 */
class DealClosureStore(
    private val database: DealDatabase,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val dao = database.dealDao()

    fun admit(deal: CanonicalDeal, confirmation: ClosureConfirmation): ClosureAdmitResult =
        database.runInTransaction(Callable { admitLocked(deal, confirmation) })

    /** Re-verifies stored rows. Invalid rows do not count and this method does not throw. */
    fun project(deal: CanonicalDeal): ClosureProjection = try {
        projectLocked(deal)
    } catch (e: RuntimeException) {
        ClosureProjection(ClosureState.UNAVAILABLE, buyerConfirmed = false, supplierConfirmed = false, rolesBound = false)
    }

    /** Two stored SIGN rows over this document. A stored status is not proof. */
    fun finalityProven(deal: CanonicalDeal): Boolean = try {
        proven(deal)
    } catch (e: RuntimeException) {
        false
    }

    private fun admitLocked(deal: CanonicalDeal, confirmation: ClosureConfirmation): ClosureAdmitResult {
        val defect = DealClosure.check(deal, confirmation)
        if (defect != null) return rejected(deal.dealId, defect, projectLocked(deal).state)
        val stored = dao.findDeal(deal.dealId) ?: return rejected(deal.dealId, ClosureDefect.NOT_FINAL, ClosureState.UNAVAILABLE)
        if (stored.currentVersionId != deal.versionId) {
            return rejected(deal.dealId, ClosureDefect.STALE, projectLocked(deal).state)
        }
        val status = try {
            DealStatus.valueOf(stored.status)
        } catch (e: IllegalArgumentException) {
            return rejected(deal.dealId, ClosureDefect.NOT_FINAL, ClosureState.UNAVAILABLE)
        }
        if (!proven(deal) || !closable(status)) {
            return rejected(deal.dealId, ClosureDefect.NOT_FINAL, projectLocked(deal).state)
        }
        val hash = Base64Url.encode(deal.documentHash())
        val existing = dao.findClosure(deal.dealId, deal.versionId, confirmation.partyId, confirmation.kind.wire)
        if (existing != null) {
            if (same(existing, confirmation, hash)) {
                audit(deal.dealId, ACTION_DUPLICATE, DealClosure.kindOutcome(confirmation.kind))
                return ClosureAdmitResult(ClosureAdmit.DUPLICATE, null, projectLocked(deal).state)
            }
            return rejected(deal.dealId, ClosureDefect.CONFLICT, projectLocked(deal).state)
        }
        dao.insertClosure(
            DealClosureRow(
                dealId = confirmation.dealId,
                versionId = confirmation.versionId,
                partyId = confirmation.partyId,
                kind = confirmation.kind.wire,
                signature = confirmation.signature.copyOf(),
                signerPublicKey = Base64Url.encode(confirmation.signerPublicKey),
                documentSha256 = hash,
                createdAtEpochMs = clock(),
            ),
        )
        audit(deal.dealId, ACTION_ACCEPT, DealClosure.kindOutcome(confirmation.kind))
        val projected = projectLocked(deal)
        if (projected.state != ClosureState.CLOSED || status == DealStatus.CLOSED) {
            return ClosureAdmitResult(ClosureAdmit.ACCEPTED, null, projected.state)
        }
        val next = DealLifecycle.transition(status, DealEvent.CLOSE)
        dao.updateDeal(deal.dealId, next.name, deal.versionId, clock())
        audit(deal.dealId, ACTION_CLOSED, "both")
        return ClosureAdmitResult(ClosureAdmit.CLOSED, null, ClosureState.CLOSED)
    }

    private fun projectLocked(deal: CanonicalDeal): ClosureProjection {
        if (deal.identity == null || !DealClosure.rolesBound(deal)) {
            return ClosureProjection(
                state = ClosureState.UNAVAILABLE,
                buyerConfirmed = false,
                supplierConfirmed = false,
                rolesBound = false,
            )
        }
        if (!proven(deal)) {
            return ClosureProjection(
                state = ClosureState.OPEN,
                buyerConfirmed = false,
                supplierConfirmed = false,
                rolesBound = true,
            )
        }
        val hash = Base64Url.encode(deal.documentHash())
        var goods = false
        var payment = false
        for (row in dao.closures(deal.dealId, deal.versionId)) {
            if (row.documentSha256 != hash) continue
            val confirmation = row.toConfirmation() ?: continue
            if (DealClosure.check(deal, confirmation) != null) continue
            when (confirmation.kind) {
                ClosureKind.GOODS_RECEIVED -> goods = true
                ClosureKind.PAYMENT_RECEIVED -> payment = true
            }
        }
        val state = when {
            goods && payment -> ClosureState.CLOSED
            goods -> ClosureState.AWAITING_PAYMENT
            payment -> ClosureState.AWAITING_GOODS
            else -> ClosureState.OPEN
        }
        return ClosureProjection(state, goods, payment, rolesBound = true)
    }

    private fun proven(deal: CanonicalDeal): Boolean {
        val rows = dao.signatures(deal.dealId, deal.versionId)
        val signatures = rows.mapNotNull { it.toSignatureOrNull() }
        if (signatures.size != rows.size) return false
        return DealFinality.assess(
            deal = deal,
            signatures = signatures,
            pinnedSigningKeys = deal.parties.associate { it.partyId to it.signingPublicKey },
        ).proven
    }

    private fun closable(status: DealStatus): Boolean = when (status) {
        DealStatus.FULLY_SIGNED,
        DealStatus.BACKUP_PENDING,
        DealStatus.EXECUTED,
        DealStatus.CLOSED,
        -> true
        else -> false
    }

    private fun same(row: DealClosureRow, confirmation: ClosureConfirmation, hash: String): Boolean =
        row.dealId == confirmation.dealId &&
            row.versionId == confirmation.versionId &&
            row.partyId == confirmation.partyId &&
            row.kind == confirmation.kind.wire &&
            row.documentSha256 == hash &&
            row.signerPublicKey == Base64Url.encode(confirmation.signerPublicKey) &&
            row.signature.contentEquals(confirmation.signature)

    private fun rejected(dealId: String, defect: ClosureDefect, state: ClosureState): ClosureAdmitResult {
        audit(dealId, ACTION_REJECT, DealClosure.rejectOutcome(defect))
        return ClosureAdmitResult(ClosureAdmit.REJECTED, defect, state)
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

    private fun DealClosureRow.toConfirmation(): ClosureConfirmation? = try {
        ClosureConfirmation(
            dealId = dealId,
            versionId = versionId,
            partyId = partyId,
            kind = ClosureKind.fromWire(kind),
            signature = signature,
            signerPublicKey = Base64Url.decode(signerPublicKey),
        )
    } catch (e: RuntimeException) {
        null
    }

    private fun DealSignatureRow.toSignatureOrNull(): DealSignature? = try {
        if (action != SignAction.SIGN.name) {
            null
        } else {
            DealSignature(
                dealId = dealId,
                versionId = versionId,
                partyId = partyId,
                action = SignAction.SIGN,
                signature = signature,
                signerPublicKey = Base64Url.decode(signerPublicKey),
            )
        }
    } catch (e: RuntimeException) {
        null
    }

    companion object {
        const val ACTION_ACCEPT = "closure_accept"
        const val ACTION_DUPLICATE = "closure_duplicate"
        const val ACTION_REJECT = "closure_reject"
        const val ACTION_CLOSED = "closure_closed"
    }
}
