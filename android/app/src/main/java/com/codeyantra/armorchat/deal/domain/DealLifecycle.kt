package com.codeyantra.armorchat.deal.domain

/**
 * Deal lifecycle. A stored [DealStatus.FULLY_SIGNED], [DealStatus.BACKUP_PENDING],
 * or [DealStatus.EXECUTED] value only claims finality. Proof is
 * [DealFinality.assess]: two distinct SIGN signatures over one canonical hash.
 * Backup receipts are not that proof. Claimed-final statuses are still immutable
 * so a bad row is not rewritten.
 *
 * One signature can be superseded. The signature stays on the old version.
 * Cancellation and expiry remain available until both signatures land.
 * A finalized agreement cannot be cancelled, expired, or revised.
 *
 * DRAFT → PROPOSED → NEGOTIATING → PARTIALLY_SIGNED → FULLY_SIGNED.
 * Optional after finalization: FULLY_SIGNED → BACKUP_PENDING → EXECUTED.
 *
 * The mandatory signing path for a bound agreement is [SigningWorkflow]. It
 * uses these same statuses. It does not replace this table.
 */
enum class DealStatus {
    DRAFT,
    PROPOSED,
    NEGOTIATING,
    PARTIALLY_SIGNED,
    FULLY_SIGNED,
    BACKUP_PENDING,
    EXECUTED,
    REJECTED,
    CANCELLED,
    EXPIRED,
}

enum class DealEvent {
    PROPOSE,
    NEGOTIATE,
    PARTIAL_SIGN,
    FULL_SIGN,
    MARK_BACKUP_PENDING,
    EXECUTE,
    REJECT,
    CANCEL,
    EXPIRE,
    SUPERSEDE,
}

object DealLifecycle {
    private val allowed: Map<DealStatus, Map<DealEvent, DealStatus>> = mapOf(
        DealStatus.DRAFT to mapOf(
            DealEvent.PROPOSE to DealStatus.PROPOSED,
            DealEvent.CANCEL to DealStatus.CANCELLED,
            DealEvent.EXPIRE to DealStatus.EXPIRED,
        ),
        DealStatus.PROPOSED to mapOf(
            DealEvent.NEGOTIATE to DealStatus.NEGOTIATING,
            DealEvent.PARTIAL_SIGN to DealStatus.PARTIALLY_SIGNED,
            DealEvent.REJECT to DealStatus.REJECTED,
            DealEvent.CANCEL to DealStatus.CANCELLED,
            DealEvent.EXPIRE to DealStatus.EXPIRED,
            DealEvent.SUPERSEDE to DealStatus.NEGOTIATING,
        ),
        DealStatus.NEGOTIATING to mapOf(
            DealEvent.PARTIAL_SIGN to DealStatus.PARTIALLY_SIGNED,
            DealEvent.REJECT to DealStatus.REJECTED,
            DealEvent.CANCEL to DealStatus.CANCELLED,
            DealEvent.EXPIRE to DealStatus.EXPIRED,
            DealEvent.SUPERSEDE to DealStatus.NEGOTIATING,
        ),
        DealStatus.PARTIALLY_SIGNED to mapOf(
            DealEvent.FULL_SIGN to DealStatus.FULLY_SIGNED,
            DealEvent.REJECT to DealStatus.REJECTED,
            DealEvent.CANCEL to DealStatus.CANCELLED,
            DealEvent.EXPIRE to DealStatus.EXPIRED,
            DealEvent.SUPERSEDE to DealStatus.NEGOTIATING,
        ),
        DealStatus.FULLY_SIGNED to mapOf(
            DealEvent.MARK_BACKUP_PENDING to DealStatus.BACKUP_PENDING,
        ),
        DealStatus.BACKUP_PENDING to mapOf(
            DealEvent.EXECUTE to DealStatus.EXECUTED,
        ),
    )

    fun transition(from: DealStatus, event: DealEvent): DealStatus {
        return allowed[from]?.get(event)
            ?: throw DealStateException("Transition $from / $event is not allowed.")
    }

    /** The status string claims finality. It is not evidence. */
    fun claimsFinality(status: DealStatus): Boolean = when (status) {
        DealStatus.FULLY_SIGNED,
        DealStatus.BACKUP_PENDING,
        DealStatus.EXECUTED,
        -> true
        else -> false
    }

    /** True only when the status claims finality and the signature verdict is proven. */
    fun isFinalized(status: DealStatus, verdict: FinalityVerdict): Boolean =
        claimsFinality(status) && verdict.proven

    /**
     * Status writes that may follow a claimed-final status. Cancellation, expiry,
     * and returning to an editable state are absent. This does not prove the
     * signatures.
     */
    fun allowsFinalizedStatus(from: DealStatus, to: DealStatus): Boolean {
        if (!claimsFinality(from)) return true
        return when (from) {
            DealStatus.FULLY_SIGNED -> to == DealStatus.FULLY_SIGNED || to == DealStatus.BACKUP_PENDING
            DealStatus.BACKUP_PENDING -> to == DealStatus.BACKUP_PENDING || to == DealStatus.EXECUTED
            DealStatus.EXECUTED -> to == DealStatus.EXECUTED
            else -> false
        }
    }

    /** Open agreements can still grow a new version. Finalized ones cannot. */
    fun allowsRevision(status: DealStatus): Boolean = when (status) {
        DealStatus.DRAFT,
        DealStatus.PROPOSED,
        DealStatus.NEGOTIATING,
        DealStatus.PARTIALLY_SIGNED,
        -> true
        else -> false
    }
}
