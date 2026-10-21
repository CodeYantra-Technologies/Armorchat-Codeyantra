package com.codeyantra.armorchat.deal

import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealConflictException
import com.codeyantra.armorchat.deal.domain.DealEvent
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.FinalityDefect
import com.codeyantra.armorchat.deal.domain.FinalityVerdict
import com.codeyantra.armorchat.deal.domain.DealParty
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStateException
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.DealVersions
import com.codeyantra.armorchat.deal.domain.ImmutableVersionException
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.deal.testing.hexToBytes
import com.codeyantra.armorchat.deal.testing.toHex
import java.text.Normalizer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDealTest {
    @Test
    fun canonicalBytesMatchThePinnedVectorAndStayDeterministic() {
        val first = vectorDeal(partiesReversed = true)
        val second = vectorDeal(partiesReversed = false)
        val bytes = first.canonicalBytes()
        assertArrayEquals(bytes, second.canonicalBytes())
        assertArrayEquals(VECTOR_BYTES, bytes)
        assertArrayEquals(bytes, CanonicalEncoding.parse(bytes).canonicalBytes())
        assertEquals(VECTOR_HASH, bytes.let(::sha256).toHex())
    }

    @Test
    fun changedTermsOrAmountChangeTheDocumentHash() {
        val original = vectorDeal(partiesReversed = false)
        val changedTerms = CanonicalDeal.create(
            dealId = original.dealId,
            versionId = original.versionId,
            parentVersionId = null,
            createdAtEpochMs = original.createdAtEpochMs,
            currency = null,
            amountMinor = null,
            parties = original.parties,
            ancestry = emptyList(),
            terms = "Rent due Friday",
        )
        assertFalse(original.documentHash().contentEquals(changedTerms.documentHash()))
        val priced = CanonicalDeal.create(
            dealId = original.dealId,
            versionId = "w".repeat(22),
            parentVersionId = original.versionId,
            createdAtEpochMs = original.createdAtEpochMs + 1,
            currency = "USD",
            amountMinor = 1050,
            parties = original.parties,
            ancestry = listOf(original.versionId),
            terms = original.terms,
        )
        val repriced = CanonicalDeal.create(
            dealId = priced.dealId,
            versionId = priced.versionId,
            parentVersionId = priced.parentVersionId,
            createdAtEpochMs = priced.createdAtEpochMs,
            currency = "USD",
            amountMinor = 1051,
            parties = priced.parties,
            ancestry = priced.ancestry,
            terms = priced.terms,
        )
        assertFalse(priced.documentHash().contentEquals(repriced.documentHash()))
        assertEquals(1050L, priced.amountMinor)
    }

    @Test
    fun parserRejectsTrailingBytesUnsortedPartiesAndAmbiguousMoney() {
        val bytes = VECTOR_BYTES.copyOf(VECTOR_BYTES.size + 1)
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(bytes) }
        val swapped = swapPartyRecords(VECTOR_BYTES.copyOf())
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(swapped) }
        assertThrows(DealFormatException::class.java) {
            vectorDeal(partiesReversed = false, currency = "usd", amountMinor = 1)
        }
        assertThrows(DealFormatException::class.java) {
            vectorDeal(partiesReversed = false, currency = "USD", amountMinor = null)
        }
        assertThrows(DealFormatException::class.java) {
            vectorDeal(partiesReversed = false, currency = "USD", amountMinor = -1)
        }
        val nfd = Normalizer.normalize("é", Normalizer.Form.NFD)
        assertThrows(DealFormatException::class.java) {
            vectorDeal(partiesReversed = false, terms = nfd)
        }
    }

    private fun vectorDeal(
        partiesReversed: Boolean,
        terms: String = "Rent",
        currency: String? = null,
        amountMinor: Long? = null,
    ): CanonicalDeal {
        val proposer = DealParty(
            "a".repeat(22),
            PartyRole.PROPOSER,
            "A",
            ByteArray(32) { 0x11 },
            ByteArray(32) { 0x33 },
        )
        val counterparty = DealParty(
            "b".repeat(22),
            PartyRole.COUNTERPARTY,
            "B",
            ByteArray(32) { 0x22 },
            ByteArray(32) { 0x44 },
        )
        return CanonicalDeal.create(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            parentVersionId = null,
            createdAtEpochMs = 1_700_000_000_000,
            currency = currency,
            amountMinor = amountMinor,
            parties = if (partiesReversed) listOf(counterparty, proposer) else listOf(proposer, counterparty),
            ancestry = emptyList(),
            terms = terms,
        )
    }

    private fun swapPartyRecords(bytes: ByteArray): ByteArray {
        val first = "a".repeat(22).toByteArray()
        val second = "b".repeat(22).toByteArray()
        val firstAt = indexOf(bytes, first) - 2
        val secondAt = indexOf(bytes, second) - 2
        val width = secondAt - firstAt
        val copy = bytes.copyOf()
        val a = copy.copyOfRange(firstAt, secondAt)
        val b = copy.copyOfRange(secondAt, secondAt + width)
        b.copyInto(copy, firstAt)
        a.copyInto(copy, secondAt)
        return copy
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray): Int {
        for (start in 0..haystack.size - needle.size) {
            if (haystack.copyOfRange(start, start + needle.size).contentEquals(needle)) return start
        }
        throw AssertionError("needle missing")
    }

    companion object {
        private val VECTOR_BYTES = hexToBytes(
            "414443420100166464646464646464646464646464646464646464646400167676767676767676767676767676767676767676767600000000018bcfe568000000000200166161616161616161616161616161616161616161616101000141002011111111111111111111111111111111111111111111111111111111111111110020333333333333333333333333333333333333333333333333333333333333333300166262626262626262626262626262626262626262626202000142002022222222222222222222222222222222222222222222222222222222222222220020444444444444444444444444444444444444444444444444444444444444444400000000000452656e74",
        )
        private const val VECTOR_HASH = "114207667dcdef469408d06d695c98d5b252947732e81b49a1867030914fee95"
    }
}

class DealLifecycleTest {
    @Test
    fun requiredPathReachesExecutedAndInvalidEdgesAreRejected() {
        var status = DealStatus.DRAFT
        status = com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(status, com.codeyantra.armorchat.deal.domain.DealEvent.PROPOSE)
        status = com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(status, com.codeyantra.armorchat.deal.domain.DealEvent.NEGOTIATE)
        status = com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(status, com.codeyantra.armorchat.deal.domain.DealEvent.PARTIAL_SIGN)
        status = com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(status, com.codeyantra.armorchat.deal.domain.DealEvent.FULL_SIGN)
        status = com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(status, com.codeyantra.armorchat.deal.domain.DealEvent.MARK_BACKUP_PENDING)
        status = com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(status, com.codeyantra.armorchat.deal.domain.DealEvent.EXECUTE)
        assertEquals(DealStatus.EXECUTED, status)
        assertThrows(DealStateException::class.java) {
            com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(DealStatus.DRAFT, com.codeyantra.armorchat.deal.domain.DealEvent.EXECUTE)
        }
        assertThrows(DealStateException::class.java) {
            com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(DealStatus.FULLY_SIGNED, com.codeyantra.armorchat.deal.domain.DealEvent.SUPERSEDE)
        }
        assertThrows(DealStateException::class.java) {
            com.codeyantra.armorchat.deal.domain.DealLifecycle.transition(DealStatus.EXECUTED, com.codeyantra.armorchat.deal.domain.DealEvent.CANCEL)
        }
    }

    @Test
    fun signedVersionCannotBeEditedAndUnsignedRevisionKeepsTheOldBytes() {
        val deal = CanonicalDeal.create(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            parentVersionId = null,
            createdAtEpochMs = 1_700_000_000_000,
            currency = "USD",
            amountMinor = 1050,
            parties = listOf(
                DealParty("a".repeat(22), PartyRole.PROPOSER, "A", ByteArray(32) { 1 }, ByteArray(32) { 2 }),
                DealParty("b".repeat(22), PartyRole.COUNTERPARTY, "B", ByteArray(32) { 3 }, ByteArray(32) { 4 }),
            ),
            ancestry = emptyList(),
            terms = "Pay 10.50 as 1050 minor units",
        )
        val original = deal.canonicalBytes()
        val revised = DealVersions.revise(
            current = deal,
            currentBytes = original,
            signatureCount = 0,
            dealStatus = DealStatus.NEGOTIATING,
            newVersionId = "w".repeat(22),
            newTerms = "Pay 10.50 as 1050 minor units, revised",
            revisedAtEpochMs = 1_700_000_000_100,
        )
        assertArrayEquals(original, deal.canonicalBytes())
        assertFalse(original.contentEquals(revised.canonicalBytes()))
        assertEquals(deal.versionId, revised.parentVersionId)
        assertThrows(ImmutableVersionException::class.java) {
            DealVersions.revise(
                current = deal,
                currentBytes = original,
                signatureCount = 1,
                dealStatus = DealStatus.NEGOTIATING,
                newVersionId = "x".repeat(22),
                newTerms = "silently edited",
                revisedAtEpochMs = 1_700_000_000_200,
            )
        }
        assertArrayEquals(original, deal.canonicalBytes())
    }

    @Test
    fun invalidAndDuplicateEventsAreRejected() {
        assertThrows(DealStateException::class.java) {
            DealLifecycle.transition(DealStatus.PROPOSED, DealEvent.PROPOSE)
        }
        assertThrows(DealStateException::class.java) {
            DealLifecycle.transition(DealStatus.PARTIALLY_SIGNED, DealEvent.PARTIAL_SIGN)
        }
        assertThrows(DealStateException::class.java) {
            DealLifecycle.transition(DealStatus.FULLY_SIGNED, DealEvent.FULL_SIGN)
        }
        assertThrows(DealStateException::class.java) {
            DealLifecycle.transition(DealStatus.CANCELLED, DealEvent.CANCEL)
        }
        assertThrows(DealStateException::class.java) {
            DealLifecycle.transition(DealStatus.EXPIRED, DealEvent.EXPIRE)
        }
        assertThrows(DealStateException::class.java) {
            DealLifecycle.transition(DealStatus.NEGOTIATING, DealEvent.PROPOSE)
        }
    }

    @Test
    fun partiallySignedDealCanBeCancelledWithoutDroppingItsSignature() {
        assertEquals(
            DealStatus.CANCELLED,
            DealLifecycle.transition(DealStatus.PARTIALLY_SIGNED, DealEvent.CANCEL),
        )
        assertEquals(
            DealStatus.EXPIRED,
            DealLifecycle.transition(DealStatus.PARTIALLY_SIGNED, DealEvent.EXPIRE),
        )
        val deal = openDeal()
        val signature = signature(deal, deal.parties.first().partyId)
        val kept = DealVersions.historicalSignatures(
            listOf(signature),
            supersededVersionId = deal.versionId,
            successorVersionId = "w".repeat(22),
        )
        assertEquals(1, kept.size)
        assertEquals(deal.versionId, kept.single().versionId)
        assertTrue(kept.single().signature.contentEquals(signature.signature))
    }

    @Test
    fun oneSignatureStartsANewVersionAndLeavesTheOldSignatureBehind() {
        val deal = openDeal()
        val original = deal.canonicalBytes()
        val signature = signature(deal, deal.parties.first().partyId)
        val successor = DealVersions.revise(
            current = deal,
            currentBytes = original,
            signatureCount = 1,
            dealStatus = DealStatus.PARTIALLY_SIGNED,
            newVersionId = "w".repeat(22),
            newTerms = "Pay 10.50 as 1050 minor units, revised",
            revisedAtEpochMs = 1_700_000_000_100,
        )
        val historical = DealVersions.historicalSignatures(
            listOf(signature),
            supersededVersionId = deal.versionId,
            successorVersionId = successor.versionId,
        )
        assertEquals(DealStatus.NEGOTIATING, DealLifecycle.transition(DealStatus.PARTIALLY_SIGNED, DealEvent.SUPERSEDE))
        assertArrayEquals(original, deal.canonicalBytes())
        assertEquals(deal.versionId, successor.parentVersionId)
        assertEquals(listOf(deal.versionId), successor.ancestry)
        assertFalse(original.contentEquals(successor.canonicalBytes()))
        assertEquals(deal.versionId, historical.single().versionId)
        assertFalse(historical.any { it.versionId == successor.versionId })
        assertThrows(DealConflictException::class.java) {
            DealVersions.historicalSignatures(
                listOf(signature.copyBoundTo(successor.versionId)),
                deal.versionId,
                successor.versionId,
            )
        }
        assertThrows(ImmutableVersionException::class.java) {
            DealVersions.revise(
                current = deal,
                currentBytes = original,
                signatureCount = 2,
                dealStatus = DealStatus.PARTIALLY_SIGNED,
                newVersionId = "x".repeat(22),
                newTerms = "both signatures already exist",
                revisedAtEpochMs = 1_700_000_000_200,
            )
        }
    }

    @Test
    fun finalizedAgreementRejectsCancelExpireAndRevisionWithoutReceipts() {
        val unproven = FinalityVerdict.failed(FinalityDefect.MISSING_SIGNATURE)
        val proven = FinalityVerdict.proven()
        listOf(DealStatus.FULLY_SIGNED, DealStatus.BACKUP_PENDING, DealStatus.EXECUTED).forEach { status ->
            assertTrue(DealLifecycle.claimsFinality(status))
            assertTrue(DealLifecycle.isFinalized(status, proven))
            assertFalse(DealLifecycle.isFinalized(status, unproven))
        }
        assertFalse(DealLifecycle.claimsFinality(DealStatus.PARTIALLY_SIGNED))
        assertFalse(DealLifecycle.isFinalized(DealStatus.PARTIALLY_SIGNED, proven))
        assertFalse(DealLifecycle.isFinalized(DealStatus.PARTIALLY_SIGNED, unproven))
        assertEquals(
            DealStatus.FULLY_SIGNED,
            DealLifecycle.transition(DealStatus.PARTIALLY_SIGNED, DealEvent.FULL_SIGN),
        )
        listOf(DealStatus.FULLY_SIGNED, DealStatus.BACKUP_PENDING, DealStatus.EXECUTED).forEach { status ->
            assertThrows(DealStateException::class.java) {
                DealLifecycle.transition(status, DealEvent.CANCEL)
            }
            assertThrows(DealStateException::class.java) {
                DealLifecycle.transition(status, DealEvent.EXPIRE)
            }
            assertThrows(DealStateException::class.java) {
                DealLifecycle.transition(status, DealEvent.SUPERSEDE)
            }
            assertFalse(DealLifecycle.allowsRevision(status))
        }
        val deal = openDeal()
        val original = deal.canonicalBytes()
        listOf(
            DealStatus.FULLY_SIGNED to 0,
            DealStatus.BACKUP_PENDING to 1,
            DealStatus.EXECUTED to 0,
            DealStatus.PARTIALLY_SIGNED to 2,
        ).forEach { (status, count) ->
            assertThrows(ImmutableVersionException::class.java) {
                DealVersions.revise(
                    current = deal,
                    currentBytes = original,
                    signatureCount = count,
                    dealStatus = status,
                    newVersionId = "w".repeat(22),
                    newTerms = "changed after both signatures",
                    revisedAtEpochMs = 1_700_000_000_300,
                )
            }
        }
        assertArrayEquals(original, deal.canonicalBytes())
        assertFalse(DealLifecycle.allowsFinalizedStatus(DealStatus.FULLY_SIGNED, DealStatus.CANCELLED))
        assertFalse(DealLifecycle.allowsFinalizedStatus(DealStatus.BACKUP_PENDING, DealStatus.EXPIRED))
        assertTrue(DealLifecycle.allowsFinalizedStatus(DealStatus.FULLY_SIGNED, DealStatus.BACKUP_PENDING))
    }

    private fun openDeal(): CanonicalDeal = CanonicalDeal.create(
        dealId = "d".repeat(22),
        versionId = "v".repeat(22),
        parentVersionId = null,
        createdAtEpochMs = 1_700_000_000_000,
        currency = "USD",
        amountMinor = 1050,
        parties = listOf(
            DealParty("a".repeat(22), PartyRole.PROPOSER, "A", ByteArray(32) { 1 }, ByteArray(32) { 2 }),
            DealParty("b".repeat(22), PartyRole.COUNTERPARTY, "B", ByteArray(32) { 3 }, ByteArray(32) { 4 }),
        ),
        ancestry = emptyList(),
        terms = "Pay 10.50 as 1050 minor units",
    )

    private fun signature(deal: CanonicalDeal, partyId: String): DealSignature = DealSignature(
        dealId = deal.dealId,
        versionId = deal.versionId,
        partyId = partyId,
        action = SignAction.SIGN,
        signature = ByteArray(64) { 7 },
        signerPublicKey = deal.party(partyId).signingPublicKey,
    )

    private fun DealSignature.copyBoundTo(versionId: String): DealSignature = DealSignature(
        dealId = dealId,
        versionId = versionId,
        partyId = partyId,
        action = action,
        signature = signature,
        signerPublicKey = signerPublicKey,
    )
}
