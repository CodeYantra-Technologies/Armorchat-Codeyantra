package com.codeyantra.armorchat.domain.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MessageStatusTest {
    @Test
    fun reservedStatusesAreStable() {
        assertThat(MessageStatus.entries.map { it.name }).containsExactly(
            "QUEUED",
            "ENCRYPTING",
            "SENDING",
            "SENT",
            "DELIVERED",
            "READ",
            "FAILED",
        ).inOrder()
    }
}
