package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.core.network.RelayQueueState
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class MessageLifecycleTest {
    @Test
    fun happyPathIsMonotonic() {
        val sent = MessageProgress.initial()
            .onEncrypting()
            .onSending()
            .onRelayAccepted()
        assertThat(sent.status).isEqualTo(MessageStatus.SENT)
        val delivered = sent.onValidatedDeliveryReceipt()
        assertThat(delivered.status).isEqualTo(MessageStatus.DELIVERED)
        val read = delivered.onValidatedReadReceipt()
        assertThat(read.status).isEqualTo(MessageStatus.READ)
        assertThat(read.onValidatedDeliveryReceipt().status).isEqualTo(MessageStatus.READ)
    }

    @Test
    fun retryReturnsToQueuedWithoutEvidence() {
        val waiting = MessageProgress.initial().onEncrypting().onSending().onRetryWait(50, 1)
        assertThat(waiting.status).isEqualTo(MessageStatus.QUEUED)
        assertThat(waiting.evidence).isEqualTo(DeliveryEvidence.NONE)
    }

    @Test
    fun relayAcceptanceIsNeverDeliveredOrRead() {
        val sent = MessageProgress.initial().onEncrypting().onSending().onRelayAccepted()
        assertThat(sent.onRelayQueueHint(RelayQueueState.CONSUMED).status).isEqualTo(MessageStatus.SENT)
        assertThat(sent.onRelayQueueHint(RelayQueueState.EXPIRED).status).isEqualTo(MessageStatus.SENT)
        assertThat(sent.onRelayQueueHint(RelayQueueState.EXPIRED).expiredWithoutReceipt).isTrue()
        assertThrows(MessagingProtocolException::class.java) {
            MessageProgress.refuseRelayStatusWord("delivered")
        }
        assertThrows(MessagingProtocolException::class.java) {
            MessageProgress.refuseRelayStatusWord("read")
        }
    }

    @Test
    fun sentCannotBecomeFailed() {
        val sent = MessageProgress.initial().onEncrypting().onSending().onRelayAccepted()
        assertThrows(IllegalStateException::class.java) {
            sent.onFailed(FailureNote("offline", true, 1, true))
        }
    }

    @Test
    fun lateReceiptAdvancesFailed() {
        val failed = MessageProgress.initial()
            .onEncrypting()
            .onSending()
            .onFailed(FailureNote("retry exhausted", false, 1, true))
        assertThat(failed.status).isEqualTo(MessageStatus.FAILED)
        assertThat(failed.onValidatedDeliveryReceipt().status).isEqualTo(MessageStatus.DELIVERED)
        assertThat(failed.onValidatedReadReceipt().status).isEqualTo(MessageStatus.READ)
    }

    @Test
    fun readImpliesDelivery() {
        val sent = MessageProgress.initial().onEncrypting().onSending().onRelayAccepted()
        assertThat(sent.onValidatedReadReceipt().status).isEqualTo(MessageStatus.READ)
    }
}
