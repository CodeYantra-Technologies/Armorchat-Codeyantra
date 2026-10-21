package com.codeyantra.armorchat.deal.lab

import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

/**
 * What the Armor Deal Lab shows and what canonical version 2 signs.
 * Party names are the party labels. Human roles, title, deadline, and
 * payment conditions are [AgreementDetails]. Amounts are INR paise.
 */
data class LabForm(
    val title: String = "",
    val partyAName: String = "",
    val partyARole: String = "",
    val partyBName: String = "",
    val partyBRole: String = "",
    val amountText: String = "",
    val deadline: String = "",
    val terms: String = "",
    val paymentConditions: String = "",
)

enum class LabField {
    TITLE,
    PARTY_A_NAME,
    PARTY_A_ROLE,
    PARTY_B_NAME,
    PARTY_B_ROLE,
    AMOUNT,
    DEADLINE,
    TERMS,
    PAYMENT,
}

object LabAgreement {
    const val CURRENCY = "INR"
    private const val MAX_NAME_BYTES = 80

    fun sample(terms: String): LabForm = LabForm(
        title = "Synthetic studio rental",
        partyAName = "Local synthetic",
        partyARole = "Client",
        partyBName = "Simulated party",
        partyBRole = "Studio",
        amountText = "1500.50",
        deadline = "2026-12-31",
        terms = terms,
        paymentConditions = "Pay on delivery",
    )

    fun problems(form: LabForm): List<String> = fieldErrors(form).values.toList()

    fun fieldErrors(form: LabForm): Map<LabField, String> {
        val value = form.trimmed()
        val errors = linkedMapOf<LabField, String>()
        textIssue(
            value.title,
            emptyMessage = "Add an agreement title.",
            longMessage = "Shorten the agreement title.",
            controlMessage = "The title cannot contain a line break or a control character.",
            maxBytes = AgreementDetails.MAX_TITLE_BYTES,
            allowNewlines = false,
            emptyAllowed = false,
        )?.let { errors[LabField.TITLE] = it }
        textIssue(
            value.partyAName,
            emptyMessage = "Add Party A's name.",
            longMessage = "Shorten Party A's name.",
            controlMessage = "Party A's name cannot contain a line break or a control character.",
            maxBytes = MAX_NAME_BYTES,
            allowNewlines = false,
            emptyAllowed = false,
        )?.let { errors[LabField.PARTY_A_NAME] = it }
        textIssue(
            value.partyARole,
            emptyMessage = "Add Party A's role. For example, Client.",
            longMessage = "Shorten Party A's role.",
            controlMessage = "Party A's role cannot contain a line break or a control character.",
            maxBytes = AgreementDetails.MAX_ROLE_BYTES,
            allowNewlines = false,
            emptyAllowed = false,
        )?.let { errors[LabField.PARTY_A_ROLE] = it }
        textIssue(
            value.partyBName,
            emptyMessage = "Add Party B's name.",
            longMessage = "Shorten Party B's name.",
            controlMessage = "Party B's name cannot contain a line break or a control character.",
            maxBytes = MAX_NAME_BYTES,
            allowNewlines = false,
            emptyAllowed = false,
        )?.let { errors[LabField.PARTY_B_NAME] = it }
        textIssue(
            value.partyBRole,
            emptyMessage = "Add Party B's role. For example, Studio.",
            longMessage = "Shorten Party B's role.",
            controlMessage = "Party B's role cannot contain a line break or a control character.",
            maxBytes = AgreementDetails.MAX_ROLE_BYTES,
            allowNewlines = false,
            emptyAllowed = false,
        )?.let { errors[LabField.PARTY_B_ROLE] = it }
        if (InrMoney.paiseOrNull(value.amountText) == null) {
            errors[LabField.AMOUNT] =
                "Enter the amount in rupees, such as 1500 or 1500.50. Use at most two decimal places."
        }
        deadlineIssue(value.deadline)?.let { errors[LabField.DEADLINE] = it }
        textIssue(
            value.terms,
            emptyMessage = "Add the agreement terms.",
            longMessage = "Shorten the agreement terms.",
            controlMessage = "The terms contain a control character that cannot be signed.",
            maxBytes = CanonicalDeal.MAX_TERMS_BYTES,
            allowNewlines = true,
            emptyAllowed = false,
        )?.let { errors[LabField.TERMS] = it }
        textIssue(
            value.paymentConditions,
            emptyMessage = "",
            longMessage = "Shorten the payment conditions.",
            controlMessage = "The payment conditions contain a control character that cannot be signed.",
            maxBytes = AgreementDetails.MAX_PAYMENT_BYTES,
            allowNewlines = true,
            emptyAllowed = true,
        )?.let { errors[LabField.PAYMENT] = it }
        return errors
    }

    fun details(form: LabForm): AgreementDetails {
        val value = form.trimmed()
        return AgreementDetails(
            title = value.title,
            deadlineDate = value.deadline,
            paymentConditions = value.paymentConditions,
            proposerRole = value.partyARole,
            counterpartyRole = value.partyBRole,
        )
    }
}

object InrMoney {
    private val pattern = Regex("^[0-9]+(\\.[0-9]{1,2})?$")

    /** Rupees as typed by a person, converted to paise. Null when the text is not a canonical amount. */
    fun paiseOrNull(text: String): Long? {
        val trimmed = text.trim()
        if (!pattern.matches(trimmed)) return null
        val parts = trimmed.split('.')
        val rupees = parts[0].toLongOrNull() ?: return null
        if (rupees > CanonicalDeal.MAX_AMOUNT_MINOR / 100) return null
        val fraction = if (parts.size == 1) 0 else parts[1].padEnd(2, '0').toInt()
        val paise = rupees * 100 + fraction
        if (paise > CanonicalDeal.MAX_AMOUNT_MINOR) return null
        return paise
    }

    fun format(paise: Long): String {
        val sign = if (paise < 0) "-" else ""
        val absolute = kotlin.math.abs(paise)
        val rupees = absolute / 100
        val fraction = (absolute % 100).toString().padStart(2, '0')
        return "$sign₹$rupees.$fraction ($absolute paise)"
    }
}

fun LabForm.trimmed(): LabForm = copy(
    title = title.trim(),
    partyAName = partyAName.trim(),
    partyARole = partyARole.trim(),
    partyBName = partyBName.trim(),
    partyBRole = partyBRole.trim(),
    amountText = amountText.trim(),
    deadline = deadline.trim(),
    terms = terms.trim(),
    paymentConditions = paymentConditions.trim(),
)

private fun deadlineIssue(value: String): String? {
    if (!value.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))) {
        return "Enter the delivery or completion deadline as YYYY-MM-DD, such as 2026-12-31."
    }
    val parsed = try {
        LocalDate.parse(value)
    } catch (e: DateTimeException) {
        return "That deadline is not a real calendar date."
    }
    if (parsed.toString() != value) {
        return "Enter the deadline as YYYY-MM-DD, such as 2026-12-31."
    }
    return null
}

private fun textIssue(
    value: String,
    emptyMessage: String,
    longMessage: String,
    controlMessage: String,
    maxBytes: Int,
    allowNewlines: Boolean,
    emptyAllowed: Boolean,
): String? {
    if (!emptyAllowed && value.isEmpty()) return emptyMessage
    if (Normalizer.normalize(value, Normalizer.Form.NFC) != value) {
        return "Use ordinary text. Combined accent marks have to be composed first."
    }
    if (value.toByteArray(Charsets.UTF_8).size > maxBytes) return longMessage
    for (ch in value) {
        val allowedBreak = allowNewlines && (ch == '\n' || ch == '\t')
        if ((ch.code < 0x20 || ch.code == 0x7f) && !allowedBreak) return controlMessage
    }
    return null
}
