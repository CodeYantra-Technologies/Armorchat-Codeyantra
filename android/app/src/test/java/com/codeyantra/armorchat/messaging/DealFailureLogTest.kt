package com.codeyantra.armorchat.messaging

import com.codeyantra.armorchat.deal.domain.DealStateException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** Phase 5.3H: dealSession failures log a fixed code and class name only. */
class DealFailureLogTest {
    @Test
    fun rejectedSigningContextMapsToSafeCodeWithoutMessage() {
        val line = DealFailureLog.line("review", IllegalArgumentException("unsupported signing context Test Party B"))
        assertEquals("action=review result=FAIL code=signing_context_rejected type=IllegalArgumentException", line)
        assertFalse(line.contains("Test Party B"))
        assertFalse(line.contains("unsupported"))
    }

    @Test
    fun dealStateAndUnknownErrorsAreMapped() {
        assertEquals(DealFailureLog.DEAL_STATE, DealFailureLog.code(DealStateException("terms TOP-SECRET")))
        assertEquals(DealFailureLog.UNEXPECTED, DealFailureLog.code(IllegalStateException("x")))
        assertFalse(DealFailureLog.line("sign", DealStateException("terms TOP-SECRET")).contains("TOP-SECRET"))
    }

    @Test
    fun unsafeActionOrClassNamesAreNeutralised() {
        class KeystorePrivateKeyLeak : RuntimeException("seed=abc")
        val line = DealFailureLog.line("review payload=abc", KeystorePrivateKeyLeak())
        assertEquals("action=deal_action result=FAIL code=unexpected type=Exception", line)
        SafeLog.assertSafe(line)
    }
}
