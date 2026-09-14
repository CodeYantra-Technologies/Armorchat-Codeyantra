package com.codeyantra.armorchat.core.recovery

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IdentityBackupCryptoTest {
    private val crypto = IdentityBackupCrypto()

    @Test
    fun roundTrip_preservesPayload() {
        val payload = IdentityBackupPayload(
            serializedIdentityKeyset = ByteArray(64) { it.toByte() },
            identityCreatedAtEpochMs = 1234L,
            cryptoVersion = 1,
        )

        val backup = crypto.encrypt(payload, "correct horse battery".toCharArray())
        val restored = crypto.decrypt(backup, "correct horse battery".toCharArray())

        assertThat(restored).isEqualTo(payload)
    }

    @Test
    fun wrongPasswordFailsWithoutPlaintext() {
        val payload = IdentityBackupPayload(
            serializedIdentityKeyset = ByteArray(64) { it.toByte() },
            identityCreatedAtEpochMs = 1234L,
            cryptoVersion = 1,
        )
        val backup = crypto.encrypt(payload, "correct horse battery".toCharArray())

        try {
            crypto.decrypt(backup, "wrong horse battery".toCharArray())
            throw AssertionError("expected IdentityBackupException")
        } catch (e: IdentityBackupException.InvalidPassword) {
            assertThat(e.message).contains("incorrect")
        }
    }
}
