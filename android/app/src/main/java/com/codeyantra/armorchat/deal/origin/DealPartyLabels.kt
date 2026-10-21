package com.codeyantra.armorchat.deal.origin

/**
 * Party labels come from the immutable identity pins of the deal, never from
 * which phone is displaying it: PROPOSER is always Party A and COUNTERPARTY is
 * always Party B. Display names are looked up by ArmorChat id only.
 */
object DealPartyLabels {
    data class Lines(val partyA: String, val partyB: String, val partyAName: String, val partyBName: String)

    fun fromPins(
        proposerArmorChatId: String,
        counterpartyArmorChatId: String,
        localArmorChatId: String,
        localDisplayName: String,
        contactArmorChatId: String,
        contactDisplayName: String,
    ): Lines {
        fun nameFor(id: String): String = when (id) {
            localArmorChatId -> localDisplayName
            contactArmorChatId -> contactDisplayName
            else -> ""
        }
        val aName = nameFor(proposerArmorChatId)
        val bName = nameFor(counterpartyArmorChatId)
        return Lines(
            partyA = line("Party A", aName, proposerArmorChatId),
            partyB = line("Party B", bName, counterpartyArmorChatId),
            partyAName = aName,
            partyBName = bName,
        )
    }

    fun line(role: String, name: String, armorChatId: String): String {
        val label = name.ifBlank { role }
        return "$role · $label ($armorChatId)"
    }
}
