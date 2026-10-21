package com.codeyantra.armorchat.deal.transport

import com.codeyantra.armorchat.deal.domain.LegalNameStatus
import com.codeyantra.armorchat.deal.domain.PartyRole

/**
 * Phase 5.4C: pure presentation logic for the debug Deal Document Viewer.
 * Every line is derived ONLY from a verified [DealDocumentView]; nothing else
 * is a signing source. Any doubt fails closed with an explicit message.
 */
sealed interface DealViewerState {
    class Failed(val code: String, val message: String) : DealViewerState {
        override fun toString(): String = "DealViewerState.Failed($code)"
    }

    class Rendered(
        val view: DealDocumentView,
        val lines: List<DealDocumentLine>,
        /** Non-null when hidden/bidi characters were found: consent is blocked. */
        val hiddenWarning: String?,
    ) : DealViewerState {
        val consentKey: String get() = DealDocumentPresenter.consentKey(view)
        override fun toString(): String = "DealViewerState.Rendered(lines=${lines.size})"
    }
}

class DealDocumentLine(val section: String, val label: String, val value: String) {
    override fun toString(): String = "DealDocumentLine($section/$label)"
}

object DealDocumentPresenter {
    const val UNAVAILABLE = "unavailable"
    const val MISSING_FIELD = "missing_field"
    const val CLAUSE_MISMATCH = "clause_mismatch"
    const val MONEY_MISMATCH = "money_mismatch"

    const val SECTION_DOCUMENT = "Document"
    const val SECTION_PARTIES = "Parties"
    const val SECTION_MONEY = "Amount and payment"
    const val SECTION_DATES = "Dates"
    const val SECTION_CLAUSES = "Terms and obligations"

    fun consentKey(view: DealDocumentView): String = "${view.versionId}:${view.documentHashBase64}"

    fun present(snapshot: DealSigningSnapshot?): DealViewerState =
        if (snapshot == null) failed(UNAVAILABLE) else present(snapshot.document)

    fun present(result: DealDocumentResult): DealViewerState = when (result) {
        is DealDocumentResult.Failed -> failed(result.code)
        is DealDocumentResult.Ready -> present(result.view)
    }

    fun present(view: DealDocumentView): DealViewerState {
        missingField(view)?.let { return failed(MISSING_FIELD) }
        val clauses = view.clauses
        if (DealClauses.join(clauses) != view.terms || clauses.map { it.number } != (1..clauses.size).toList()) {
            return failed(CLAUSE_MISMATCH)
        }
        if (!moneyMatches(view)) return failed(MONEY_MISMATCH)
        return DealViewerState.Rendered(view, lines(view), hiddenWarning(view))
    }

    fun message(code: String): String = when (code) {
        UNAVAILABLE, DealDocumentResult.NOT_LOADED ->
            "This phone could not decrypt or load the agreement. Do not review, confirm or sign."
        DealDocumentResult.HASH_MISMATCH ->
            "The agreement failed its integrity check (hash mismatch). Do not review, confirm or sign."
        DealDocumentResult.ENCODE_FAILED ->
            "The agreement could not be verified. Do not review, confirm or sign."
        DealDocumentResult.MONEY_UNRENDERABLE, MONEY_MISMATCH ->
            "The amount or currency cannot be shown exactly. Do not review, confirm or sign."
        MISSING_FIELD -> "A required field of the agreement is missing. Do not review, confirm or sign."
        CLAUSE_MISMATCH -> "The clauses do not match the signed terms. Do not review, confirm or sign."
        else -> "The agreement cannot be shown safely. Do not review, confirm or sign."
    }

    private fun failed(code: String) = DealViewerState.Failed(code, message(code))

    private fun missingField(view: DealDocumentView): String? {
        if (view.parties.size != 2 || view.parties.map { it.role }.toSet() != PartyRole.entries.toSet()) return "parties"
        if (view.canonicalVersion >= 2) {
            if (view.title == null) return "title"
            if (view.proposerRole == null || view.counterpartyRole == null) return "roles"
            if (view.paymentConditions == null) return "payment"
            if (view.deadlineDate == null) return "deadline"
        }
        if (view.canonicalVersion >= 3 && view.parties.any { it.legalName == null || it.nameStatus == null || it.armorChatId == null }) {
            return "identity"
        }
        if (view.canonicalVersion >= 4 && view.purpose == null) return "purpose"
        return null
    }

    /** The shown text must reproduce exactly the hashed currency and minor-unit integer. */
    internal fun moneyMatches(view: DealDocumentView): Boolean = when (val money = view.money) {
        is DealMoneyText.NoAmount -> view.currency == null && view.amountMinor == null
        is DealMoneyText.Invalid -> false
        is DealMoneyText.Amount -> {
            val expected = DealMoney.format(view.currency, view.amountMinor)
            val digits = money.text.removeSuffix(" ${money.currency}").replace(",", "").replace(".", "").trimStart('0').ifEmpty { "0" }
            expected == money &&
                money.currency == view.currency &&
                money.rawMinor == view.amountMinor &&
                digits == money.rawMinor.toString()
        }
    }

    private fun hiddenWarning(view: DealDocumentView): String? {
        if (view.hiddenCharacters.isEmpty()) return null
        val labels = view.hiddenCharacters.map { it.label }.distinct().joinToString(", ")
        return "This agreement contains ${view.hiddenCharacters.size} invisible or text-direction character(s) " +
            "($labels) that could make the text look different from what is signed. " +
            "The content is shown unchanged and review consent is blocked. Ask the proposer to send a clean revision."
    }

    fun lines(view: DealDocumentView): List<DealDocumentLine> {
        val out = ArrayList<DealDocumentLine>()
        fun add(section: String, label: String, value: String?) {
            out += DealDocumentLine(section, label, value ?: "Not specified")
        }
        add(SECTION_DOCUMENT, "Title", view.title)
        add(SECTION_DOCUMENT, "Purpose", view.purpose)
        add(SECTION_DOCUMENT, "Version ID", view.versionId)
        add(SECTION_DOCUMENT, "Previous version", view.parentVersionId ?: "None (first version)")
        add(SECTION_DOCUMENT, "Document format", "Canonical v${view.canonicalVersion}")
        add(SECTION_DOCUMENT, "Hash fingerprint", view.fingerprint)
        add(SECTION_DOCUMENT, "Full document hash", view.documentHashBase64)
        for (party in view.parties.sortedBy { it.role.code }) {
            val name = partyName(party.role)
            add(SECTION_PARTIES, "$name legal name", party.legalName?.let { "$it (${nameStatus(party.nameStatus)})" })
            add(SECTION_PARTIES, "$name contractual role", party.contractualRole)
            add(SECTION_PARTIES, "$name phone label", party.label)
            add(SECTION_PARTIES, "$name ArmorChat ID", party.armorChatId)
        }
        add(SECTION_MONEY, "Amount", moneyText(view.money))
        add(SECTION_MONEY, "Currency", view.currency ?: "None")
        add(SECTION_MONEY, "Amount in minor units", view.amountMinor?.toString() ?: "None")
        add(SECTION_MONEY, "Payment conditions", view.paymentConditions)
        add(SECTION_DATES, "Deadline", view.deadlineDate)
        add(SECTION_DATES, "Effective date", view.effectiveDate)
        for (clause in view.clauses) {
            add(SECTION_CLAUSES, "Clause ${clause.number}", if (clause.text.isEmpty()) "(blank line)" else clause.text)
        }
        out += DealDocumentLine(SECTION_CLAUSES, "End of document", "${view.clauses.size} clause(s). Version ${view.versionId}.")
        return out
    }

    fun partyName(role: PartyRole): String = when (role) {
        PartyRole.PROPOSER -> "Party A (proposer)"
        PartyRole.COUNTERPARTY -> "Party B (counterparty)"
    }

    private fun nameStatus(status: LegalNameStatus?): String = when (status) {
        LegalNameStatus.SELF_DECLARED_UNVERIFIED -> "self-declared, not government verified"
        LegalNameStatus.UNSET -> "not yet confirmed"
        null -> "unknown"
    }

    fun moneyText(money: DealMoneyText): String = when (money) {
        is DealMoneyText.NoAmount -> DealMoneyText.NoAmount.TEXT
        is DealMoneyText.Amount -> money.display
        is DealMoneyText.Invalid -> "Cannot be shown"
    }
}

/** Why review consent cannot be recorded right now. */
enum class DealConsentBlock { NONE, NOT_RENDERED, HIDDEN_CHARACTERS, NOT_CHECKED, ALREADY_RECORDED, FINAL }

/** Checkbox state bound to one exact version/hash; a different key always resets it. */
data class DealConsentChecklist(val key: String? = null, val checked: Boolean = false) {
    fun forState(state: DealViewerState): DealConsentChecklist {
        val next = (state as? DealViewerState.Rendered)?.consentKey
        return if (next != null && next == key) this else DealConsentChecklist(next, false)
    }

    fun check(value: Boolean, state: DealViewerState): DealConsentChecklist {
        val bound = forState(state)
        return if (bound.key == null) bound else bound.copy(checked = value)
    }

    override fun toString(): String = "DealConsentChecklist(checked=$checked)"
}

object DealConsentGate {
    const val CHECKBOX_TEXT = "I have reviewed the complete agreement for this exact version."

    fun block(state: DealViewerState, checklist: DealConsentChecklist, recorded: Boolean, proven: Boolean): DealConsentBlock {
        if (state !is DealViewerState.Rendered) return DealConsentBlock.NOT_RENDERED
        if (proven) return DealConsentBlock.FINAL
        if (state.hiddenWarning != null || state.view.hiddenCharacters.isNotEmpty()) return DealConsentBlock.HIDDEN_CHARACTERS
        if (recorded) return DealConsentBlock.ALREADY_RECORDED
        if (checklist.key != state.consentKey || !checklist.checked) return DealConsentBlock.NOT_CHECKED
        return DealConsentBlock.NONE
    }
}

/** Which signing controls may be enabled. Service-level gates still apply to every action. */
data class DealSigningControls(
    val review: Boolean,
    val confirmName: Boolean,
    val sign: Boolean,
    val revise: Boolean,
    val reject: Boolean,
) {
    companion object {
        fun from(snapshot: DealSigningSnapshot, state: DealViewerState): DealSigningControls {
            val readable = state is DealViewerState.Rendered &&
                state.hiddenWarning == null &&
                snapshot.ownReviewConsent &&
                (state.view.versionId == snapshot.versionId)
            val final = snapshot.proven || snapshot.stage == com.codeyantra.armorchat.deal.domain.SigningStage.FULLY_SIGNED
            return DealSigningControls(
                review = !final && readable && snapshot.canReview,
                confirmName = !final && readable && snapshot.canConfirmName,
                sign = !final && readable && snapshot.canSign,
                revise = !final && snapshot.canRevise,
                reject = !final && snapshot.canReject,
            )
        }
    }
}

/** Final SIGN dialog content, derived only from the verified document. */
class DealSignConfirmation(val title: String, val body: String) {
    override fun toString(): String = "DealSignConfirmation"

    companion object {
        fun from(view: DealDocumentView, localRole: PartyRole): DealSignConfirmation {
            val parties = view.parties.sortedBy { it.role.code }.joinToString("\n") { party ->
                val you = if (party.role == localRole) " — you" else ""
                "${DealDocumentPresenter.partyName(party.role)}$you: ${party.legalName ?: party.label}" +
                    (party.contractualRole?.let { " ($it)" } ?: "")
            }
            val body = buildString {
                appendLine(view.title ?: "Untitled agreement")
                appendLine("Version: ${view.versionId}")
                appendLine("Fingerprint: ${view.fingerprint}")
                appendLine()
                appendLine(parties)
                appendLine()
                appendLine("Amount: ${DealDocumentPresenter.moneyText(view.money)}")
                appendLine("Payment conditions: ${view.paymentConditions ?: "Not specified"}")
                appendLine("Deadline: ${view.deadlineDate ?: "Not specified"}")
                appendLine()
                append("Signing uses this phone's deal key for exactly this version. It cannot be undone.")
            }
            return DealSignConfirmation("Sign this exact version?", body)
        }
    }
}
