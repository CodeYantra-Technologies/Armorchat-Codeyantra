package com.codeyantra.armorchat.deal

import com.codeyantra.armorchat.deal.domain.DealRecoveryException
import com.codeyantra.armorchat.deal.recovery.DealRecovery
import com.codeyantra.armorchat.deal.testing.signedFixture
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class DealRecoveryTest {
    @Test
    fun eachPartyRestoresAloneAndAWrongOrCorruptSecretIsRejected() {
        val fixture = signedFixture(terms = "Independent recovery text")
        val recovery = DealRecovery()
        val secretA = ByteArray(32) { 0x41 }
        val secretB = ByteArray(32) { 0x42 }
        val packageA = recovery.seal(
            fixture.partyA, secretA, fixture.keysA, fixture.sealed, fixture.deal, fixture.signatures,
        )
        val packageB = recovery.seal(
            fixture.partyB, secretB, fixture.keysB, fixture.sealed, fixture.deal, fixture.signatures,
        )
        val restoredA = recovery.open(packageA, secretA)
        val restoredB = recovery.open(packageB, secretB)
        assertEquals(fixture.partyA, restoredA.partyId)
        assertEquals("Independent recovery text", restoredA.deal.terms)
        assertArrayEquals(fixture.keysA.signingPublic, restoredA.keys.signingPublic)
        assertEquals(fixture.partyB, restoredB.partyId)
        assertArrayEquals(fixture.keysB.encryptionPublic, restoredB.keys.encryptionPublic)
        assertArrayEquals(fixture.deal.canonicalBytes(), restoredA.canonicalBytes)
        assertThrows(DealRecoveryException.WrongSecret::class.java) { recovery.open(packageA, secretB) }
        assertThrows(DealRecoveryException.WeakSecret::class.java) { recovery.open(packageA, ByteArray(16)) }
        val truncated = packageA.copyOf(8)
        assertThrows(DealRecoveryException.Corrupted::class.java) { recovery.open(truncated, secretA) }
        val badMagic = packageA.copyOf()
        badMagic[0] = 0
        assertThrows(DealRecoveryException.Corrupted::class.java) { recovery.open(badMagic, secretA) }
    }
}
