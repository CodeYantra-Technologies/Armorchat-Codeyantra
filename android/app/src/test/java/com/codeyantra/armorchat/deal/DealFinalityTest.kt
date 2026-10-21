package com.codeyantra.armorchat.deal

import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealFinality
import com.codeyantra.armorchat.deal.domain.DealLifecycle
import com.codeyantra.armorchat.deal.domain.DealParty
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.FinalityDefect
import com.codeyantra.armorchat.deal.domain.FinalityVerdict
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.testing.signedFixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DealFinalityTest {
    @Test
    fun twoDistinctSignaturesProveCanonicalV1AndV2() {
        val v1 = signedFixture(terms = "Version one terms")
        val v1Verdict = assess(v1.deal, v1.signatures)
        assertEquals(DealFeature.CANONICAL_VERSION, v1.deal.canonicalVersion)
        assertTrue(v1Verdict.proven)
        assertNull(v1Verdict.defect)
        assertTrue(DealLifecycle.isFinalized(DealStatus.FULLY_SIGNED, v1Verdict))
        assertTrue(DealLifecycle.isFinalized(DealStatus.BACKUP_PENDING, v1Verdict))
        assertTrue(DealLifecycle.isFinalized(DealStatus.EXECUTED, v1Verdict))
        assertFalse(DealLifecycle.isFinalized(DealStatus.PARTIALLY_SIGNED, v1Verdict))
        assertFalse(DealLifecycle.isFinalized(DealStatus.FULLY_SIGNED, FinalityVerdict.failed(FinalityDefect.MISSING_SIGNATURE)))

        val keysA = DealKeyMaterial.generate()
        val keysB = DealKeyMaterial.generate()
        val v2 = CanonicalDeal.create(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            parentVersionId = null,
            createdAtEpochMs = 1_700_000_000_000,
            currency = "INR",
            amountMinor = 1050,
            parties = listOf(
                DealParty("a".repeat(22), PartyRole.PROPOSER, "A", keysA.signingPublic, keysA.encryptionPublic),
                DealParty("b".repeat(22), PartyRole.COUNTERPARTY, "B", keysB.signingPublic, keysB.encryptionPublic),
            ),
            ancestry = emptyList(),
            terms = "Version two terms",
            details = AgreementDetails(
                title = "Service agreement",
                deadlineDate = "2026-12-31",
                paymentConditions = "Pay on completion",
                proposerRole = "Provider",
                counterpartyRole = "Client",
            ),
        )
        val v2Signatures = listOf(
            DealSignatures.sign(keysA.signingHandle(), v2, "a".repeat(22), SignAction.SIGN),
            DealSignatures.sign(keysB.signingHandle(), v2, "b".repeat(22), SignAction.SIGN),
        )
        assertEquals(DealFeature.CANONICAL_VERSION_V2, v2.canonicalVersion)
        assertTrue(assess(v2, v2Signatures).proven)
    }

    @Test
    fun missingDuplicateAndSamePartySignaturesFailClosed() {
        val fixture = signedFixture(terms = "Need both parties")
        val partyA = fixture.signatures.single { it.partyId == fixture.partyA }
        assertEquals(FinalityDefect.MISSING_SIGNATURE, assess(fixture.deal, emptyList()).defect)
        assertEquals(FinalityDefect.MISSING_SIGNATURE, assess(fixture.deal, listOf(partyA)).defect)
        assertEquals(
            FinalityDefect.DUPLICATE_SIGNER,
            assess(fixture.deal, listOf(partyA, partyA)).defect,
        )
        assertEquals(
            FinalityDefect.MISSING_SIGNATURE,
            assess(fixture.deal, fixture.signatures + partyA).defect,
        )
        val rejection = DealSignatures.sign(
            fixture.keysA.signingHandle(),
            fixture.deal,
            fixture.partyA,
            SignAction.REJECT,
        )
        val other = fixture.signatures.single { it.partyId == fixture.partyB }
        assertEquals(FinalityDefect.INVALID_SIGNATURE, assess(fixture.deal, listOf(rejection, other)).defect)
    }

    @Test
    fun differentHashesWrongKeysAndWrongBindingsFailClosed() {
        val fixture = signedFixture(terms = "Original terms")
        val flippedHash = fixture.deal.documentHash().copyOf().also { it[0] = (it[0].toInt() xor 0xff).toByte() }
        assertEquals(
            FinalityDefect.DIFFERENT_HASH,
            DealFinality.assess(
                deal = fixture.deal,
                signatures = fixture.signatures,
                pinnedSigningKeys = pins(fixture.deal),
                storedDocumentSha256 = flippedHash,
            ).defect,
        )
        val other = CanonicalDeal.create(
            dealId = fixture.deal.dealId,
            versionId = fixture.deal.versionId,
            parentVersionId = null,
            createdAtEpochMs = fixture.deal.createdAtEpochMs,
            currency = fixture.deal.currency,
            amountMinor = fixture.deal.amountMinor,
            parties = fixture.deal.parties,
            ancestry = fixture.deal.ancestry,
            terms = "Different terms",
        )
        val signedOther = listOf(
            DealSignatures.sign(fixture.keysA.signingHandle(), other, fixture.partyA, SignAction.SIGN),
            DealSignatures.sign(fixture.keysB.signingHandle(), other, fixture.partyB, SignAction.SIGN),
        )
        assertEquals(FinalityDefect.INVALID_SIGNATURE, assess(fixture.deal, signedOther).defect)
        val tampered = rebind(
            fixture.signatures.first(),
            signature = fixture.signatures.first().signature.copyOf().also { it[63] = (it[63].toInt() xor 0x01).toByte() },
        )
        val mixed = fixture.signatures.map { if (it.partyId == tampered.partyId) tampered else it }
        assertEquals(FinalityDefect.INVALID_SIGNATURE, assess(fixture.deal, mixed).defect)

        val wrongPin = pins(fixture.deal).toMutableMap()
        wrongPin[fixture.partyA] = ByteArray(32) { 8 }
        assertEquals(FinalityDefect.MISMATCHED_KEY, assess(fixture.deal, fixture.signatures, wrongPin).defect)
        val wrongSigner = fixture.signatures.map {
            if (it.partyId == fixture.partyB) rebind(it, signerPublicKey = ByteArray(32) { 9 }) else it
        }
        assertEquals(FinalityDefect.MISMATCHED_KEY, assess(fixture.deal, wrongSigner).defect)

        val wrongDeal = fixture.signatures.map {
            if (it.partyId == fixture.partyA) rebind(it, dealId = "x".repeat(22)) else it
        }
        assertEquals(FinalityDefect.WRONG_DEAL, assess(fixture.deal, wrongDeal).defect)
        val wrongVersion = fixture.signatures.map {
            if (it.partyId == fixture.partyB) rebind(it, versionId = "w".repeat(22)) else it
        }
        assertEquals(FinalityDefect.WRONG_VERSION, assess(fixture.deal, wrongVersion).defect)
        assertEquals(
            FinalityDefect.INCONSISTENT_VERSION,
            DealFinality.assess(
                deal = fixture.deal,
                signatures = fixture.signatures,
                pinnedSigningKeys = pins(fixture.deal),
                storedVersionId = "w".repeat(22),
            ).defect,
        )
        assertTrue(assess(fixture.deal, fixture.signatures).proven)
    }

    private fun assess(
        deal: CanonicalDeal,
        signatures: List<DealSignature>,
        pinned: Map<String, ByteArray> = pins(deal),
    ) = DealFinality.assess(deal, signatures, pinned)

    private fun pins(deal: CanonicalDeal): Map<String, ByteArray> =
        deal.parties.associate { it.partyId to it.signingPublicKey }

    private fun rebind(
        source: DealSignature,
        dealId: String = source.dealId,
        versionId: String = source.versionId,
        signature: ByteArray = source.signature,
        signerPublicKey: ByteArray = source.signerPublicKey,
    ): DealSignature = DealSignature(
        dealId = dealId,
        versionId = versionId,
        partyId = source.partyId,
        action = source.action,
        signature = signature,
        signerPublicKey = signerPublicKey,
    )
}
