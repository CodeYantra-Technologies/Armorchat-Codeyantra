package com.codeyantra.armorchat.deal

import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealParty
import com.codeyantra.armorchat.deal.domain.DealStatus
import com.codeyantra.armorchat.deal.domain.DealVersions
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.deal.testing.toHex
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CanonicalDealV2Test {
    @Test
    fun versionOneBytesStayPinnedWhenDetailsAreAbsent() {
        val deal = v1()
        val bytes = deal.canonicalBytes()
        assertEquals(1, bytes[4].toInt())
        assertEquals(1, deal.canonicalVersion)
        assertNull(deal.details)
        assertEquals(V1_HASH, sha256(bytes).toHex())
        assertArrayEquals(bytes, CanonicalEncoding.parse(bytes).canonicalBytes())
        val trailed = bytes.copyOf(bytes.size + 1)
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(trailed) }
    }

    @Test
    fun versionTwoRoundTripsAndKeepsFieldsInTheSignedBytes() {
        val deal = v2()
        val bytes = deal.canonicalBytes()
        assertEquals(2, bytes[4].toInt())
        assertTrue(bytes.size > v1().canonicalBytes().size)
        val parsed = CanonicalEncoding.parse(bytes)
        assertArrayEquals(bytes, parsed.canonicalBytes())
        val details = parsed.details ?: throw AssertionError("Version 2 details were dropped.")
        assertEquals("Synthetic studio rental", details.title)
        assertEquals("2026-12-31", details.deadlineDate)
        assertEquals("Pay on delivery", details.paymentConditions)
        assertEquals("Client", details.proposerRole)
        assertEquals("Studio", details.counterpartyRole)
        assertEquals(150050L, parsed.amountMinor)
        assertEquals("INR", parsed.currency)
        assertEquals("Local synthetic", parsed.parties.first { it.role == PartyRole.PROPOSER }.label)
        assertEquals("Simulated party", parsed.parties.first { it.role == PartyRole.COUNTERPARTY }.label)
        val utf8 = String(bytes, Charsets.UTF_8)
        listOf(
            "Synthetic studio rental",
            "2026-12-31",
            "Pay on delivery",
            "Client",
            "Studio",
            "Local synthetic",
            "Simulated party",
            "Alpha terms",
        ).forEach { field ->
            assertTrue("Missing from canonical bytes: $field", utf8.contains(field))
        }
    }

    @Test
    fun displayedFieldChangesTheDocumentHash() {
        val original = v2()
        assertFalse(original.documentHash().contentEquals(v2(title = "Other title").documentHash()))
        assertFalse(original.documentHash().contentEquals(v2(deadline = "2027-01-15").documentHash()))
        assertFalse(original.documentHash().contentEquals(v2(proposerRole = "Buyer").documentHash()))
        assertFalse(original.documentHash().contentEquals(v2(counterpartyRole = "Owner").documentHash()))
        assertFalse(original.documentHash().contentEquals(v2(payment = "Pay in two parts").documentHash()))
        assertFalse(original.documentHash().contentEquals(v2(amountMinor = 150000L).documentHash()))
        assertFalse(original.documentHash().contentEquals(v2(proposerLabel = "Other local").documentHash()))
        assertFalse(original.documentHash().contentEquals(v2(terms = "Beta terms").documentHash()))
        val emptyPayment = v2(payment = "")
        assertEquals("", emptyPayment.details?.paymentConditions)
        assertFalse(original.documentHash().contentEquals(emptyPayment.documentHash()))
    }

    @Test
    fun badDeadlinesAndTrailingBytesAreRejected() {
        listOf("2026-02-31", "2026-13-01", "2026-00-10", "31-12-2026", "2026-2-01", "").forEach { deadline ->
            assertThrows(DealFormatException::class.java) { v2(deadline = deadline) }
        }
        val trailed = v2().canonicalBytes().let { it.copyOf(it.size + 1) }
        assertThrows(DealFormatException::class.java) { CanonicalEncoding.parse(trailed) }
    }

    @Test
    fun revisionKeepsVersionTwoDetails() {
        val deal = v2()
        val original = deal.canonicalBytes()
        val revised = DealVersions.revise(
            current = deal,
            currentBytes = original,
            signatureCount = 0,
            dealStatus = DealStatus.DRAFT,
            newVersionId = "w".repeat(22),
            newTerms = "Alpha terms, revised",
            revisedAtEpochMs = 1_700_000_000_100,
        )
        assertEquals(deal.details?.title, revised.details?.title)
        assertEquals(deal.details?.deadlineDate, revised.details?.deadlineDate)
        assertEquals(deal.details?.paymentConditions, revised.details?.paymentConditions)
        assertEquals(deal.details?.proposerRole, revised.details?.proposerRole)
        assertEquals(deal.details?.counterpartyRole, revised.details?.counterpartyRole)
        assertEquals(2, revised.canonicalVersion)
        assertArrayEquals(original, deal.canonicalBytes())
    }

    @Test
    fun editingASignedFieldInvalidatesTheSignature() {
        val keys = DealKeyMaterial.generate()
        val other = DealKeyMaterial.generate()
        val deal = v2(proposerKeys = keys, counterpartyKeys = other)
        val proposerId = deal.parties.first { it.role == PartyRole.PROPOSER }.partyId
        val signature = DealSignatures.sign(keys.signingHandle(), deal, proposerId, SignAction.SIGN)
        DealSignatures.verify(
            deal,
            signature,
            SignAction.SIGN,
            deal.dealId,
            deal.versionId,
            keys.signingPublic,
        )
        val edited = v2(title = "Changed after signing", proposerKeys = keys, counterpartyKeys = other)
        assertFalse(deal.documentHash().contentEquals(edited.documentHash()))
        assertThrows(DealCryptoException.InvalidSignature::class.java) {
            DealSignatures.verify(
                edited,
                signature,
                SignAction.SIGN,
                edited.dealId,
                edited.versionId,
                keys.signingPublic,
            )
        }
        DealSignatures.verify(
            deal,
            signature,
            SignAction.SIGN,
            deal.dealId,
            deal.versionId,
            keys.signingPublic,
        )
    }

    private fun v1(): CanonicalDeal = CanonicalDeal.create(
        dealId = "d".repeat(22),
        versionId = "v".repeat(22),
        parentVersionId = null,
        createdAtEpochMs = 1_700_000_000_000,
        currency = null,
        amountMinor = null,
        parties = listOf(proposer(), counterparty()),
        ancestry = emptyList(),
        terms = "Rent",
    )

    private fun v2(
        title: String = "Synthetic studio rental",
        deadline: String = "2026-12-31",
        payment: String = "Pay on delivery",
        proposerRole: String = "Client",
        counterpartyRole: String = "Studio",
        amountMinor: Long = 150050L,
        proposerLabel: String = "Local synthetic",
        terms: String = "Alpha terms",
        proposerKeys: DealKeyMaterial? = null,
        counterpartyKeys: DealKeyMaterial? = null,
    ): CanonicalDeal {
        val proposer = if (proposerKeys == null) {
            proposer(proposerLabel)
        } else {
            DealParty(
                "a".repeat(22),
                PartyRole.PROPOSER,
                proposerLabel,
                proposerKeys.signingPublic,
                proposerKeys.encryptionPublic,
            )
        }
        val counterparty = if (counterpartyKeys == null) {
            counterparty("Simulated party")
        } else {
            DealParty(
                "b".repeat(22),
                PartyRole.COUNTERPARTY,
                "Simulated party",
                counterpartyKeys.signingPublic,
                counterpartyKeys.encryptionPublic,
            )
        }
        return CanonicalDeal.create(
            dealId = "d".repeat(22),
            versionId = "v".repeat(22),
            parentVersionId = null,
            createdAtEpochMs = 1_700_000_000_000,
            currency = "INR",
            amountMinor = amountMinor,
            parties = listOf(proposer, counterparty),
            ancestry = emptyList(),
            terms = terms,
            details = AgreementDetails(
                title = title,
                deadlineDate = deadline,
                paymentConditions = payment,
                proposerRole = proposerRole,
                counterpartyRole = counterpartyRole,
            ),
        )
    }

    private fun proposer(label: String = "A"): DealParty = DealParty(
        "a".repeat(22),
        PartyRole.PROPOSER,
        label,
        ByteArray(32) { 0x11 },
        ByteArray(32) { 0x33 },
    )

    private fun counterparty(label: String = "B"): DealParty = DealParty(
        "b".repeat(22),
        PartyRole.COUNTERPARTY,
        label,
        ByteArray(32) { 0x22 },
        ByteArray(32) { 0x44 },
    )

    companion object {
        private const val V1_HASH = "114207667dcdef469408d06d695c98d5b252947732e81b49a1867030914fee95"
    }
}
