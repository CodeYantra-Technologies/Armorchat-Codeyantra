package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.RelayQueueState
import com.codeyantra.armorchat.core.network.RelaySubmitResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OutboxPolicyTest {
    @Test
    fun exponentialBackoffCapsAtFiveMinutes() {
        assertThat(OutboxPolicy.backoffCapSeconds(0)).isEqualTo(2L)
        assertThat(OutboxPolicy.backoffCapSeconds(1)).isEqualTo(4L)
        assertThat(OutboxPolicy.backoffCapSeconds(7)).isEqualTo(256L)
        assertThat(OutboxPolicy.backoffCapSeconds(8)).isEqualTo(300L)
        assertThat(OutboxPolicy.backoffCapSeconds(20)).isEqualTo(300L)
    }

    @Test
    fun jitterStaysInsideCap() {
        val at = OutboxPolicy.nextRetryAt(1_000, 0) { cap -> cap }
        assertThat(at).isEqualTo(1_002)
        val zero = OutboxPolicy.nextRetryAt(1_000, 8) { 0 }
        assertThat(zero).isEqualTo(1_000)
    }

    @Test
    fun offlineAndSuspendedDoNotBurnAttempts() {
        assertThat(OutboxPolicy.burnsAttempt(OutboxPolicy.SubmitOutcome.OFFLINE)).isFalse()
        assertThat(OutboxPolicy.burnsAttempt(OutboxPolicy.SubmitOutcome.SUSPENDED)).isFalse()
        assertThat(OutboxPolicy.burnsAttempt(OutboxPolicy.SubmitOutcome.RETRYABLE)).isTrue()
        assertThat(OutboxPolicy.classify(RelaySubmitResult.Unavailable))
            .isEqualTo(OutboxPolicy.SubmitOutcome.OFFLINE)
        assertThat(OutboxPolicy.classify(RelaySubmitResult.ServiceSuspended(30)))
            .isEqualTo(OutboxPolicy.SubmitOutcome.SUSPENDED)
        assertThat(OutboxPolicy.classify(RelaySubmitResult.Rejected("DIGEST_MISMATCH")))
            .isEqualTo(OutboxPolicy.SubmitOutcome.PERMANENT)
        assertThat(OutboxPolicy.classify(RelaySubmitResult.Rejected("TIMEOUT")))
            .isEqualTo(OutboxPolicy.SubmitOutcome.RETRYABLE)
        unused(RelayQueueState.ACCEPTED)
    }

    @Test
    fun expiryUsesFiveMinuteSkew() {
        assertThat(OutboxPolicy.stillLive(100, 400)).isTrue()
        assertThat(OutboxPolicy.stillLive(100, 401)).isFalse()
    }

    private fun unused(value: Any?) = Unit
}
