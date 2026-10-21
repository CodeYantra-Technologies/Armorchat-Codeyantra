package com.codeyantra.armorchat.deal

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.core.security.AndroidKeystoreKek
import com.codeyantra.armorchat.crypto.SessionSealing
import com.codeyantra.armorchat.deal.crypto.AndroidKeystoreDealKeyProtector
import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.PartyWrapTarget
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIntegrityException
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealRecoveryException
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.FinalityDefect
import com.codeyantra.armorchat.deal.domain.FinalityVerdict
import com.codeyantra.armorchat.deal.domain.DealVersions
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import com.codeyantra.armorchat.deal.domain.SignAction
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
import org.junit.Assert.assertNull
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

    @Test
    fun partialSignatureSurvivesCancellationAndADuplicateCancelIsRejected() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
        try {
            val fixture = signedFixture(terms = "Original terms")
            val one = fixture.signatures.filter { it.partyId == fixture.partyA }
            val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
            val repository = DealRepository(database)
            repository.saveVerified(
                fixture.deal, fixture.sealed, one, fixture.partyA, fixture.keysA, protector, "PARTIALLY_SIGNED",
            )
            val before = database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single()
            repository.cancel(fixture.deal.dealId, protector)
            val deal = database.dealDao().findDeal(fixture.deal.dealId)!!
            val after = database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single()
            assertEquals("CANCELLED", deal.status)
            assertEquals(fixture.deal.versionId, deal.currentVersionId)
            assertEquals(before.partyId, after.partyId)
            assertTrue(before.signature.contentEquals(after.signature))
            assertFalse(database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.superseded)
            assertTrue(database.dealDao().audits(fixture.deal.dealId).any { it.action == "local_save" && it.outcome == "ok" })
            assertTrue(database.dealDao().audits(fixture.deal.dealId).any { it.action == "cancel" && it.outcome == "ok" })
            assertThrows(DealStateException::class.java) {
                repository.cancel(fixture.deal.dealId, protector)
            }
            assertEquals("CANCELLED", database.dealDao().findDeal(fixture.deal.dealId)!!.status)
            assertTrue(before.signature.contentEquals(
                database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single().signature,
            ))
            assertTrue(database.dealDao().audits(fixture.deal.dealId).any { it.action == "cancel" && it.outcome == "rejected" })
        } finally {
            database.close()
        }
    }

    @Test
    fun changeRequestKeepsTheOldSignatureAndFinalizationIgnoresReceipts() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
        try {
            val fixture = signedFixture(terms = "Original terms")
            val one = fixture.signatures.filter { it.partyId == fixture.partyA }
            val protector = SoftwareDealKeyProtector(ByteArray(32) { 11 })
            val repository = DealRepository(database)
            repository.saveVerified(
                fixture.deal, fixture.sealed, one, fixture.partyA, fixture.keysA, protector, "PARTIALLY_SIGNED",
            )
            val successor = DealVersions.revise(
                current = fixture.deal,
                currentBytes = fixture.deal.canonicalBytes(),
                signatureCount = 1,
                dealStatus = DealStatus.PARTIALLY_SIGNED,
                newVersionId = "w".repeat(22),
                newTerms = "Original terms, revised",
                revisedAtEpochMs = fixture.deal.createdAtEpochMs + 1_000,
            )
            val sealed = DealCipher.encrypt(
                dealId = successor.dealId,
                versionId = successor.versionId,
                canonical = successor.canonicalBytes(),
                recipients = listOf(
                    PartyWrapTarget(fixture.partyA, fixture.keysA.encryptionPublicHandle()),
                    PartyWrapTarget(fixture.partyB, fixture.keysB.encryptionPublicHandle()),
                ),
            )
            repository.saveChangeRequest(successor, sealed, fixture.partyA, fixture.keysA)
            repository.saveChangeRequest(successor, sealed, fixture.partyA, fixture.keysA)
            val parent = database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!
            val child = database.dealDao().findVersion(successor.dealId, successor.versionId)!!
            assertEquals("NEGOTIATING", database.dealDao().findDeal(fixture.deal.dealId)!!.status)
            assertEquals(successor.versionId, database.dealDao().findDeal(fixture.deal.dealId)!!.currentVersionId)
            assertTrue(parent.superseded)
            assertFalse(parent.immutable)
            assertFalse(child.superseded)
            assertFalse(child.immutable)
            assertEquals(parent.documentSha256, Base64Url.encode(fixture.deal.documentHash()))
            val kept = database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single()
            assertEquals(fixture.partyA, kept.partyId)
            assertEquals("SIGN", kept.action)
            assertTrue(database.dealDao().signatures(successor.dealId, successor.versionId).isEmpty())
            assertEquals(1, database.dealDao().audits(fixture.deal.dealId).count { it.action == "supersede" && it.outcome == "ok" })
            DealSignatures.verify(
                fixture.deal,
                one.single(),
                SignAction.SIGN,
                fixture.deal.dealId,
                fixture.deal.versionId,
                fixture.keysA.signingPublic,
            )
            assertThrows(DealCryptoException.WrongVersion::class.java) {
                DealSignatures.verify(
                    successor,
                    one.single(),
                    SignAction.SIGN,
                    successor.dealId,
                    successor.versionId,
                    fixture.keysA.signingPublic,
                )
            }
            assertTrue(database.dealDao().receipts(fixture.deal.dealId, fixture.deal.versionId).isEmpty())

            val finalDatabase = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
            try {
                val finalRepository = DealRepository(finalDatabase)
                val both = signedFixture(terms = "Final terms")
                finalRepository.saveVerified(
                    both.deal, both.sealed, both.signatures, both.partyA, both.keysA, protector, "FULLY_SIGNED",
                )
                val verdict = DealFinality.assess(
                    deal = both.deal,
                    signatures = both.signatures,
                    pinnedSigningKeys = both.deal.parties.associate { it.partyId to it.signingPublicKey },
                )
                assertTrue(verdict.proven)
                assertTrue(DealLifecycle.claimsFinality(DealStatus.FULLY_SIGNED))
                assertTrue(DealLifecycle.isFinalized(DealStatus.FULLY_SIGNED, verdict))
                assertFalse(
                    DealLifecycle.isFinalized(
                        DealStatus.FULLY_SIGNED,
                        FinalityVerdict.failed(FinalityDefect.MISSING_SIGNATURE),
                    ),
                )
                assertTrue(finalDatabase.dealDao().receipts(both.deal.dealId, both.deal.versionId).isEmpty())
                assertThrows(DealStateException::class.java) { finalRepository.cancel(both.deal.dealId, protector) }
                assertThrows(DealStateException::class.java) { finalRepository.expire(both.deal.dealId, protector) }
                assertThrows(ImmutableVersionException::class.java) {
                    finalRepository.recordMilestone(both.deal.dealId, "CANCELLED", "cancel", "ok", protector)
                }
                val forged = CanonicalDeal.create(
                    dealId = both.deal.dealId,
                    versionId = "w".repeat(22),
                    parentVersionId = both.deal.versionId,
                    createdAtEpochMs = both.deal.createdAtEpochMs + 1_000,
                    currency = both.deal.currency,
                    amountMinor = both.deal.amountMinor,
                    parties = both.deal.parties,
                    ancestry = listOf(both.deal.versionId),
                    terms = "changed after both signatures",
                )
                val forgedSealed = DealCipher.encrypt(
                    dealId = forged.dealId,
                    versionId = forged.versionId,
                    canonical = forged.canonicalBytes(),
                    recipients = listOf(
                        PartyWrapTarget(both.partyA, both.keysA.encryptionPublicHandle()),
                        PartyWrapTarget(both.partyB, both.keysB.encryptionPublicHandle()),
                    ),
                )
                assertThrows(ImmutableVersionException::class.java) {
                    finalRepository.saveChangeRequest(forged, forgedSealed, both.partyA, both.keysA)
                }
                assertEquals("FULLY_SIGNED", finalDatabase.dealDao().findDeal(both.deal.dealId)!!.status)
                assertEquals(2, finalDatabase.dealDao().signatures(both.deal.dealId, both.deal.versionId).size)
                assertTrue(finalDatabase.dealDao().findVersion(both.deal.dealId, both.deal.versionId)!!.immutable)
                assertTrue(finalDatabase.dealDao().audits(both.deal.dealId).any { it.action == "cancel" && it.outcome == "rejected" })
                assertTrue(finalDatabase.dealDao().audits(both.deal.dealId).any { it.action == "expire" && it.outcome == "rejected" })
            } finally {
                finalDatabase.close()
            }
        } finally {
            database.close()
        }
    }

    @Test
    fun forgedFinalStatusDoesNotProveFinalityOrRewriteTheRow() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
        try {
            val fixture = signedFixture(terms = "One signature is not final")
            val one = fixture.signatures.filter { it.partyId == fixture.partyA }
            val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
            val repository = DealRepository(database)
            repository.saveVerified(
                fixture.deal, fixture.sealed, one, fixture.partyA, fixture.keysA, protector, "PARTIALLY_SIGNED",
            )
            assertEquals("One signature is not final", repository.loadTerms(fixture.deal.dealId, fixture.partyA, protector))
            val sealedBefore = repository.sealedDocument(fixture.deal.dealId, fixture.deal.versionId)!!
            val signatureBefore = database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single().signature
            val hashBefore = database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.documentSha256
            listOf("FULLY_SIGNED", "BACKUP_PENDING", "EXECUTED").forEach { forged ->
                database.dealDao().updateDeal(fixture.deal.dealId, forged, fixture.deal.versionId, 5)
                val verdict = repository.assessStored(fixture.deal.dealId, fixture.partyA, protector)
                assertFalse(verdict.proven)
                assertEquals(FinalityDefect.MISSING_SIGNATURE, verdict.defect)
                val integrity = assertThrows(DealIntegrityException::class.java) {
                    repository.loadCanonical(fixture.deal.dealId, fixture.partyA, protector)
                }
                assertEquals(FinalityDefect.MISSING_SIGNATURE, integrity.defect)
                assertEquals(forged, database.dealDao().findDeal(fixture.deal.dealId)!!.status)
                assertEquals(fixture.deal.versionId, database.dealDao().findDeal(fixture.deal.dealId)!!.currentVersionId)
                assertTrue(signatureBefore.contentEquals(
                    database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single().signature,
                ))
                assertEquals(hashBefore, database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.documentSha256)
                assertTrue(sealedBefore.contentEquals(repository.sealedDocument(fixture.deal.dealId, fixture.deal.versionId)))
                assertFalse(database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.immutable)
                assertThrows(DealStateException::class.java) {
                    repository.cancel(fixture.deal.dealId, protector)
                }
                assertThrows(DealStateException::class.java) {
                    repository.expire(fixture.deal.dealId, protector)
                }
                assertThrows(DealIntegrityException::class.java) {
                    repository.recordMilestone(fixture.deal.dealId, "CANCELLED", "cancel", "ok", protector)
                }
                assertThrows(DealIntegrityException::class.java) {
                    repository.recordMilestone(fixture.deal.dealId, "NEGOTIATING", "supersede", "ok", protector)
                }
                assertEquals(forged, database.dealDao().findDeal(fixture.deal.dealId)!!.status)
                assertTrue(signatureBefore.contentEquals(
                    database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single().signature,
                ))
            }
            assertTrue(database.dealDao().audits(fixture.deal.dealId).any { it.action == "finality" && it.outcome == "rejected" })
            assertTrue(database.dealDao().audits(fixture.deal.dealId).none { it.action == "cancel" && it.outcome == "ok" })
            assertTrue(database.dealDao().audits(fixture.deal.dealId).none { it.outcome == "ok" && it.action != "local_save" })
        } finally {
            database.close()
        }
    }

    @Test
    fun saveVerifiedRefusesAFinalStatusThatTheSignaturesDoNotProve() {
        val context = RuntimeEnvironment.getApplication()
        val fixture = signedFixture(terms = "Do not store this forgery")
        val one = fixture.signatures.filter { it.partyId == fixture.partyA }
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
        listOf("FULLY_SIGNED", "BACKUP_PENDING", "EXECUTED").forEach { status ->
            val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
            try {
                val repository = DealRepository(database)
                val failure = assertThrows(DealIntegrityException::class.java) {
                    repository.saveVerified(
                        fixture.deal, fixture.sealed, one, fixture.partyA, fixture.keysA, protector, status,
                    )
                }
                assertEquals(FinalityDefect.MISSING_SIGNATURE, failure.defect)
                assertTrue(repository.listDeals().isEmpty())
                val duplicate = assertThrows(DealIntegrityException::class.java) {
                    repository.saveVerified(
                        fixture.deal,
                        fixture.sealed,
                        listOf(one.single(), one.single()),
                        fixture.partyA,
                        fixture.keysA,
                        protector,
                        status,
                    )
                }
                assertEquals(FinalityDefect.DUPLICATE_SIGNER, duplicate.defect)
                assertTrue(repository.listDeals().isEmpty())
            } finally {
                database.close()
            }
        }
    }

    @Test
    fun provenSignaturesCannotMoveBackwardEvenWhenTheStatusDoesNotClaimFinality() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
        try {
            val fixture = signedFixture(terms = "Both signatures, lagged status")
            val protector = SoftwareDealKeyProtector(ByteArray(32) { 9 })
            val repository = DealRepository(database)
            repository.saveVerified(
                fixture.deal, fixture.sealed, fixture.signatures, fixture.partyA, fixture.keysA, protector, "PARTIALLY_SIGNED",
            )
            val verdict = repository.assessStored(fixture.deal.dealId, fixture.partyA, protector)
            assertTrue(verdict.proven)
            assertFalse(DealLifecycle.claimsFinality(DealStatus.PARTIALLY_SIGNED))
            assertEquals("Both signatures, lagged status", repository.loadTerms(fixture.deal.dealId, fixture.partyA, protector))
            assertThrows(ImmutableVersionException::class.java) {
                repository.cancel(fixture.deal.dealId, protector)
            }
            assertThrows(ImmutableVersionException::class.java) {
                repository.expire(fixture.deal.dealId, protector)
            }
            assertThrows(ImmutableVersionException::class.java) {
                repository.recordMilestone(fixture.deal.dealId, "FULLY_SIGNED", "repair", "ok", protector)
            }
            assertEquals("PARTIALLY_SIGNED", database.dealDao().findDeal(fixture.deal.dealId)!!.status)
            assertEquals(2, database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).size)
            assertTrue(database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.immutable)
            assertTrue(database.dealDao().audits(fixture.deal.dealId).any { it.action == "finality" && it.outcome == "rejected" })
            assertTrue(database.dealDao().audits(fixture.deal.dealId).none { it.action == "cancel" && it.outcome == "ok" })
        } finally {
            database.close()
        }
    }

    @Test
    fun tamperedStoredHashKeyAndVersionStayAsIntegrityFailures() {
        val context = RuntimeEnvironment.getApplication()
        tamper(
            context,
            "UPDATE deal_versions SET documentSha256 = '${Base64Url.encode(ByteArray(32) { 1 })}' " +
                "WHERE dealId = '${"d".repeat(22)}'",
            FinalityDefect.DIFFERENT_HASH,
        )
        tamper(
            context,
            "UPDATE deal_parties SET signingPublicKey = '${Base64Url.encode(ByteArray(32) { 7 })}' " +
                "WHERE dealId = '${"d".repeat(22)}' AND partyId = '${"b".repeat(22)}'",
            FinalityDefect.MISMATCHED_KEY,
        )
        val flipped = ByteArray(64) { 3 }
        tamper(
            context,
            "UPDATE deal_signatures SET signature = X'${flipped.toSqlHex()}' " +
                "WHERE dealId = '${"d".repeat(22)}' AND partyId = '${"a".repeat(22)}' AND action = 'SIGN'",
            FinalityDefect.INVALID_SIGNATURE,
        )
        val replacement = "w".repeat(22)
        tamper(
            context,
            "UPDATE deal_versions SET versionId = '$replacement' WHERE dealId = '${"d".repeat(22)}'; " +
                "UPDATE deals SET currentVersionId = '$replacement' WHERE dealId = '${"d".repeat(22)}'",
            FinalityDefect.INCONSISTENT_VERSION,
        )
    }

    @Test
    fun aStoredVersionCannotGainOrSwapParticipantIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
        try {
            val fixture = signedFixture(terms = "Original terms")
            val one = fixture.signatures.filter { it.partyId == fixture.partyA }
            val protector = SoftwareDealKeyProtector(ByteArray(32) { 12 })
            val repository = DealRepository(database)
            repository.saveVerified(
                fixture.deal, fixture.sealed, one, fixture.partyA, fixture.keysA, protector, "PARTIALLY_SIGNED",
            )
            val beforeHash = database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.documentSha256
            val beforeSignature = database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single().signature.copyOf()
            val upgraded = unsignedVersionUpgrade(fixture.deal)
            val sealed = DealCipher.encrypt(
                dealId = upgraded.dealId,
                versionId = upgraded.versionId,
                canonical = upgraded.canonicalBytes(),
                recipients = listOf(
                    PartyWrapTarget(fixture.partyA, fixture.keysA.encryptionPublicHandle()),
                    PartyWrapTarget(fixture.partyB, fixture.keysB.encryptionPublicHandle()),
                ),
            )
            assertThrows(DealFormatException::class.java) {
                repository.saveChangeRequest(upgraded, sealed, fixture.partyA, fixture.keysA)
            }
            val row = database.dealDao().findDeal(fixture.deal.dealId)!!
            assertEquals("PARTIALLY_SIGNED", row.status)
            assertEquals(fixture.deal.versionId, row.currentVersionId)
            assertEquals(beforeHash, database.dealDao().findVersion(fixture.deal.dealId, fixture.deal.versionId)!!.documentSha256)
            assertTrue(
                beforeSignature.contentEquals(
                    database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).single().signature,
                ),
            )
            assertNull(database.dealDao().findVersion(fixture.deal.dealId, upgraded.versionId))
        } finally {
            database.close()
        }
    }

    private fun tamper(
        context: android.content.Context,
        sql: String,
        defect: FinalityDefect,
    ) {
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
        try {
            val fixture = signedFixture(terms = "Stored evidence $defect")
            val protector = SoftwareDealKeyProtector(ByteArray(32) { 4 })
            val repository = DealRepository(database)
            repository.saveVerified(
                fixture.deal, fixture.sealed, fixture.signatures, fixture.partyA, fixture.keysA, protector, "FULLY_SIGNED",
            )
            val dealId = fixture.deal.dealId
            val versionId = fixture.deal.versionId
            val beforeStatus = database.dealDao().findDeal(dealId)!!.status
            val beforeSignatures = database.dealDao().signatures(dealId, versionId).map { it.partyId to it.signature.copyOf() }
            val beforeSealed = repository.sealedDocument(dealId, versionId)!!
            val sqlite = database.openHelper.writableDatabase
            sql.split(";").map { it.trim() }.filter { it.isNotEmpty() }.forEach { statement ->
                sqlite.execSQL(statement)
            }
            val failure = assertThrows(DealIntegrityException::class.java) {
                repository.loadCanonical(dealId, fixture.partyA, protector)
            }
            assertEquals(defect, failure.defect)
            val assessed = repository.assessStored(dealId, fixture.partyA, protector)
            assertFalse(assessed.proven)
            assertEquals(defect, assessed.defect)
            assertThrows(DealIntegrityException::class.java) {
                repository.recordMilestone(dealId, "CANCELLED", "cancel", "ok", protector)
            }
            assertEquals(beforeStatus, database.dealDao().findDeal(dealId)!!.status)
            if (defect != FinalityDefect.INCONSISTENT_VERSION) {
                val after = database.dealDao().signatures(dealId, versionId).map { it.partyId to it.signature }
                assertEquals(beforeSignatures.size, after.size)
                beforeSignatures.zip(after).forEach { (left, right) ->
                    assertEquals(left.first, right.first)
                    if (defect == FinalityDefect.INVALID_SIGNATURE && left.first == fixture.partyA) {
                        assertFalse(left.second.contentEquals(right.second))
                    } else {
                        assertTrue(left.second.contentEquals(right.second))
                    }
                }
                assertTrue(beforeSealed.contentEquals(repository.sealedDocument(dealId, versionId)))
            } else {
                assertEquals(2, database.dealDao().signatures(dealId, versionId).size)
                assertTrue(beforeSealed.contentEquals(repository.sealedDocument(dealId, "w".repeat(22))))
            }
            assertTrue(database.dealDao().audits(dealId).any { it.action == "finality" && it.outcome == "rejected" })
            assertTrue(database.dealDao().audits(dealId).none { it.action == "cancel" && it.outcome == "ok" })
        } finally {
            database.close()
        }
    }

    private fun ByteArray.toSqlHex(): String = joinToString("") { "%02x".format(it) }
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
        val fixture = signedFixture()
        val proven = DealFinality.assess(
            deal = fixture.deal,
            signatures = fixture.signatures,
            pinnedSigningKeys = fixture.deal.parties.associate { it.partyId to it.signingPublicKey },
        )
        assertTrue(proven.proven)
        assertThrows(DealRecoveryException.Rejected::class.java) {
            DealExecution.confirm(
                FinalityVerdict.failed(FinalityDefect.MISSING_SIGNATURE),
                com.codeyantra.armorchat.deal.domain.DealStatus.FULLY_SIGNED,
                listOf(receiptA, receiptB),
                setOf(partyA, partyB),
                ciphertext,
            )
        }
        assertThrows(DealRecoveryException.MissingObject::class.java) {
            DealExecution.confirm(
                proven,
                com.codeyantra.armorchat.deal.domain.DealStatus.FULLY_SIGNED,
                listOf(receiptA),
                setOf(partyA, partyB),
                ciphertext,
            )
        }
        assertEquals(
            com.codeyantra.armorchat.deal.domain.DealStatus.EXECUTED,
            DealExecution.confirm(
                proven,
                com.codeyantra.armorchat.deal.domain.DealStatus.FULLY_SIGNED,
                listOf(receiptA, receiptB),
                setOf(partyA, partyB),
                ciphertext,
            ),
        )
        assertEquals(hash.toList(), Base64Url.decode(Base64Url.encode(hash)).toList())
    }
}
