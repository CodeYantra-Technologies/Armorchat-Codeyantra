package com.codeyantra.armorchat.deal

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import com.codeyantra.armorchat.deal.lab.DealLabController
import com.codeyantra.armorchat.deal.lab.DealLabDestination
import com.codeyantra.armorchat.deal.lab.InrMoney
import com.codeyantra.armorchat.deal.lab.LabAgreement
import com.codeyantra.armorchat.deal.lab.LabResult
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRepository
import com.codeyantra.armorchat.deal.testing.SoftwareDealKeyProtector
import com.codeyantra.armorchat.deal.testing.signedFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DealLabControllerTest {
    @Test
    fun draftEditingUpdatesTermsWithoutSaving() {
        val harness = harness()
        val id = harness.controller.createDraft("Alpha terms")
        assertEquals(0, harness.repository.listDeals().size)
        val before = harness.controller.snapshot(id).documentHashHex
        val updated = harness.controller.updateTerms(id, "Beta terms")
        assertTrue(updated is LabResult.Ok)
        val snap = harness.controller.snapshot(id)
        assertEquals("Beta terms", snap.terms)
        assertNotEquals(before, snap.documentHashHex)
        assertTrue(snap.actions.canEdit)
        assertEquals(0, harness.repository.listDeals().size)
        try {
            harness.controller.updateTerms(id, "")
            throw AssertionError("Empty terms were accepted.")
        } catch (e: DealFormatException) {
            assertEquals("Beta terms", harness.controller.snapshot(id).terms)
        }
        assertTrue(harness.controller.history(id).any { it.action == "draft_edited" && it.outcome == "ok" })
    }

    @Test
    fun invalidTransitionsAndSignedEditsAreRefused() {
        val harness = harness()
        val id = harness.controller.createDraft("Alpha terms")
        val execute = harness.controller.apply(id, DealEvent.EXECUTE)
        assertTrue(execute is LabResult.Rejected)
        assertFalse(execute.verified)
        assertEquals("DRAFT", harness.controller.snapshot(id).status)
        assertFalse(harness.controller.snapshot(id).actions.canExecute)
        harness.controller.apply(id, DealEvent.PROPOSE)
        assertTrue(harness.controller.signLocal(id) is LabResult.Ok)
        try {
            harness.controller.updateTerms(id, "Changed terms")
            throw AssertionError("A signed draft was edited.")
        } catch (e: ImmutableVersionException) {
            assertEquals("Alpha terms", harness.controller.snapshot(id).terms)
        }
        assertFalse(harness.controller.snapshot(id).actions.canEdit)
        val duplicate = harness.controller.signLocal(id)
        assertTrue(duplicate is LabResult.Rejected)
        assertEquals(1, harness.controller.snapshot(id).let { if (it.localSigned) 1 else 0 })
        assertTrue(harness.controller.history(id).any { it.action == "transition_rejected" && it.outcome == "rejected" })
        assertTrue(harness.controller.history(id).any { it.action == "draft_edit_rejected" })
    }

    @Test
    fun signaturesAreRealAndTheSavedRowIsCiphertext() {
        val harness = harness()
        val marker = "LAB_TERMS_MARKER_77"
        val id = harness.controller.createDraft(marker)
        val early = harness.controller.signLocal(id)
        assertTrue(early is LabResult.Rejected)
        assertFalse(harness.controller.snapshot(id).localSigned)
        harness.controller.apply(id, DealEvent.PROPOSE)
        val local = harness.controller.signLocal(id)
        assertTrue(local is LabResult.Ok)
        assertTrue(local.message.contains("local synthetic"))
        assertFalse(local.message.contains("remote party was contacted"))
        assertFalse(harness.controller.snapshot(id).persisted)
        val simulated = harness.controller.signSimulatedCounterparty(id)
        assertTrue(simulated is LabResult.Ok)
        assertTrue(simulated.message.contains("simulated counterparty"))
        assertTrue(simulated.message.contains("No remote party"))
        val snap = harness.controller.snapshot(id)
        assertTrue(snap.localSigned)
        assertTrue(snap.simulatedSigned)
        assertTrue(snap.persisted)
        assertEquals("FULLY_SIGNED", snap.status)
        assertTrue(snap.finalityProven)
        assertEquals("Both parties have signed", snap.statusLabel)
        assertEquals(marker, harness.repository.loadTerms(id, snap.localPartyId, harness.protector))
        val sqlite = harness.database.openHelper.readableDatabase
        sqlite.query("SELECT sealedDocument FROM deal_versions").use { cursor ->
            assertTrue(cursor.moveToNext())
            val blob = cursor.getBlob(0)
            assertFalse(String(blob, Charsets.ISO_8859_1).contains(marker))
        }
        assertFalse(snap.actions.canExecute)
        assertTrue(harness.controller.history(id).any { it.detail == DealLabController.LOCAL_SIGN })
        assertTrue(harness.controller.history(id).any { it.detail == DealLabController.SIMULATED_SIGN })
    }

    @Test
    fun recoveryFailureDoesNotClaimAVerifiedBackup() {
        val harness = harness()
        val id = signed(harness.controller)
        val secret = ByteArray(32) { 7 }
        val wrong = ByteArray(32) { 8 }
        val weak = harness.controller.sealLocalPackage(id, ByteArray(16) { 1 })
        assertFalse(weak.verified)
        assertFalse(weak.message.contains("encrypted backup verified"))
        assertFalse(harness.controller.snapshot(id).backupPrepared)
        val sealed = harness.controller.sealLocalPackage(id, secret)
        assertFalse(sealed.verified)
        assertTrue(sealed.message.contains("Not verified"))
        val before = harness.controller.unverifiedPackage(id)!!
        val failed = harness.controller.verifyLocalPackage(id, wrong)
        assertTrue(failed is LabResult.Rejected)
        assertFalse(failed.verified)
        assertFalse(failed.message.contains("encrypted backup verified"))
        assertTrue(failed.message.contains("not verified"))
        assertTrue(before.contentEquals(harness.controller.unverifiedPackage(id)))
        assertEquals("FULLY_SIGNED", harness.controller.snapshot(id).status)
        assertFalse(harness.controller.snapshot(id).backupVerified)
        val verified = harness.controller.verifyLocalPackage(id, secret)
        assertTrue(verified.verified)
        assertEquals(DealLabController.VERIFIED_BACKUP, verified.message)
        assertTrue(harness.controller.snapshot(id).backupVerified)
        assertTrue(harness.controller.snapshot(id).finalityProven)
        assertEquals("BACKUP_PENDING", harness.controller.snapshot(id).status)
        val blocked = harness.controller.apply(id, DealEvent.EXECUTE)
        assertTrue(blocked is LabResult.Rejected)
        assertEquals("BACKUP_PENDING", harness.controller.snapshot(id).status)
        assertFalse(harness.controller.snapshot(id).actions.canExecute)
        assertTrue(harness.controller.history(id).any { it.action == "backup_verify_failed" && it.outcome == "rejected" })
        assertTrue(harness.controller.history(id).any { it.action == "backup_verified" && it.outcome == "ok" })
    }

    @Test
    fun incompleteAgreementCannotBeSigned() {
        val harness = harness()
        val id = harness.controller.beginBlank()
        val snap = harness.controller.snapshot(id)
        assertFalse(snap.complete)
        assertNull(snap.documentHashHex)
        assertFalse(snap.actions.canPropose)
        assertFalse(snap.actions.canSignLocal)
        assertFalse(snap.actions.canSignSimulated)
        assertTrue(snap.problems.any { it.contains("title") })
        assertTrue(snap.problems.any { it.contains("Party A") })
        assertTrue(snap.problems.any { it.contains("Party B") })
        assertTrue(snap.problems.any { it.contains("rupees") })
        assertTrue(snap.problems.any { it.contains("deadline") })
        assertTrue(snap.problems.any { it.contains("terms") })
        assertFalse(snap.problems.any { it.contains("Payment") })
        val proposed = harness.controller.apply(id, DealEvent.PROPOSE)
        assertTrue(proposed is LabResult.Rejected)
        assertEquals("DRAFT", harness.controller.snapshot(id).status)
        val signed = harness.controller.signLocal(id)
        assertTrue(signed is LabResult.Rejected)
        assertFalse(harness.controller.snapshot(id).localSigned)
        assertFalse(harness.controller.snapshot(id).simulatedSigned)
    }

    @Test
    fun amountDeadlineAndFieldChangesAreCanonical() {
        assertEquals(150050L, InrMoney.paiseOrNull("1500.50"))
        assertEquals(150050L, InrMoney.paiseOrNull("1500.5"))
        assertEquals(150000L, InrMoney.paiseOrNull("1500"))
        assertEquals(0L, InrMoney.paiseOrNull("0"))
        assertNull(InrMoney.paiseOrNull("1500.500"))
        assertNull(InrMoney.paiseOrNull("1500.501"))
        assertNull(InrMoney.paiseOrNull("-1"))
        val harness = harness()
        val id = harness.controller.createDraft("Alpha terms")
        val document = harness.controller.openedDocument(id) ?: throw AssertionError("Complete draft has no document.")
        assertEquals(150050L, document.amountMinor)
        assertEquals("INR", document.currency)
        assertEquals(2, document.canonicalVersion)
        assertEquals("2026-12-31", document.details?.deadlineDate)
        val utf8 = String(document.canonicalBytes(), Charsets.UTF_8)
        listOf("Synthetic studio rental", "Client", "Studio", "Local synthetic", "Simulated party", "Alpha terms", "Pay on delivery").forEach {
            assertTrue(utf8.contains(it))
        }
        assertEquals(document.terms, CanonicalEncoding.parse(document.canonicalBytes()).terms)
        val hash = harness.controller.snapshot(id).documentHashHex
        val badDate = harness.controller.updateForm(
            id,
            harness.controller.snapshot(id).form.copy(deadline = "2026-02-31"),
        )
        assertTrue(badDate is LabResult.Rejected)
        assertEquals("2026-12-31", harness.controller.snapshot(id).form.deadline)
        assertEquals(hash, harness.controller.snapshot(id).documentHashHex)
        val retitled = harness.controller.updateForm(
            id,
            harness.controller.snapshot(id).form.copy(title = "Other synthetic title"),
        )
        assertTrue(retitled is LabResult.Ok)
        assertNotEquals(hash, harness.controller.snapshot(id).documentHashHex)
        val priced = harness.controller.updateForm(
            id,
            harness.controller.snapshot(id).form.copy(amountText = "1500"),
        )
        assertTrue(priced is LabResult.Ok)
        assertEquals(150000L, harness.controller.openedDocument(id)?.amountMinor)
        val half = harness.controller.updateForm(
            id,
            harness.controller.snapshot(id).form.copy(amountText = "1500.5"),
        )
        assertTrue(half is LabResult.Ok)
        assertEquals(150050L, harness.controller.openedDocument(id)?.amountMinor)
        val optional = harness.controller.updateForm(
            id,
            harness.controller.snapshot(id).form.copy(paymentConditions = ""),
        )
        assertTrue(optional is LabResult.Ok)
        assertEquals("", harness.controller.openedDocument(id)?.details?.paymentConditions)
        assertTrue(LabAgreement.problems(LabAgreement.sample("Alpha terms")).isEmpty())
    }

    @Test
    fun proposeDoesNotSignAndEditsReturnToDraft() {
        val harness = harness()
        val id = harness.controller.createDraft("Alpha terms")
        val proposed = harness.controller.apply(id, DealEvent.PROPOSE)
        assertTrue(proposed is LabResult.Ok)
        val ready = harness.controller.snapshot(id)
        assertEquals("PROPOSED", ready.status)
        assertEquals("Ready to sign", ready.statusLabel)
        assertFalse(ready.localSigned)
        assertFalse(ready.simulatedSigned)
        assertTrue(harness.controller.history(id).none { it.action == "local_signature" || it.action == "simulated_signature" })
        val edited = harness.controller.updateTerms(id, "Beta terms")
        assertTrue(edited is LabResult.Ok)
        assertEquals("DRAFT", harness.controller.snapshot(id).status)
        val early = harness.controller.signLocal(id)
        assertTrue(early is LabResult.Rejected)
        assertFalse(harness.controller.snapshot(id).localSigned)
        harness.controller.apply(id, DealEvent.PROPOSE)
        assertTrue(harness.controller.signLocal(id) is LabResult.Ok)
        val signedHash = harness.controller.snapshot(id).documentHashHex
        try {
            harness.controller.updateForm(id, harness.controller.snapshot(id).form.copy(title = "Changed after signing"))
            throw AssertionError("A signed agreement was edited.")
        } catch (e: ImmutableVersionException) {
            assertEquals(signedHash, harness.controller.snapshot(id).documentHashHex)
            assertTrue(harness.controller.snapshot(id).localSigned)
            assertEquals(signedHash, harness.controller.openedDocument(id)?.documentHash()?.joinToString("") { "%02x".format(it) })
        }
        assertEquals(harness.controller.history(id).first { it.action == "local_signature" }.createdAtEpochMs, harness.controller.snapshot(id).localSignedAtEpochMs)
    }

    @Test
    fun lifecycleStopsBeforeExecution() {
        val harness = harness()
        val id = harness.controller.createDraft("Alpha terms")
        assertTrue(harness.controller.apply(id, DealEvent.PROPOSE) is LabResult.Ok)
        assertTrue(harness.controller.signLocal(id) is LabResult.Ok)
        assertEquals("PARTIALLY_SIGNED", harness.controller.snapshot(id).status)
        assertEquals("One party has signed", harness.controller.snapshot(id).statusLabel)
        assertTrue(harness.controller.signSimulatedCounterparty(id) is LabResult.Ok)
        assertEquals("FULLY_SIGNED", harness.controller.snapshot(id).status)
        assertEquals("Both parties have signed", harness.controller.snapshot(id).statusLabel)
        assertNotEquals(null, harness.controller.snapshot(id).simulatedSignedAtEpochMs)
        val secret = ByteArray(32) { 9 }
        assertTrue(harness.controller.sealLocalPackage(id, secret) is LabResult.Ok)
        val verified = harness.controller.verifyLocalPackage(id, secret)
        assertTrue(verified.verified)
        assertEquals("BACKUP_PENDING", harness.controller.snapshot(id).status)
        assertEquals("Local backup verified", harness.controller.snapshot(id).statusLabel)
        val executed = harness.controller.apply(id, DealEvent.EXECUTE)
        assertTrue(executed is LabResult.Rejected)
        assertFalse(executed.verified)
        assertEquals("BACKUP_PENDING", harness.controller.snapshot(id).status)
        assertFalse(harness.controller.snapshot(id).actions.canExecute)
        val stored = harness.repository.loadCanonical(id, harness.controller.snapshot(id).localPartyId, harness.protector)
        assertEquals("Synthetic studio rental", stored.details?.title)
        assertEquals(150050L, stored.amountMinor)
        val cancelledId = harness.controller.createDraft("Cancel me")
        assertTrue(harness.controller.apply(cancelledId, DealEvent.CANCEL) is LabResult.Ok)
        assertEquals("CANCELLED", harness.controller.snapshot(cancelledId).status)
        assertFalse(harness.controller.snapshot(cancelledId).actions.canEdit)
        assertTrue(harness.controller.signLocal(cancelledId) is LabResult.Rejected)
    }

    @Test
    fun debugEntryIsLabeledAndProductionUiStaysOff() {
        assertEquals("Armor Deal Lab", DealLabDestination.TITLE)
        assertEquals("debug/deal_lab", DealLabDestination.ROUTE)
        assertTrue(DealLabDestination.ROUTE.startsWith("debug/"))
        assertEquals("LOCAL DEMO — NOT A LEGAL ESIGN OR CLOUD BACKUP", DealLabDestination.BANNER)
        assertFalse(DealFeature.PRODUCTION_UI_ENABLED)
        assertTrue(DealLabGate.visible(debugBuild = true, productionUiEnabled = false))
        assertFalse(DealLabGate.visible(debugBuild = false, productionUiEnabled = false))
    }

    @Test
    fun forgedStoredFinalStatusIsNotDisplayedAsVerified() {
        val harness = harness()
        val fixture = signedFixture(terms = "Stored status is not proof")
        val one = fixture.signatures.filter { it.partyId == fixture.partyA }
        harness.repository.saveVerified(
            fixture.deal,
            fixture.sealed,
            one,
            fixture.partyA,
            fixture.keysA,
            harness.protector,
            "PARTIALLY_SIGNED",
        )
        val auditsBefore = harness.repository.audits(fixture.deal.dealId).size
        listOf("FULLY_SIGNED", "BACKUP_PENDING", "EXECUTED").forEach { forged ->
            harness.database.dealDao().updateDeal(fixture.deal.dealId, forged, fixture.deal.versionId, 9)
            val summary = harness.controller.summaries().single { it.dealId == fixture.deal.dealId }
            assertEquals(forged, summary.status)
            assertEquals("Finality not proven", summary.statusLabel)
            assertFalse(summary.backupVerified)
            val opened = harness.controller.openStored(fixture.deal.dealId)
            assertTrue(opened is LabResult.Rejected)
            assertFalse(opened.verified)
            assertTrue(opened.message.contains("does not verify"))
            assertEquals(forged, harness.database.dealDao().findDeal(fixture.deal.dealId)!!.status)
            assertEquals(1, harness.database.dealDao().signatures(fixture.deal.dealId, fixture.deal.versionId).size)
        }
        assertEquals(auditsBefore, harness.repository.audits(fixture.deal.dealId).size)
    }

    private fun signed(controller: DealLabController): String {
        val id = controller.createDraft("Synthetic recovery terms")
        controller.apply(id, DealEvent.PROPOSE)
        controller.signLocal(id)
        controller.signSimulatedCounterparty(id)
        return id
    }

    private fun harness(): Harness {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val clock = { 1_700_000_000_000L }
        val protector = SoftwareDealKeyProtector(ByteArray(32) { 1 })
        val repository = DealRepository(database, clock = clock)
        return Harness(
            database = database,
            repository = repository,
            controller = DealLabController(repository, protector, clock = clock),
            protector = protector,
        )
    }

    private class Harness(
        val database: DealDatabase,
        val repository: DealRepository,
        val controller: DealLabController,
        val protector: SoftwareDealKeyProtector,
    )
}
