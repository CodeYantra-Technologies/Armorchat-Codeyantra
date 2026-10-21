package com.codeyantra.armorchat.deal

import com.codeyantra.armorchat.deal.origin.DealPartyLabels
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 5.3H: Party A is always the PROPOSER pin, Party B the COUNTERPARTY pin. */
class DealPartyLabelsTest {
    private val crest = "ac1:CREST"
    private val samsung = "ac1:SAMSUNG"

    @Test
    fun proposerPhoneShowsItselfAsPartyA() {
        val lines = DealPartyLabels.fromPins(crest, samsung, crest, "crest", samsung, "samsung")
        assertEquals("Party A · crest (ac1:CREST)", lines.partyA)
        assertEquals("Party B · samsung (ac1:SAMSUNG)", lines.partyB)
    }

    @Test
    fun counterpartyPhoneShowsProposerAsPartyANotItself() {
        val lines = DealPartyLabels.fromPins(
            proposerArmorChatId = crest,
            counterpartyArmorChatId = samsung,
            localArmorChatId = samsung,
            localDisplayName = "samsung",
            contactArmorChatId = crest,
            contactDisplayName = "crest",
        )
        assertEquals("Party A · crest (ac1:CREST)", lines.partyA)
        assertEquals("Party B · samsung (ac1:SAMSUNG)", lines.partyB)
        assertEquals("crest", lines.partyAName)
        assertEquals("samsung", lines.partyBName)
    }

    @Test
    fun bothPhonesRenderIdenticalLabels() {
        val onCrest = DealPartyLabels.fromPins(crest, samsung, crest, "crest", samsung, "samsung")
        val onSamsung = DealPartyLabels.fromPins(crest, samsung, samsung, "samsung", crest, "crest")
        assertEquals(onCrest, onSamsung)
    }

    @Test
    fun unknownPinFallsBackToRoleWithoutBorrowingAName() {
        val lines = DealPartyLabels.fromPins(crest, "ac1:OTHER", crest, "crest", samsung, "samsung")
        assertEquals("Party B · Party B (ac1:OTHER)", lines.partyB)
    }
}
