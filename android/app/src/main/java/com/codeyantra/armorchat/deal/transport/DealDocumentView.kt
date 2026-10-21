package com.codeyantra.armorchat.deal.transport

import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealException
import com.codeyantra.armorchat.deal.domain.LegalNameStatus
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.domain.identity.Base64Url
import java.math.BigInteger
import java.security.MessageDigest
import java.util.Currency

/**
 * Phase 5.4B: every field of one decrypted, parsed canonical document, for a
 * full read before review or signing. It is built ONLY from [CanonicalDeal]
 * after a re-encode self-check against the stored document hash.
 * [toString] never prints terms, names, or other contract text.
 */
class DealDocumentView internal constructor(
    val dealId: String,
    val versionId: String,
    val parentVersionId: String?,
    val canonicalVersion: Int,
    documentHash: ByteArray,
    val title: String?,
    val purpose: String?,
    val proposerRole: String?,
    val counterpartyRole: String?,
    val paymentConditions: String?,
    val currency: String?,
    val amountMinor: Long?,
    val money: DealMoneyText,
    val deadlineDate: String?,
    val effectiveDate: String?,
    val terms: String,
    val clauses: List<DealClause>,
    val hiddenCharacters: List<DealHiddenCharacter>,
    val parties: List<DealPartyView>,
) {
    private val hash = documentHash.copyOf()

    /** Raw SHA-256 of the canonical bytes; a copy each call. */
    val documentHash: ByteArray get() = hash.copyOf()

    val documentHashBase64: String get() = Base64Url.encode(hash)

    /** Short human fingerprint: first 16 bytes as four hex groups of 8. */
    val fingerprint: String get() = hash.copyOf(16).joinToString("") { "%02x".format(it) }.chunked(8).joinToString(" ")

    override fun toString(): String = "DealDocumentView(version=$canonicalVersion, clauses=${clauses.size})"

    companion object {
        /**
         * Builds the view. Fails closed when the parsed document does not
         * re-encode to [storedDocumentSha256] (base64url) or any field fails.
         */
        fun from(deal: CanonicalDeal, storedDocumentSha256: String): DealDocumentResult {
            val encoded = try {
                CanonicalEncoding.encode(deal)
            } catch (e: DealException) {
                return DealDocumentResult.Failed(DealDocumentResult.ENCODE_FAILED)
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(encoded)
            encoded.fill(0)
            val stored = try {
                Base64Url.decode(storedDocumentSha256)
            } catch (e: IllegalArgumentException) {
                return DealDocumentResult.Failed(DealDocumentResult.HASH_MISMATCH)
            }
            if (!MessageDigest.isEqual(digest, stored) || !MessageDigest.isEqual(digest, deal.documentHash())) {
                return DealDocumentResult.Failed(DealDocumentResult.HASH_MISMATCH)
            }
            val money = DealMoney.format(deal.currency, deal.amountMinor)
            if (money is DealMoneyText.Invalid) return DealDocumentResult.Failed(DealDocumentResult.MONEY_UNRENDERABLE)
            val pins = deal.identity?.pins.orEmpty()
            val parties = deal.parties.map { party ->
                val pin = pins.firstOrNull { it.partyId == party.partyId }
                DealPartyView(
                    partyId = party.partyId,
                    role = party.role,
                    label = party.label,
                    contractualRole = when (party.role) {
                        PartyRole.PROPOSER -> deal.details?.proposerRole
                        PartyRole.COUNTERPARTY -> deal.details?.counterpartyRole
                    },
                    armorChatId = pin?.participant?.armorChatId,
                    legalName = pin?.legalName,
                    nameStatus = pin?.nameStatus,
                )
            }
            val textFields = listOfNotNull(
                deal.terms,
                deal.details?.title,
                deal.details?.paymentConditions,
                deal.details?.proposerRole,
                deal.details?.counterpartyRole,
                deal.annex?.purpose,
            ) + parties.map { it.label } + parties.mapNotNull { it.legalName }
            return DealDocumentResult.Ready(
                DealDocumentView(
                    dealId = deal.dealId,
                    versionId = deal.versionId,
                    parentVersionId = deal.parentVersionId,
                    canonicalVersion = deal.canonicalVersion,
                    documentHash = digest,
                    title = deal.details?.title,
                    purpose = deal.annex?.purpose,
                    proposerRole = deal.details?.proposerRole,
                    counterpartyRole = deal.details?.counterpartyRole,
                    paymentConditions = deal.details?.paymentConditions,
                    currency = deal.currency,
                    amountMinor = deal.amountMinor,
                    money = money,
                    deadlineDate = deal.details?.deadlineDate,
                    effectiveDate = deal.annex?.effectiveDate,
                    terms = deal.terms,
                    clauses = DealClauses.split(deal.terms),
                    hiddenCharacters = textFields.flatMap { DealTextScan.hidden(it) },
                    parties = parties,
                ),
            )
        }
    }
}

class DealPartyView(
    val partyId: String,
    val role: PartyRole,
    val label: String,
    val contractualRole: String?,
    val armorChatId: String?,
    val legalName: String?,
    val nameStatus: LegalNameStatus?,
) {
    override fun toString(): String = "DealPartyView(role=$role)"
}

sealed interface DealDocumentResult {
    class Ready(val view: DealDocumentView) : DealDocumentResult {
        override fun toString(): String = "DealDocumentResult.Ready"
    }

    /** Fail closed: the document must not be shown as reviewable. [code] carries no contract text. */
    class Failed(val code: String) : DealDocumentResult {
        override fun toString(): String = "DealDocumentResult.Failed($code)"
    }

    companion object {
        const val HASH_MISMATCH = "hash_mismatch"
        const val ENCODE_FAILED = "encode_failed"
        const val MONEY_UNRENDERABLE = "money_unrenderable"
        const val NOT_LOADED = "not_loaded"
    }
}

sealed interface DealMoneyText {
    data object NoAmount : DealMoneyText {
        const val TEXT = "No amount specified"
    }

    /** [text] e.g. "1,500.50 INR"; [rawMinor] is the exact canonical integer. */
    data class Amount(val text: String, val currency: String, val rawMinor: Long, val fractionDigits: Int) : DealMoneyText {
        val display: String get() = "$text ($rawMinor minor units)"
    }

    data class Invalid(val reason: String) : DealMoneyText
}

object DealMoney {
    fun format(currency: String?, amountMinor: Long?): DealMoneyText {
        if (currency == null && amountMinor == null) return DealMoneyText.NoAmount
        if (currency == null || amountMinor == null) return DealMoneyText.Invalid("incomplete")
        if (amountMinor < 0) return DealMoneyText.Invalid("negative_amount")
        val instance = try {
            Currency.getInstance(currency)
        } catch (e: IllegalArgumentException) {
            return DealMoneyText.Invalid("unknown_currency")
        }
        val digits = instance.defaultFractionDigits
        if (digits < 0) return DealMoneyText.Invalid("no_minor_unit")
        val scale = BigInteger.TEN.pow(digits)
        val parts = BigInteger.valueOf(amountMinor).divideAndRemainder(scale)
        val major = parts[0].toString().reversed().chunked(3).joinToString(",").reversed()
        val text = if (digits == 0) major else major + "." + parts[1].toString().padStart(digits, '0')
        return DealMoneyText.Amount("$text $currency", currency, amountMinor, digits)
    }
}

/** One terms line. [text] is an exact substring; joining all clauses with "\n" restores the terms. */
class DealClause(val number: Int, val text: String) {
    override fun toString(): String = "DealClause($number)"
}

object DealClauses {
    fun split(terms: String): List<DealClause> =
        terms.split('\n').mapIndexed { index, line -> DealClause(index + 1, line) }

    fun join(clauses: List<DealClause>): String = clauses.joinToString("\n") { it.text }
}

/** A character that renders invisibly or reorders text. [index] is the UTF-16 offset. */
data class DealHiddenCharacter(val index: Int, val codePoint: Int, val kind: Kind) {
    enum class Kind { BIDI_CONTROL, ZERO_WIDTH, SOFT_HYPHEN, OTHER_FORMAT }

    val label: String get() = "U+%04X".format(codePoint)
}

object DealTextScan {
    private val bidi = setOf(0x061C, 0x200E, 0x200F) + (0x202A..0x202E) + (0x2066..0x2069)
    private val zeroWidth = setOf(0x180E, 0x200B, 0x200C, 0x200D, 0x2060, 0x2061, 0x2062, 0x2063, 0x2064, 0xFEFF)

    fun hidden(text: String): List<DealHiddenCharacter> {
        val out = ArrayList<DealHiddenCharacter>()
        var index = 0
        while (index < text.length) {
            val cp = text.codePointAt(index)
            val kind = when {
                cp in bidi -> DealHiddenCharacter.Kind.BIDI_CONTROL
                cp in zeroWidth -> DealHiddenCharacter.Kind.ZERO_WIDTH
                cp == 0x00AD -> DealHiddenCharacter.Kind.SOFT_HYPHEN
                Character.getType(cp) == Character.FORMAT.toInt() -> DealHiddenCharacter.Kind.OTHER_FORMAT
                else -> null
            }
            if (kind != null) out += DealHiddenCharacter(index, cp, kind)
            index += Character.charCount(cp)
        }
        return out
    }
}

/** Phase 5.4D: local, version/hash-bound review consent stored as a deal audit row. */
object DealReviewConsent {
    const val ACTION = "review_consent"

    fun outcome(versionId: String, documentSha256: String, partyId: String): String =
        "ok:v1:$versionId:$documentSha256:$partyId"
}
