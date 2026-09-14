package com.codeyantra.armorchat.domain.model

/**
 * Outbound delivery lifecycle.
 *
 * Production chat still does not set network states. The Phase 1D.4
 * test harness enforces monotonic evidence SENT → DELIVERED → READ.
 * Relay acceptance is SENT only and never DELIVERED or READ.
 */
enum class MessageStatus {
    QUEUED,
    ENCRYPTING,
    SENDING,
    SENT,
    DELIVERED,
    READ,
    FAILED,
}
