package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.RelayQueueState
import com.codeyantra.armorchat.domain.model.MessageStatus

enum class DeliveryEvidence {
    NONE,
    SENT,
    DELIVERED,
    READ,
    ;

    val rank: Int
        get() = when (this) {
            NONE -> 0
            SENT -> 1
            DELIVERED -> 2
            READ -> 3
        }
}

enum class AttemptState {
    QUEUED,
    ENCRYPTING,
    SENDING,
    FAILED,
}

data class FailureNote(
    val reason: String,
    val retryable: Boolean,
    val lastAttemptAt: Long,
    val ambiguousAcceptance: Boolean,
)

/**
 * Attempt state is separate from monotonic delivery evidence.
 * Visible [status] never regresses SENT → earlier, or DELIVERED/READ → earlier.
 * FAILED is not proof of non-delivery and may still advance on a late receipt.
 */
data class MessageProgress(
    val attempt: AttemptState = AttemptState.QUEUED,
    val evidence: DeliveryEvidence = DeliveryEvidence.NONE,
    val retryAtEpochSeconds: Long = 0,
    val attemptCount: Int = 0,
    val failure: FailureNote? = null,
    val expiredWithoutReceipt: Boolean = false,
) {
    val status: MessageStatus
        get() = when (evidence) {
            DeliveryEvidence.READ -> MessageStatus.READ
            DeliveryEvidence.DELIVERED -> MessageStatus.DELIVERED
            DeliveryEvidence.SENT -> MessageStatus.SENT
            DeliveryEvidence.NONE -> when (attempt) {
                AttemptState.FAILED -> MessageStatus.FAILED
                AttemptState.SENDING -> MessageStatus.SENDING
                AttemptState.ENCRYPTING -> MessageStatus.ENCRYPTING
                AttemptState.QUEUED -> MessageStatus.QUEUED
            }
        }

    fun onEncrypting(): MessageProgress {
        check(evidence == DeliveryEvidence.NONE) { "cannot encrypt after evidence" }
        check(attempt == AttemptState.QUEUED) { "encrypt from QUEUED only" }
        return copy(attempt = AttemptState.ENCRYPTING, failure = null)
    }

    fun onSending(): MessageProgress {
        check(evidence == DeliveryEvidence.NONE) { "cannot send after evidence" }
        check(attempt == AttemptState.ENCRYPTING) { "send from ENCRYPTING only" }
        return copy(attempt = AttemptState.SENDING)
    }

    fun onRetryWait(retryAtEpochSeconds: Long, attemptCount: Int): MessageProgress {
        check(evidence == DeliveryEvidence.NONE) { "retry is for unaccepted work" }
        check(attempt == AttemptState.SENDING || attempt == AttemptState.ENCRYPTING) {
            "retry from in-flight only"
        }
        return copy(
            attempt = AttemptState.QUEUED,
            retryAtEpochSeconds = retryAtEpochSeconds,
            attemptCount = attemptCount,
        )
    }

    fun onFailed(note: FailureNote): MessageProgress {
        check(evidence == DeliveryEvidence.NONE) {
            "FAILED cannot replace SENT/DELIVERED/READ"
        }
        return copy(attempt = AttemptState.FAILED, failure = note, retryAtEpochSeconds = 0)
    }

    fun onRelayAccepted(): MessageProgress {
        if (evidence.rank >= DeliveryEvidence.SENT.rank) return this
        return copy(evidence = DeliveryEvidence.SENT, failure = null)
    }

    fun onValidatedDeliveryReceipt(): MessageProgress {
        return promote(DeliveryEvidence.DELIVERED)
    }

    fun onValidatedReadReceipt(): MessageProgress {
        return promote(DeliveryEvidence.READ)
    }

    fun onRelayQueueHint(state: RelayQueueState): MessageProgress {
        return when (state) {
            RelayQueueState.ACCEPTED -> onRelayAccepted()
            RelayQueueState.CONSUMED, RelayQueueState.UNKNOWN -> this
            RelayQueueState.EXPIRED ->
                if (evidence == DeliveryEvidence.SENT) copy(expiredWithoutReceipt = true) else this
        }
    }

    fun onExpiryWithoutAcceptance(now: Long): MessageProgress {
        check(evidence == DeliveryEvidence.NONE) { "expiry after SENT stays SENT" }
        return onFailed(
            FailureNote(
                reason = "expired",
                retryable = false,
                lastAttemptAt = now,
                ambiguousAcceptance = true,
            ),
        )
    }

    private fun promote(next: DeliveryEvidence): MessageProgress {
        if (next.rank <= evidence.rank) return this
        return copy(evidence = next, failure = null)
    }

    companion object {
        fun initial(): MessageProgress = MessageProgress()

        fun refuseRelayStatusWord(word: String) {
            val lower = word.lowercase()
            if (lower == "delivered" || lower == "read") {
                throw MessagingProtocolException("relay cannot set $lower")
            }
        }
    }
}
