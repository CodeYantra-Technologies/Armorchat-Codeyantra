package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.RelaySubmitResult

object OutboxPolicy {
    const val MAX_AUTOMATIC_ATTEMPTS = 20
    const val BASE_SECONDS = 2L
    const val CAP_SECONDS = 300L
    const val TTL_SECONDS = 7L * 24 * 3600
    const val CLOCK_SKEW_SECONDS = 300L

    enum class SubmitOutcome {
        ACCEPTED,
        RETRYABLE,
        PERMANENT,
        OFFLINE,
        SUSPENDED,
    }

    fun backoffCapSeconds(retryIndex: Int): Long {
        val n = retryIndex.coerceAtLeast(0).coerceAtMost(30)
        val exponential = BASE_SECONDS * (1L shl n)
        return minOf(CAP_SECONDS, exponential)
    }

    fun nextRetryAt(nowEpochSeconds: Long, retryIndex: Int, jitter: (Long) -> Long): Long {
        val cap = backoffCapSeconds(retryIndex)
        val wait = jitter(cap).coerceIn(0L, cap)
        return nowEpochSeconds + wait
    }

    /**
     * Mesh-1K-B: why a relay submit threw. Both map to [RelaySubmitResult.Unavailable] (OFFLINE,
     * no attempt burned); the split only records whether the relay might already hold the envelope.
     * NOT_SENT: the connection or TLS session never existed, so no request body reached the relay.
     * AMBIGUOUS: anything else (read/call timeout, reset, EOF after write). A retry is still safe
     * because it resubmits the identical envelope under the same Idempotency-Key.
     */
    enum class TransportFailure { NOT_SENT, AMBIGUOUS }

    fun classifyTransportFailure(e: java.io.IOException): TransportFailure = when (e) {
        is java.net.UnknownHostException,
        is java.net.ConnectException,
        is java.net.NoRouteToHostException,
        is java.net.PortUnreachableException,
        is javax.net.ssl.SSLHandshakeException,
        -> TransportFailure.NOT_SENT
        else -> TransportFailure.AMBIGUOUS
    }

    fun burnsAttempt(outcome: SubmitOutcome): Boolean = when (outcome) {
        SubmitOutcome.OFFLINE, SubmitOutcome.SUSPENDED -> false
        SubmitOutcome.ACCEPTED, SubmitOutcome.RETRYABLE, SubmitOutcome.PERMANENT -> true
    }

    fun classify(result: RelaySubmitResult): SubmitOutcome = when (result) {
        is RelaySubmitResult.Accepted -> SubmitOutcome.ACCEPTED
        is RelaySubmitResult.Unavailable -> SubmitOutcome.OFFLINE
        is RelaySubmitResult.ServiceSuspended -> SubmitOutcome.SUSPENDED
        is RelaySubmitResult.Rejected -> classifyRejected(result.reason)
    }

    fun stillLive(expiresAt: Long, nowEpochSeconds: Long): Boolean =
        expiresAt + CLOCK_SKEW_SECONDS >= nowEpochSeconds

    private fun classifyRejected(reason: String): SubmitOutcome {
        val token = reason.uppercase()
        return if (token in PERMANENT_CODES) SubmitOutcome.PERMANENT else SubmitOutcome.RETRYABLE
    }

    private val PERMANENT_CODES = setOf(
        "MALFORMED",
        "FORBIDDEN",
        "DIGEST_MISMATCH",
        "REVOKED",
        "TOO_LARGE",
        "BAD_REQUEST",
        "UNSUPPORTED",
        "CONFLICT",
        "UNAUTHORIZED",
    )
}
