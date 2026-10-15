package com.codeyantra.armorchat.deal.domain

/**
 * Deal lifecycle. A signed version is never edited in place; [DealEvent.SUPERSEDE]
 * is refused once the deal is fully signed.
 *
 * DRAFT → PROPOSED → NEGOTIATING → PARTIALLY_SIGNED → FULLY_SIGNED →
 * BACKUP_PENDING → EXECUTED.
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
            DealEvent.CANCEL to DealStatus.CANCELLED,
        ),
        DealStatus.BACKUP_PENDING to mapOf(
            DealEvent.EXECUTE to DealStatus.EXECUTED,
            DealEvent.CANCEL to DealStatus.CANCELLED,
        ),
    )

    fun transition(from: DealStatus, event: DealEvent): DealStatus {
        return allowed[from]?.get(event)
            ?: throw DealStateException("Transition $from / $event is not allowed.")
    }

    /** Fully signed and later states cannot be superseded in Phase 1. */
    fun allowsRevision(status: DealStatus): Boolean = when (status) {
        DealStatus.DRAFT,
        DealStatus.PROPOSED,
        DealStatus.NEGOTIATING,
        DealStatus.PARTIALLY_SIGNED,
        -> true
        else -> false
    }
}
