package com.codeyantra.armorchat.deal

import android.app.Application
import androidx.room.Room
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.DealTink
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.ContractAnnex
import com.codeyantra.armorchat.deal.domain.DealConflictException
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealPartyDraft
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.ForeignPartyException
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.SigningEvent
import com.codeyantra.armorchat.deal.domain.SigningProgress
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.SigningStage
import com.codeyantra.armorchat.deal.domain.SigningUi
import com.codeyantra.armorchat.deal.domain.SigningWorkflow
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.draft.DealDrafts
import com.codeyantra.armorchat.deal.storage.DealDatabase
import com.codeyantra.armorchat.deal.storage.DealRow
import com.codeyantra.armorchat.deal.storage.SigningProgressStore
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

class SigningWorkflowTest {
    private val fixture = SigningFixture()

    @Test
    fun happyPathMapsStagesOntoExistingStatuses() {
        assertFalse(com.codeyantra.armorchat.deal.DealFeature.PRODUCTION_UI_ENABLED)
        val stages = fixture.path()
        val draft = stages.getValue(SigningStage.LOCAL_DRAFT)
        assertEquals(DealDrafts.STATUS_LOCAL, draft.stage.name)
        assertEquals(DealStatus.DRAFT, draft.storedStatus)
        assertEquals(0, draft.signatureCount)
        assertFalse(draft.verdict().proven)

        val proposed = stages.getValue(SigningStage.PROPOSED)
        assertEquals(DealStatus.PROPOSED, proposed.storedStatus)
        assertSame(proposed, SigningWorkflow.acknowledge(proposed))
        assertSame(proposed, SigningWorkflow.recordDelivery(proposed))
        assertEquals(0, proposed.signatureCount)

        val review = stages.getValue(SigningStage.AWAITING_PARTY_B_REVIEW)
        assertEquals(DealStatus.PROPOSED, review.storedStatus)
        val names = stages.getValue(SigningStage.AWAITING_NAME_CONFIRMATIONS)
        assertEquals(DealStatus.PROPOSED, names.storedStatus)
        assertEquals(1, names.nameConfirmations.size)
        assertEquals(0, names.signatureCount)

        val ready = stages.getValue(SigningStage.READY_TO_SIGN)
        assertEquals(DealStatus.PROPOSED, ready.storedStatus)
        assertEquals(2, ready.nameConfirmations.size)
        assertEquals(0, ready.signatureCount)
        assertFalse(ready.verdict().proven)
        assertSame(ready, SigningWorkflow.acknowledge(ready))
        assertSame(ready, SigningWorkflow.recordDelivery(ready))

        val partial = stages.getValue(SigningStage.PARTIALLY_SIGNED)
        assertEquals(DealStatus.PARTIALLY_SIGNED, partial.storedStatus)
        assertEquals(1, partial.signatureCount)
        assertFalse(partial.verdict().proven)
        assertTrue(partial.signatures.all { it.action == SignAction.SIGN })

        val full = stages.getValue(SigningStage.FULLY_SIGNED)
        assertEquals(DealStatus.FULLY_SIGNED, full.storedStatus)
        assertEquals(2, full.signatureCount)
        assertEquals(2, full.signatures.map { it.partyId }.distinct().size)
        assertTrue(full.verdict().proven)
        assertTrue(DealLifecycle.isFinalized(full.storedStatus, full.verdict()))
        assertSame(full, SigningWorkflow.acknowledge(full))
        assertSame(full, SigningWorkflow.recordDelivery(full))
        assertSame(full, SigningWorkflow.sign(full, fixture.partyA, fixture.keysA.signingHandle()))
        assertSame(full, SigningWorkflow.confirmOwnName(full, fixture.partyA, fixture.signerA, fixture.nameA))

        val text = full.toString()
        assertFalse(text.contains(fixture.nameA))
        assertFalse(text.contains(fixture.nameB))
        assertFalse(text.contains(fixture.terms))
        assertFalse(text.contains("Shared purpose"))
    }

    @Test
    fun transitionTableRejectsEventsTheStageDoesNotAllow() {
        val stages = fixture.path()
        for (stage in SigningStage.entries) {
            for (event in SigningEvent.entries) {
                val permitted = ALLOWED.getValue(stage).contains(event)
                assertEquals("$stage / $event", permitted, SigningWorkflow.allows(stage, event))
                if (permitted) continue
                val progress = stages.getValue(stage)
                try {
                    attempt(progress, event)
                    fail("$stage / $event was accepted")
                } catch (expected: DealStateException) {
                    assertEquals(stage, progress.stage)
                    assertEquals(stages.getValue(stage).signatureCount, progress.signatureCount)
                } catch (unexpected: RuntimeException) {
                    throw AssertionError("$stage / $event", unexpected)
                }
            }
        }
    }

    @Test
    fun existingLifecycleStillAcceptsPartialSignFromProposed() {
        assertEquals(
            DealStatus.PARTIALLY_SIGNED,
            DealLifecycle.transition(DealStatus.PROPOSED, DealEvent.PARTIAL_SIGN),
        )
        assertEquals(
            DealStatus.FULLY_SIGNED,
            DealLifecycle.transition(DealStatus.PARTIALLY_SIGNED, DealEvent.FULL_SIGN),
        )
        assertThrows(DealStateException::class.java) {
            DealLifecycle.transition(DealStatus.FULLY_SIGNED, DealEvent.CANCEL)
        }
        val proposed = fixture.proposed()
        assertThrows(DealStateException::class.java) {
            SigningWorkflow.sign(proposed, fixture.partyA, fixture.keysA.signingHandle())
        }
        assertEquals(0, proposed.signatureCount)
    }

    @Test
    fun retriesAreIdempotent() {
        val proposed = fixture.proposed()
        val reviewed = SigningWorkflow.recordPartyBReview(proposed, fixture.signerB)
        assertSame(reviewed, SigningWorkflow.recordPartyBReview(reviewed, fixture.signerB))
        assertEquals(SigningStage.AWAITING_PARTY_B_REVIEW, reviewed.stage)

        val firstName = SigningWorkflow.confirmOwnName(reviewed, fixture.partyA, fixture.signerA, fixture.nameA)
        assertSame(firstName, SigningWorkflow.confirmOwnName(firstName, fixture.partyA, fixture.signerA, fixture.nameA))
        assertEquals(1, firstName.nameConfirmations.size)
        assertEquals(0, firstName.signatureCount)

        val ready = SigningWorkflow.confirmOwnName(firstName, fixture.partyB, fixture.signerB, fixture.nameB)
        assertSame(ready, SigningWorkflow.confirmOwnName(ready, fixture.partyB, fixture.signerB, fixture.nameB))
        assertEquals(SigningStage.READY_TO_SIGN, ready.stage)
        assertEquals(0, ready.signatureCount)

        val partial = SigningWorkflow.sign(ready, fixture.partyA, fixture.keysA.signingHandle())
        assertSame(partial, SigningWorkflow.sign(partial, fixture.partyA, fixture.keysA.signingHandle()))
        assertEquals(1, partial.signatureCount)
        assertSame(partial, SigningWorkflow.acknowledge(partial))
        assertSame(partial, SigningWorkflow.recordDelivery(partial))
    }

    @Test
    fun outOfOrderEventsAreRejected() {
        val draft = SigningWorkflow.start(fixture.namedDeal())
        assertThrows(DealStateException::class.java) {
            SigningWorkflow.recordPartyBReview(draft, fixture.signerB)
        }
        assertThrows(DealStateException::class.java) {
            SigningWorkflow.confirmOwnName(draft, fixture.partyA, fixture.signerA, fixture.nameA)
        }
        assertThrows(DealStateException::class.java) {
            SigningWorkflow.sign(draft, fixture.partyA, fixture.keysA.signingHandle())
        }
        val proposed = SigningWorkflow.sendProposal(draft)
        assertThrows(DealStateException::class.java) { SigningWorkflow.sendProposal(proposed) }
        assertThrows(DealStateException::class.java) {
            SigningWorkflow.confirmOwnName(proposed, fixture.partyA, fixture.signerA, fixture.nameA)
        }
        assertThrows(DealStateException::class.java) {
            SigningWorkflow.sign(proposed, fixture.partyB, fixture.keysB.signingHandle())
        }
        val waiting = SigningWorkflow.confirmOwnName(
            SigningWorkflow.recordPartyBReview(proposed, fixture.signerB),
            fixture.partyA,
            fixture.signerA,
            fixture.nameA,
        )
        assertEquals(SigningStage.AWAITING_NAME_CONFIRMATIONS, waiting.stage)
        assertThrows(DealStateException::class.java) {
            SigningWorkflow.sign(waiting, fixture.partyA, fixture.keysA.signingHandle())
        }
        assertEquals(0, waiting.signatureCount)
        assertEquals(SigningStage.LOCAL_DRAFT, draft.stage)
        assertEquals(SigningStage.PROPOSED, proposed.stage)
    }

    @Test
    fun forgedStaleAndUnauthorizedSignaturesAreRejected() {
        val ready = fixture.ready()
        val produced = DealSignatures.sign(fixture.keysA.signingHandle(), ready.deal, fixture.partyA, SignAction.SIGN)
        val mutated = produced.signature.copyOf()
        mutated[0] = (mutated[0].toInt() xor 0x01).toByte()
        assertThrows(DealCryptoException.InvalidSignature::class.java) {
            SigningWorkflow.submitSignature(
                ready,
                DealSignature(
                    produced.dealId,
                    produced.versionId,
                    produced.partyId,
                    SignAction.SIGN,
                    mutated,
                    produced.signerPublicKey.copyOf(),
                ),
            )
        }
        assertThrows(DealCryptoException.WrongSigner::class.java) {
            SigningWorkflow.sign(ready, fixture.partyA, fixture.keysB.signingHandle())
        }
        assertThrows(DealCryptoException.WrongSigner::class.java) {
            SigningWorkflow.submitSignature(
                ready,
                DealSignature(
                    ready.deal.dealId,
                    ready.deal.versionId,
                    "c".repeat(22),
                    SignAction.SIGN,
                    ByteArray(64) { 9 },
                    ByteArray(32) { 8 },
                ),
            )
        }
        assertThrows(DealCryptoException.WrongDeal::class.java) {
            SigningWorkflow.submitSignature(
                ready,
                DealSignature(
                    "e".repeat(22),
                    ready.deal.versionId,
                    fixture.partyA,
                    SignAction.SIGN,
                    ByteArray(64) { 4 },
                    fixture.keysA.signingPublic.copyOf(),
                ),
            )
        }
        assertThrows(DealCryptoException.WrongAction::class.java) {
            SigningWorkflow.submitSignature(
                ready,
                DealSignature(
                    ready.deal.dealId,
                    ready.deal.versionId,
                    fixture.partyA,
                    SignAction.REJECT,
                    ByteArray(64) { 3 },
                    fixture.keysA.signingPublic.copyOf(),
                ),
            )
        }
        val staleHash = ByteArray(32) { 7 }
        val staleName = fixture.signerA.sign(
            DealIdentity.nameOnVersionPayload(
                ready.deal.dealId,
                ready.deal.versionId,
                PartyRole.PROPOSER,
                fixture.participantA,
                fixture.nameA,
                staleHash,
            ),
        )
        assertThrows(DealCryptoException.InvalidSignature::class.java) {
            SigningWorkflow.submitNameConfirmation(ready, fixture.partyA, staleName)
        }
        assertEquals(0, ready.signatureCount)
        assertEquals(2, ready.nameConfirmations.size)
        assertEquals(SigningStage.READY_TO_SIGN, ready.stage)
    }

    @Test
    fun aPartyCannotConfirmOrSignForTheOtherParty() {
        val proposed = fixture.proposed()
        assertThrows(ForeignPartyException::class.java) {
            SigningWorkflow.recordPartyBReview(proposed, fixture.signerA)
        }
        val review = SigningWorkflow.recordPartyBReview(proposed, fixture.signerB)
        val before = fixture.signerA.signatures
        assertThrows(ForeignPartyException::class.java) {
            SigningWorkflow.confirmOwnName(review, fixture.partyB, fixture.signerA, fixture.nameB)
        }
        assertEquals(before, fixture.signerA.signatures)
        assertTrue(review.nameConfirmations.isEmpty())
        val ready = fixture.ready()
        assertThrows(DealConflictException::class.java) {
            SigningWorkflow.confirmOwnName(ready, fixture.partyA, fixture.signerA, "Someone Else")
        }
        assertEquals(2, ready.nameConfirmations.size)
        assertEquals(0, ready.signatureCount)
    }

    @Test
    fun nameConfirmationsMustMatchOneHashAndEitherPartyMaySignFirst() {
        var progress = SigningWorkflow.start(fixture.unnamedDeal())
        progress = SigningWorkflow.sendProposal(progress)
        progress = SigningWorkflow.recordPartyBReview(progress, fixture.signerB)
        progress = SigningWorkflow.confirmOwnName(progress, fixture.partyA, fixture.signerA, fixture.nameA)
        val hashAfterFirstName = progress.deal.documentHash().copyOf()
        assertEquals(SigningStage.AWAITING_NAME_CONFIRMATIONS, progress.stage)
        progress = SigningWorkflow.confirmOwnName(progress, fixture.partyB, fixture.signerB, fixture.nameB)
        assertFalse(hashAfterFirstName.contentEquals(progress.deal.documentHash()))
        assertEquals(SigningStage.AWAITING_NAME_CONFIRMATIONS, progress.stage)
        assertEquals(fixture.partyB, progress.nameConfirmations.single().partyId)
        progress = SigningWorkflow.confirmOwnName(progress, fixture.partyA, fixture.signerA, fixture.nameA)
        assertEquals(SigningStage.READY_TO_SIGN, progress.stage)
        assertEquals(2, progress.nameConfirmations.size)
        assertEquals(0, progress.signatureCount)
        assertFalse(progress.verdict().proven)

        var otherOrder = fixture.ready()
        otherOrder = SigningWorkflow.sign(otherOrder, fixture.partyB, fixture.keysB.signingHandle())
        assertEquals(SigningStage.PARTIALLY_SIGNED, otherOrder.stage)
        assertEquals(fixture.partyB, otherOrder.signatures.single().partyId)
        otherOrder = SigningWorkflow.sign(otherOrder, fixture.partyA, fixture.keysA.signingHandle())
        assertEquals(SigningStage.FULLY_SIGNED, otherOrder.stage)
        assertTrue(otherOrder.verdict().proven)
    }

    @Test
    fun revisionKeepsHistoryAndFinalityRejectsLaterMutation() {
        val partial = fixture.partial()
        val before = partial.deal.canonicalBytes().copyOf()
        val revised = SigningWorkflow.revise(
            partial,
            before,
            "w".repeat(22),
            "Revised terms",
            fixture.createdAt + 86_400_000,
        )
        assertArrayEquals(before, partial.deal.canonicalBytes())
        assertEquals(1, partial.signatureCount)
        assertEquals(partial.deal.versionId, partial.signatures.single().versionId)
        assertEquals(SigningStage.PROPOSED, revised.stage)
        assertEquals(DealStatus.NEGOTIATING, revised.storedStatus)
        assertEquals(0, revised.signatureCount)
        assertTrue(revised.nameConfirmations.isEmpty())
        assertEquals(partial.deal.versionId, revised.deal.parentVersionId)
        assertFalse(revised.deal.canonicalBytes().contentEquals(before))

        var successor = SigningWorkflow.recordPartyBReview(revised, fixture.signerB)
        successor = SigningWorkflow.confirmOwnName(successor, fixture.partyA, fixture.signerA, fixture.nameA)
        successor = SigningWorkflow.confirmOwnName(successor, fixture.partyB, fixture.signerB, fixture.nameB)
        assertEquals(SigningStage.READY_TO_SIGN, successor.stage)
        assertEquals(DealStatus.NEGOTIATING, successor.storedStatus)
        assertThrows(DealCryptoException.WrongVersion::class.java) {
            SigningWorkflow.submitSignature(successor, partial.signatures.single())
        }
        assertEquals(0, successor.signatureCount)
        successor = SigningWorkflow.sign(successor, fixture.partyA, fixture.keysA.signingHandle())
        successor = SigningWorkflow.sign(successor, fixture.partyB, fixture.keysB.signingHandle())
        assertEquals(SigningStage.FULLY_SIGNED, successor.stage)
        assertTrue(successor.verdict().proven)
        assertEquals(1, partial.signatureCount)

        assertThrows(DealStateException::class.java) {
            SigningWorkflow.revise(successor, successor.deal.canonicalBytes(), "x".repeat(22), "Again", fixture.createdAt + 172_800_000)
        }
        assertThrows(DealStateException::class.java) { SigningWorkflow.cancel(successor) }
        assertThrows(DealStateException::class.java) { SigningWorkflow.expire(successor) }
        assertThrows(DealStateException::class.java) { SigningWorkflow.reject(successor) }
        assertThrows(DealCryptoException.WrongAction::class.java) {
            SigningWorkflow.submitSignature(
                successor,
                DealSignature(
                    successor.deal.dealId,
                    successor.deal.versionId,
                    fixture.partyA,
                    SignAction.REJECT,
                    ByteArray(64) { 6 },
                    fixture.keysA.signingPublic.copyOf(),
                ),
            )
        }
        assertEquals(2, successor.signatureCount)
        assertTrue(successor.verdict().proven)
    }

    @Test
    fun rejectionCancellationAndExpiryStayAvailableUntilBothSignatures() {
        val proposed = fixture.proposed()
        assertEquals(SigningStage.REJECTED, SigningWorkflow.reject(proposed).stage)
        assertEquals(DealStatus.REJECTED, SigningWorkflow.reject(proposed).storedStatus)
        assertEquals(SigningStage.CANCELLED, SigningWorkflow.cancel(proposed).stage)
        assertEquals(SigningStage.EXPIRED, SigningWorkflow.expire(proposed).stage)
        assertEquals(SigningStage.CANCELLED, SigningWorkflow.cancel(SigningWorkflow.start(fixture.namedDeal())).stage)

        val partial = fixture.partial()
        val cancelled = SigningWorkflow.cancel(partial)
        assertEquals(SigningStage.CANCELLED, cancelled.stage)
        assertEquals(DealStatus.CANCELLED, cancelled.storedStatus)
        assertEquals(1, cancelled.signatureCount)
        assertFalse(cancelled.verdict().proven)
        val restored = SigningWorkflow.recover(cancelled.deal, listOf(SigningWorkflow.journalOutcome(cancelled)))
        assertEquals(SigningStage.CANCELLED, restored.stage)
        assertEquals(1, restored.signatureCount)
        assertArrayEquals(partial.signatures.single().signature, restored.signatures.single().signature)

        val full = fixture.full()
        assertThrows(DealStateException::class.java) { SigningWorkflow.cancel(full) }
        assertThrows(DealStateException::class.java) { SigningWorkflow.expire(full) }
        assertThrows(DealStateException::class.java) { SigningWorkflow.reject(full) }
        assertEquals(2, full.signatureCount)
        assertTrue(full.verdict().proven)
    }

    @Test
    fun reviewSignatureAndNameSuccessorBindTheSameTerms() {
        val proposed = SigningWorkflow.sendProposal(SigningWorkflow.start(fixture.unnamedDeal()))
        assertThrows(ForeignPartyException::class.java) {
            SigningWorkflow.signPartyBReview(proposed, fixture.signerA)
        }
        val review = SigningWorkflow.signPartyBReview(proposed, fixture.signerB)
        val adopted = SigningWorkflow.adoptPartyBReview(proposed, review)
        assertEquals(SigningStage.AWAITING_PARTY_B_REVIEW, adopted.stage)
        assertSame(adopted, SigningWorkflow.adoptPartyBReview(adopted, review))
        assertThrows(DealCryptoException.InvalidSignature::class.java) {
            SigningWorkflow.adoptPartyBReview(proposed, ByteArray(64) { 7 })
        }

        val named = SigningWorkflow.confirmOwnNameOnSuccessor(
            adopted,
            fixture.partyA,
            fixture.signerA,
            fixture.nameA,
            "n".repeat(22),
            fixture.createdAt + 1_000,
        )
        assertEquals(fixture.terms, named.deal.terms)
        assertEquals("n".repeat(22), named.deal.versionId)
        assertEquals(adopted.deal.versionId, named.deal.parentVersionId)
        assertEquals(fixture.nameA, named.deal.identity!!.pin(fixture.partyA).legalName)
        assertTrue(named.deal.identity!!.pin(fixture.partyB).legalName.isEmpty())
        assertEquals(SigningStage.AWAITING_NAME_CONFIRMATIONS, named.stage)
        assertArrayEquals(adopted.deal.canonicalBytes(), proposed.deal.canonicalBytes())

        val evil = CanonicalDeal.create(
            dealId = named.deal.dealId,
            versionId = named.deal.versionId,
            parentVersionId = named.deal.parentVersionId,
            createdAtEpochMs = named.deal.createdAtEpochMs,
            currency = named.deal.currency,
            amountMinor = named.deal.amountMinor,
            parties = named.deal.parties,
            ancestry = named.deal.ancestry,
            terms = "Tampered terms",
            details = named.deal.details,
            identity = named.deal.identity,
            annex = named.deal.annex,
        )
        assertThrows(DealFormatException::class.java) {
            SigningWorkflow.adoptNameSuccessor(
                adopted,
                evil,
                fixture.partyA,
                named.nameConfirmations.single().signature,
            )
        }
        assertEquals(0, adopted.signatureCount)
        assertFalse(SigningUi.canSign(SigningStage.PROPOSED, ownSignature = false, proven = false))
        assertTrue(SigningUi.canSign(SigningStage.READY_TO_SIGN, ownSignature = false, proven = false))
        assertFalse(SigningUi.canSign(SigningStage.FULLY_SIGNED, ownSignature = false, proven = true))
        assertFalse(SigningUi.canRevise(SigningStage.FULLY_SIGNED, PartyRole.PROPOSER, proven = true))
        assertFalse(SigningUi.canReject(SigningStage.FULLY_SIGNED, proven = true))
        assertTrue(SigningUi.canReview(SigningStage.PROPOSED, PartyRole.COUNTERPARTY))
        assertFalse(SigningUi.canReview(SigningStage.PROPOSED, PartyRole.PROPOSER))
    }

    private fun attempt(progress: SigningProgress, event: SigningEvent): SigningProgress = when (event) {
        SigningEvent.SEND_PROPOSAL -> SigningWorkflow.sendProposal(progress)
        SigningEvent.PARTY_B_VALIDATED -> SigningWorkflow.recordPartyBReview(progress, fixture.signerB)
        SigningEvent.CONFIRM_OWN_NAME -> SigningWorkflow.confirmOwnName(progress, fixture.partyA, fixture.signerA, fixture.nameA)
        SigningEvent.SIGN -> SigningWorkflow.sign(progress, fixture.partyA, fixture.keysA.signingHandle())
        SigningEvent.REJECT -> SigningWorkflow.reject(progress)
        SigningEvent.CANCEL -> SigningWorkflow.cancel(progress)
        SigningEvent.EXPIRE -> SigningWorkflow.expire(progress)
        SigningEvent.REVISE -> SigningWorkflow.revise(
            progress,
            progress.deal.canonicalBytes(),
            "w".repeat(22),
            "Revised terms",
            fixture.createdAt + 86_400_000,
        )
        SigningEvent.ACKNOWLEDGE -> SigningWorkflow.acknowledge(progress)
        SigningEvent.RECORD_DELIVERY -> SigningWorkflow.recordDelivery(progress)
    }

    companion object {
        private val ALLOWED: Map<SigningStage, Set<SigningEvent>> = mapOf(
            SigningStage.LOCAL_DRAFT to setOf(SigningEvent.SEND_PROPOSAL, SigningEvent.CANCEL, SigningEvent.EXPIRE),
            SigningStage.PROPOSED to setOf(
                SigningEvent.PARTY_B_VALIDATED,
                SigningEvent.REJECT,
                SigningEvent.CANCEL,
                SigningEvent.EXPIRE,
                SigningEvent.REVISE,
                SigningEvent.ACKNOWLEDGE,
                SigningEvent.RECORD_DELIVERY,
            ),
            SigningStage.AWAITING_PARTY_B_REVIEW to setOf(
                SigningEvent.CONFIRM_OWN_NAME,
                SigningEvent.PARTY_B_VALIDATED,
                SigningEvent.REJECT,
                SigningEvent.CANCEL,
                SigningEvent.EXPIRE,
                SigningEvent.REVISE,
                SigningEvent.ACKNOWLEDGE,
                SigningEvent.RECORD_DELIVERY,
            ),
            SigningStage.AWAITING_NAME_CONFIRMATIONS to setOf(
                SigningEvent.CONFIRM_OWN_NAME,
                SigningEvent.REJECT,
                SigningEvent.CANCEL,
                SigningEvent.EXPIRE,
                SigningEvent.REVISE,
                SigningEvent.ACKNOWLEDGE,
                SigningEvent.RECORD_DELIVERY,
            ),
            SigningStage.READY_TO_SIGN to setOf(
                SigningEvent.SIGN,
                SigningEvent.CONFIRM_OWN_NAME,
                SigningEvent.REJECT,
                SigningEvent.CANCEL,
                SigningEvent.EXPIRE,
                SigningEvent.REVISE,
                SigningEvent.ACKNOWLEDGE,
                SigningEvent.RECORD_DELIVERY,
            ),
            SigningStage.PARTIALLY_SIGNED to setOf(
                SigningEvent.SIGN,
                SigningEvent.REJECT,
                SigningEvent.CANCEL,
                SigningEvent.EXPIRE,
                SigningEvent.REVISE,
                SigningEvent.ACKNOWLEDGE,
                SigningEvent.RECORD_DELIVERY,
            ),
            SigningStage.FULLY_SIGNED to setOf(
                SigningEvent.ACKNOWLEDGE,
                SigningEvent.RECORD_DELIVERY,
                SigningEvent.CONFIRM_OWN_NAME,
                SigningEvent.SIGN,
            ),
            SigningStage.REJECTED to emptySet(),
            SigningStage.CANCELLED to emptySet(),
            SigningStage.EXPIRED to emptySet(),
        )
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SigningJournalRecoveryTest {
    @Test
    fun recoveryIgnoresClaimedStatusTamperingAndKeepsOlderVersions() {
        val context = RuntimeEnvironment.getApplication()
        val database = Room.inMemoryDatabaseBuilder(context, DealDatabase::class.java).allowMainThreadQueries().build()
        try {
            assertEquals(3, database.openHelper.readableDatabase.version)
            val fixture = SigningFixture()
            val dao = database.dealDao()
            val store = SigningProgressStore(dao)
            val partial = fixture.partial()
            dao.insertDeal(
                DealRow(
                    dealId = partial.deal.dealId,
                    status = DealStatus.DRAFT.name,
                    currentVersionId = partial.deal.versionId,
                    createdAtEpochMs = partial.deal.createdAtEpochMs,
                    updatedAtEpochMs = partial.deal.createdAtEpochMs,
                ),
            )
            store.save(partial, "event-partial", 10)
            store.save(partial, "event-repeat", 11)
            val partialAudits = dao.audits(partial.deal.dealId).filter { it.action == SigningWorkflow.JOURNAL_ACTION }
            assertEquals(1, partialAudits.size)
            assertFalse(partialAudits.single().outcome.contains(fixture.nameA))
            assertFalse(partialAudits.single().outcome.contains(fixture.terms))
            assertTrue(dao.signatures(partial.deal.dealId, partial.deal.versionId).isEmpty())
            assertEquals(DealStatus.PARTIALLY_SIGNED.name, dao.findDeal(partial.deal.dealId)!!.status)

            val interrupted = store.recover(partial.deal)
            assertEquals(SigningStage.PARTIALLY_SIGNED, interrupted.stage)
            assertEquals(1, interrupted.signatureCount)
            assertFalse(interrupted.verdict().proven)
            assertSame(interrupted, SigningWorkflow.sign(interrupted, fixture.partyA, fixture.keysA.signingHandle()))

            dao.updateDeal(partial.deal.dealId, DealStatus.FULLY_SIGNED.name, partial.deal.versionId, 20)
            val encoded = SigningWorkflow.encodeJournal(partial)
            assertEquals("ADSW", encoded.copyOfRange(0, 4).toString(Charsets.US_ASCII))
            assertEquals(SigningStage.PARTIALLY_SIGNED.code, encoded[5].toInt() and 0xff)
            val claimed = encoded.copyOf()
            claimed[5] = SigningStage.FULLY_SIGNED.code.toByte()
            dao.insertAudit(audit(partial.deal.dealId, "claimed-final", claimed, 21))
            val flipped = encoded.copyOf()
            val middle = flipped.size / 2
            flipped[middle] = (flipped[middle].toInt() xor 0xff).toByte()
            dao.insertAudit(audit(partial.deal.dealId, "flipped", flipped, 22))
            dao.insertAudit(
                com.codeyantra.armorchat.deal.storage.DealAuditRow(
                    eventId = "garbage",
                    dealId = partial.deal.dealId,
                    action = SigningWorkflow.JOURNAL_ACTION,
                    outcome = "ok:!!!!",
                    createdAtEpochMs = 23,
                ),
            )
            val before = dao.auditCount()
            val recovered = store.recover(partial.deal)
            assertEquals(before, dao.auditCount())
            assertEquals(SigningStage.PARTIALLY_SIGNED, recovered.stage)
            assertEquals(DealStatus.PARTIALLY_SIGNED, recovered.storedStatus)
            assertEquals(1, recovered.signatureCount)
            assertFalse(recovered.verdict().proven)

            val full = SigningWorkflow.sign(partial, fixture.partyB, fixture.keysB.signingHandle())
            store.save(full, "event-full", 30)
            val cancelBytes = SigningWorkflow.encodeJournal(full)
            cancelBytes[cancelBytes.lastIndex] = 2
            dao.insertAudit(audit(full.deal.dealId, "late-cancel", cancelBytes, 31))
            val erased = SigningWorkflow.encodeJournal(SigningWorkflow.start(full.deal))
            dao.insertAudit(audit(full.deal.dealId, "erased", erased, 32))
            val proven = store.recover(full.deal)
            assertEquals(SigningStage.FULLY_SIGNED, proven.stage)
            assertEquals(2, proven.signatureCount)
            assertTrue(proven.verdict().proven)

            val revised = SigningWorkflow.revise(
                partial,
                partial.deal.canonicalBytes(),
                "w".repeat(22),
                "Revised terms",
                fixture.createdAt + 86_400_000,
            )
            store.save(revised, "event-revision", 40)
            assertEquals(DealStatus.NEGOTIATING.name, dao.findDeal(partial.deal.dealId)!!.status)
            assertEquals(revised.deal.versionId, dao.findDeal(partial.deal.dealId)!!.currentVersionId)
            val journals = dao.audits(partial.deal.dealId).filter { it.action == SigningWorkflow.JOURNAL_ACTION }
            assertTrue(journals.size >= 3)
            val oldVersion = store.recover(partial.deal)
            assertEquals(SigningStage.FULLY_SIGNED, oldVersion.stage)
            assertTrue(oldVersion.verdict().proven)
            assertTrue(
                oldVersion.signatures.any { it.signature.contentEquals(partial.signatures.single().signature) },
            )
            val newVersion = store.recover(revised.deal)
            assertEquals(SigningStage.PROPOSED, newVersion.stage)
            assertEquals(DealStatus.NEGOTIATING, newVersion.storedStatus)
            assertEquals(0, newVersion.signatureCount)
            assertEquals(revised.deal.versionId, newVersion.deal.versionId)
        } finally {
            database.close()
        }
    }

    private fun audit(
        dealId: String,
        eventId: String,
        body: ByteArray,
        at: Long,
    ) = com.codeyantra.armorchat.deal.storage.DealAuditRow(
        eventId = eventId,
        dealId = dealId,
        action = SigningWorkflow.JOURNAL_ACTION,
        outcome = "ok:" + Base64Url.encode(body),
        createdAtEpochMs = at,
    )
}

private class SigningFixture {
    val signerA: CountingSigner
    val signerB: CountingSigner
    val keysA: DealKeyMaterial = DealKeyMaterial.generate()
    val keysB: DealKeyMaterial = DealKeyMaterial.generate()
    val participantA: VerifiedDealParticipant
    val participantB: VerifiedDealParticipant
    val partyA: String = "a".repeat(22)
    val partyB: String = "b".repeat(22)
    val nameA: String = "Neeraj Sharma"
    val nameB: String = "Asha Menon"
    val terms: String = "Shared terms"
    val createdAt: Long = 1_700_000_000_000L

    init {
        DealTink.register()
        signerA = CountingSigner(KeysetHandle.generateNew(KeyTemplates.get("ED25519")))
        signerB = CountingSigner(KeysetHandle.generateNew(KeyTemplates.get("ED25519")))
        participantA = VerifiedDealParticipant.verify(ArmorChatId.derive(signerA.publicKey), signerA.publicKey)
        participantB = VerifiedDealParticipant.verify(ArmorChatId.derive(signerB.publicKey), signerB.publicKey)
    }

    fun proposed(): SigningProgress = SigningWorkflow.sendProposal(SigningWorkflow.start(namedDeal()))

    fun ready(): SigningProgress {
        val reviewed = SigningWorkflow.recordPartyBReview(proposed(), signerB)
        val first = SigningWorkflow.confirmOwnName(reviewed, partyA, signerA, nameA)
        return SigningWorkflow.confirmOwnName(first, partyB, signerB, nameB)
    }

    fun partial(): SigningProgress = SigningWorkflow.sign(ready(), partyA, keysA.signingHandle())

    fun full(): SigningProgress = SigningWorkflow.sign(partial(), partyB, keysB.signingHandle())

    fun path(): Map<SigningStage, SigningProgress> {
        val out = linkedMapOf<SigningStage, SigningProgress>()
        var progress = SigningWorkflow.start(namedDeal())
        out[progress.stage] = progress
        progress = SigningWorkflow.sendProposal(progress)
        out[progress.stage] = progress
        val proposed = progress
        progress = SigningWorkflow.recordPartyBReview(progress, signerB)
        out[progress.stage] = progress
        progress = SigningWorkflow.confirmOwnName(progress, partyA, signerA, nameA)
        out[progress.stage] = progress
        progress = SigningWorkflow.confirmOwnName(progress, partyB, signerB, nameB)
        out[progress.stage] = progress
        progress = SigningWorkflow.sign(progress, partyA, keysA.signingHandle())
        out[progress.stage] = progress
        progress = SigningWorkflow.sign(progress, partyB, keysB.signingHandle())
        out[progress.stage] = progress
        out[SigningStage.REJECTED] = SigningWorkflow.reject(proposed)
        out[SigningStage.CANCELLED] = SigningWorkflow.cancel(proposed)
        out[SigningStage.EXPIRED] = SigningWorkflow.expire(proposed)
        return out
    }

    fun namedDeal(): CanonicalDeal {
        val deal = unnamedDeal()
        val withA = DealIdentity.confirmOwnLegalName(deal, partyA, signerA, nameA, 0, DealStatus.PROPOSED)
        return DealIdentity.confirmOwnLegalName(withA, partyB, signerB, nameB, 0, DealStatus.PROPOSED)
    }

    fun unnamedDeal(): CanonicalDeal {
        val dealId = "d".repeat(22)
        val versionId = "v".repeat(22)
        return DealIdentity.assemble(
            dealId = dealId,
            versionId = versionId,
            parentVersionId = null,
            createdAtEpochMs = createdAt,
            currency = null,
            amountMinor = null,
            terms = terms,
            details = AgreementDetails("Shared agreement", "2026-12-31", "", "Creator", "Recipient"),
            ancestry = emptyList(),
            drafts = listOf(
                draft(partyA, PartyRole.PROPOSER, "A", participantA, signerA, keysA),
                draft(partyB, PartyRole.COUNTERPARTY, "B", participantB, signerB, keysB),
            ),
            annex = ContractAnnex("Shared purpose", null),
        )
    }

    private fun draft(
        partyId: String,
        role: PartyRole,
        label: String,
        participant: VerifiedDealParticipant,
        signer: CountingSigner,
        keys: DealKeyMaterial,
    ): DealPartyDraft {
        val binding = signer.sign(
            DealIdentity.bindingPayload(
                "d".repeat(22),
                role,
                participant,
                keys.signingPublic,
                keys.encryptionPublic,
            ),
        )
        return DealPartyDraft(
            partyId = partyId,
            role = role,
            label = label,
            participant = participant,
            signingPublicKey = keys.signingPublic,
            encryptionPublicKey = keys.encryptionPublic,
            bindingSignature = binding,
        )
    }
}
