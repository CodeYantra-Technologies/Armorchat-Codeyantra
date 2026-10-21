package com.codeyantra.armorchat.deal

import com.codeyantra.armorchat.deal.crypto.AndroidKeystoreDealKeyProtector
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealKeyException
import com.codeyantra.armorchat.deal.recovery.DealRecovery
import com.codeyantra.armorchat.deal.testing.SoftwareDealKeyProtector
import com.codeyantra.armorchat.deal.testing.signedFixture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DealRecoveryPortabilityTest {
    @Test
    fun recoveryPackageOpensOnAnotherDeviceWrap() {
        val fixture = signedFixture(terms = "synthetic portability")
        val secret = ByteArray(32) { 7 }
        val packageBytes = DealRecovery().seal(
            fixture.partyA,
            secret,
            fixture.keysA,
            fixture.sealed,
            fixture.deal,
            fixture.signatures,
        )
        assertTrue(packageBytes.copyOfRange(0, 4).contentEquals("ADRP".toByteArray()))
        assertFalse(packageBytes.copyOfRange(0, 4).contentEquals("ADKS".toByteArray()))
        assertFalse(packageBytes.toString(Charsets.ISO_8859_1).contains(AndroidKeystoreDealKeyProtector.ALIAS))
        val restored = DealRecovery().open(packageBytes, secret)
        assertEquals("synthetic portability", restored.deal.terms)
        val firstDevice = SoftwareDealKeyProtector(ByteArray(32) { 8 })
        val secondDevice = SoftwareDealKeyProtector(ByteArray(32) { 9 })
        val moved = secondDevice.seal(restored.keys.serializedSigning(), "deal-signing-keyset")
        assertArrayEquals(restored.keys.serializedSigning(), secondDevice.open(moved, "deal-signing-keyset"))
        assertThrows(DealCryptoException.AuthenticationFailed::class.java) {
            firstDevice.open(moved, "deal-signing-keyset")
        }
        val unchanged = packageBytes.copyOf()
        assertThrows(com.codeyantra.armorchat.deal.domain.DealRecoveryException.WrongSecret::class.java) {
            DealRecovery().open(packageBytes, ByteArray(32) { 1 })
        }
        assertTrue(unchanged.contentEquals(packageBytes))
    }

    @Test
    fun protectorCannotBeAimedAtMessagingAliases() {
        assertThrows(DealKeyException.KeystoreUnavailable::class.java) {
            AndroidKeystoreDealKeyProtector("armorchat_identity_kek_v1")
        }
        assertThrows(DealKeyException.KeystoreUnavailable::class.java) {
            AndroidKeystoreDealKeyProtector("armorchat_relay_credentials_kek_v1")
        }
        assertThrows(DealKeyException.KeystoreUnavailable::class.java) {
            AndroidKeystoreDealKeyProtector("armorchat_session_storage_wrap_v1")
        }
        assertEquals(
            "armorchat_deal_keystore_probe_v1",
            AndroidKeystoreDealKeyProtector.PROBE_ALIAS,
        )
    }
}
