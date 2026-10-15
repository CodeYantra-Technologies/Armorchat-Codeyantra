package com.codeyantra.armorchat.deal

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.security.AndroidKeystoreKek
import com.codeyantra.armorchat.crypto.SessionSealing
import com.codeyantra.armorchat.deal.crypto.AndroidKeystoreDealKeyProtector
import com.codeyantra.armorchat.deal.domain.DealRecoveryException
import com.codeyantra.armorchat.deal.recovery.DealRecovery
import com.codeyantra.armorchat.deal.remote.BackupReceipt
import com.codeyantra.armorchat.deal.remote.BackupReceiptVerifier
import com.codeyantra.armorchat.deal.remote.DealExecution
import com.codeyantra.armorchat.deal.remote.DealObjectKeys
import com.codeyantra.armorchat.deal.remote.RejectingDealAuthenticator
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.testing.RevokedDealKeyProtector
import com.codeyantra.armorchat.deal.testing.SoftwareDealKeyProtector
import com.codeyantra.armorchat.deal.testing.signedFixture
import com.codeyantra.armorchat.domain.identity.Base64Url
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealRepositoryTest {
    private val marker = "UNIQUE_TERMS_MARKER_9421_NOT_PLAINTEXT"

    @Test
    fun encryptedRowsRoundTripAndFailedRecoveryDoesNotCorruptThem() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
        try {
            val fixture = signedFixture(terms = marker)
            val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
            val repository = DealRepository(database)
            repository.saveVerified(
                fixture.deal, fixture.sealed, fixture.signatures, fixture.partyA, fixture.keysA, protector, "FULLY_SIGNED",
            )
            repository.saveVerified(
                fixture.deal, fixture.sealed, fixture.signatures, fixture.partyB, fixture.keysB, protector, "FULLY_SIGNED",
            )
            assertEquals(marker, repository.loadTerms(fixture.deal.dealId, fixture.partyA, protector))
            assertEquals(marker, repository.loadTerms(fixture.deal.dealId, fixture.partyB, protector))
            val sqlite = database.openHelper.readableDatabase
            val tables = mutableListOf<String>()
            sqlite.query("SELECT name FROM sqlite_master WHERE type='table'").use { cursor ->
                while (cursor.moveToNext()) tables += cursor.getString(0)
            }
            assertTrue(tables.contains("deals"))
            assertFalse(tables.contains("messages"))
            assertFalse(tables.contains("mesh_custody"))
            sqlite.query("SELECT sealedDocument FROM deal_versions").use { cursor ->
                assertTrue(cursor.moveToNext())
                val blob = cursor.getBlob(0)
                assertFalse(String(blob, Charsets.ISO_8859_1).contains(marker))
            }
            val before = repository.sealedDocument(fixture.deal.dealId, fixture.deal.versionId)!!
            val secret = ByteArray(32) { 3 }
            val recovery = DealRecovery()
            val packageBytes = recovery.seal(
                fixture.partyA, secret, fixture.keysA, fixture.sealed, fixture.deal, fixture.signatures,
            )
            val wrong = ByteArray(32) { 4 }
            assertThrows(DealRecoveryException.WrongSecret::class.java) {
                repository.restore(packageBytes, wrong, protector)
            }
            val corrupt = packageBytes.copyOf()
            corrupt[0] = 0
            assertThrows(DealRecoveryException.Corrupted::class.java) {
                repository.restore(corrupt, secret, protector)
            }
            assertTrue(before.contentEquals(repository.sealedDocument(fixture.deal.dealId, fixture.deal.versionId)))
            assertThrows(com.codeyantra.armorchat.deal.domain.DealKeyException.RevokedDeviceAccess::class.java) {
                repository.loadTerms(fixture.deal.dealId, fixture.partyA, RevokedDealKeyProtector())
            }
            assertEquals(marker, repository.loadTerms(fixture.deal.dealId, fixture.partyA, protector))
        } finally {
            database.close()
        }
    }
}

class DealBoundaryTest {
    @Test
    fun dealStaysOutOfProductionUiAndUsesASeparateKeystoreAlias() {
        assertFalse(DealFeature.PRODUCTION_UI_ENABLED)
        assertNotEquals(AndroidKeystoreKek.KEK_ALIAS, AndroidKeystoreDealKeyProtector.ALIAS)
        assertNotEquals(SessionSealing.ALIAS, AndroidKeystoreDealKeyProtector.ALIAS)
        assertNotEquals("armorchat_relay_credentials_kek_v1", AndroidKeystoreDealKeyProtector.ALIAS)
        assertThrows(com.codeyantra.armorchat.deal.domain.DealAccessException.Unauthenticated::class.java) {
            RejectingDealAuthenticator().authenticate("Bearer v1.local-test.token")
        }
    }

    @Test
    fun backupReceiptVerificationRejectsMissingCorruptAndCrossPartyEvidence() {
        val dealId = "d".repeat(22)
        val versionId = "v".repeat(22)
        val partyA = "a".repeat(22)
        val partyB = "b".repeat(22)
        val ciphertext = "ciphertext-bytes".toByteArray()
        val hash = com.codeyantra.armorchat.deal.domain.sha256(ciphertext)
        val receiptA = BackupReceipt(
            dealId, versionId, partyA, DealObjectKeys.backup(dealId, versionId, partyA), hash, 10,
        )
        BackupReceiptVerifier.verify(receiptA, ciphertext, objectExists = true)
        assertThrows(DealRecoveryException.MissingObject::class.java) {
            BackupReceiptVerifier.verify(receiptA, ciphertext, objectExists = false)
        }
        assertThrows(DealRecoveryException.Corrupted::class.java) {
            BackupReceiptVerifier.verify(receiptA, "other".toByteArray(), objectExists = true)
        }
        val foreignKey = BackupReceipt(
            dealId,
            versionId,
            partyA,
            DealObjectKeys.backup(dealId, versionId, partyB),
            hash,
            10,
        )
        assertThrows(com.codeyantra.armorchat.deal.domain.DealRecoveryException.Rejected::class.java) {
            BackupReceiptVerifier.verify(foreignKey, ciphertext, objectExists = true)
        }
        val receiptB = BackupReceipt(
            dealId, versionId, partyB, DealObjectKeys.backup(dealId, versionId, partyB), hash, 11,
        )
        assertThrows(DealRecoveryException.MissingObject::class.java) {
            DealExecution.confirm(
                com.codeyantra.armorchat.deal.domain.DealStatus.FULLY_SIGNED,
                listOf(receiptA),
                setOf(partyA, partyB),
                ciphertext,
            )
        }
        assertEquals(
            com.codeyantra.armorchat.deal.domain.DealStatus.EXECUTED,
            DealExecution.confirm(
                com.codeyantra.armorchat.deal.domain.DealStatus.FULLY_SIGNED,
                listOf(receiptA, receiptB),
                setOf(partyA, partyB),
                ciphertext,
            ),
        )
        assertEquals(hash.toList(), Base64Url.decode(Base64Url.encode(hash)).toList())
    }
}
