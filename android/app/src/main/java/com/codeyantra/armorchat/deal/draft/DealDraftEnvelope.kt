package com.codeyantra.armorchat.deal.draft

import com.codeyantra.armorchat.deal.crypto.DealIdentityCrypto
import com.codeyantra.armorchat.deal.domain.AgreementDetails
import com.codeyantra.armorchat.deal.domain.ByteArrayOutput
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIdentity
import com.codeyantra.armorchat.deal.domain.PartyRole
import com.codeyantra.armorchat.deal.domain.VerifiedDealParticipant
import com.codeyantra.armorchat.deal.domain.requireCanonicalDeadline
import com.codeyantra.armorchat.deal.domain.requireCanonicalText
import com.codeyantra.armorchat.deal.domain.requireLegalName
import com.codeyantra.armorchat.deal.domain.validateMoney
import com.codeyantra.armorchat.deal.origin.ChatDealIds
import com.codeyantra.armorchat.deal.origin.ChatDealOrigin
import com.codeyantra.armorchat.deal.origin.PendingChatDeal
import java.nio.charset.StandardCharsets

/**
 * What Party A can edit before a deal is sent. Contractual roles are not
 * Party A or Party B. A legal name typed here is not a confirmation signature.
 */
class DealDraftEditor(
    val title: String,
    val purpose: String,
    val proposerRole: String,
    val counterpartyRole: String,
    val terms: String,
    val paymentConditions: String,
    val currency: String?,
    val amountMinor: Long?,
    val deadlineDate: String,
    val effectiveDate: String,
    val partyALegalName: String,
) {
    val details: AgreementDetails = text("Title, roles, deadline, or payment conditions") {
        AgreementDetails(
            title = title,
            deadlineDate = deadlineDate,
            paymentConditions = paymentConditions,
            proposerRole = proposerRole,
            counterpartyRole = counterpartyRole,
        )
    }

    init {
        text("Purpose") {
            requireCanonicalText(
                purpose,
                allowNewlines = false,
                maxBytes = DealDrafts.MAX_PURPOSE_BYTES,
                emptyAllowed = false,
            )
        }
        text("Terms") {
            requireCanonicalText(
                terms,
                allowNewlines = true,
                maxBytes = CanonicalDeal.MAX_TERMS_BYTES,
                emptyAllowed = false,
            )
        }
        if (effectiveDate.isNotEmpty()) {
            text("Effective date") { requireCanonicalDeadline(effectiveDate) }
        }
        if (partyALegalName.isNotEmpty()) {
            text("Legal name") { requireLegalName(partyALegalName) }
        }
        text("Amount") { validateMoney(currency, amountMinor) }
    }

    override fun toString(): String = "DealDraftEditor"

    private inline fun <T> text(label: String, block: () -> T): T {
        try {
            return block()
        } catch (e: DealFormatException) {
            throw DealFormatException("$label: ${e.message}")
        }
    }
}

/**
 * Plaintext body of one local draft. Magic is ADDF, not the canonical ADCB
 * magic and not the sealed-document ADEC magic. Party B's deal keys, binding,
 * and legal name are absent. Purpose and the optional effective date are not
 * canonical version 1, 2, or 3 fields. A sent proposal carries them in canonical version 4.
 */
class DealDraftEnvelope internal constructor(
    val pending: PendingChatDeal,
    val purpose: String,
    val effectiveDate: String,
    val partyALegalName: String,
) {
    init {
        require(pending.ids.proposerPartyId.isNotEmpty())
        if (partyALegalName.isNotEmpty()) requireLegalName(partyALegalName)
        if (effectiveDate.isNotEmpty()) requireCanonicalDeadline(effectiveDate)
        requireCanonicalText(
            purpose,
            allowNewlines = false,
            maxBytes = DealDrafts.MAX_PURPOSE_BYTES,
            emptyAllowed = false,
        )
    }

    fun withContent(editor: DealDraftEditor): DealDraftEnvelope = DealDraftEnvelope(
        pending = PendingChatDeal(
            origin = pending.origin,
            ids = pending.ids,
            createdAtEpochMs = pending.createdAtEpochMs,
            currency = editor.currency,
            amountMinor = editor.amountMinor,
            terms = editor.terms,
            details = editor.details,
            proposerLabel = pending.proposerLabel,
            counterpartyLabel = pending.counterpartyLabel,
            proposerSigningPublic = pending.proposerSigningPublic.copyOf(),
            proposerEncryptionPublic = pending.proposerEncryptionPublic.copyOf(),
            proposerBinding = pending.proposerBinding.copyOf(),
        ),
        purpose = editor.purpose,
        effectiveDate = editor.effectiveDate,
        partyALegalName = editor.partyALegalName,
    )

    fun editor(): DealDraftEditor = DealDraftEditor(
        title = pending.details.title,
        purpose = purpose,
        proposerRole = pending.details.proposerRole,
        counterpartyRole = pending.details.counterpartyRole,
        terms = pending.terms,
        paymentConditions = pending.details.paymentConditions,
        currency = pending.currency,
        amountMinor = pending.amountMinor,
        deadlineDate = pending.details.deadlineDate,
        effectiveDate = effectiveDate,
        partyALegalName = partyALegalName,
    )

    override fun toString(): String = "DealDraftEnvelope"

    companion object {
        private val MAGIC = byteArrayOf(0x41, 0x44, 0x44, 0x46) // ADDF
        private const val VERSION = 1

        fun encode(envelope: DealDraftEnvelope): ByteArray {
            val pending = envelope.pending
            val out = ByteArrayOutput()
            out.raw(MAGIC)
            out.u8(VERSION)
            out.lp(pending.ids.dealId)
            out.lp(pending.ids.versionId)
            out.lp(pending.ids.proposerPartyId)
            out.lp(pending.ids.counterpartyPartyId)
            out.lp(pending.origin.conversationId)
            out.lp(pending.origin.contactId)
            out.u64(pending.createdAtEpochMs)
            out.lp(pending.origin.partyA.armorChatId)
            writeKey(out, pending.origin.partyA.messagingPublicKey)
            out.lp(pending.proposerLabel)
            out.lp(pending.origin.partyB.armorChatId)
            writeKey(out, pending.origin.partyB.messagingPublicKey)
            out.lp(pending.counterpartyLabel)
            writeKey(out, pending.proposerSigningPublic)
            writeKey(out, pending.proposerEncryptionPublic)
            writeSignature(out, pending.proposerBinding)
            out.lp(pending.details.title)
            out.lp(envelope.purpose)
            out.lp(pending.details.proposerRole)
            out.lp(pending.details.counterpartyRole)
            val terms = pending.terms.toByteArray(StandardCharsets.UTF_8)
            out.u32(terms.size)
            out.raw(terms)
            out.lp(pending.details.paymentConditions)
            if (pending.currency == null) {
                out.u8(0)
            } else {
                out.u8(3)
                out.raw(pending.currency.toByteArray(StandardCharsets.US_ASCII))
            }
            if (pending.amountMinor == null) {
                out.u8(0)
            } else {
                out.u8(1)
                out.u64(pending.amountMinor)
            }
            out.lp(pending.details.deadlineDate)
            out.lp(envelope.effectiveDate)
            out.lp(envelope.partyALegalName)
            return out.toByteArray()
        }

        fun parse(bytes: ByteArray): DealDraftEnvelope {
            if (bytes.size > CanonicalDeal.MAX_TERMS_BYTES + 16_384) {
                throw DealFormatException("Draft envelope is too large.")
            }
            val cursor = Cursor(bytes)
            val magic = cursor.bytes(4)
            if (!magic.contentEquals(MAGIC)) throw DealFormatException("Not an Armor Deal draft.")
            if (cursor.u8() != VERSION) throw DealFormatException("Unsupported draft envelope.")
            val ids = ChatDealIds(
                dealId = cursor.lp(),
                versionId = cursor.lp(),
                proposerPartyId = cursor.lp(),
                counterpartyPartyId = cursor.lp(),
            )
            val conversationId = cursor.lp()
            val contactId = cursor.lp()
            if (conversationId.isEmpty() || contactId.isEmpty()) {
                throw DealFormatException("The conversation is missing.")
            }
            val createdAt = cursor.u64()
            if (createdAt <= 0L || createdAt > CanonicalDeal.MAX_TIMESTAMP_MS) {
                throw DealFormatException("Timestamp is outside the accepted range.")
            }
            val partyA = VerifiedDealParticipant.verify(cursor.lp(), cursor.key())
            val proposerLabel = cursor.lp()
            val partyB = VerifiedDealParticipant.verify(cursor.lp(), cursor.key())
            val counterpartyLabel = cursor.lp()
            val signing = cursor.key()
            val encryption = cursor.key()
            val binding = cursor.signature()
            val title = cursor.lp()
            val purpose = cursor.lp()
            val proposerRole = cursor.lp()
            val counterpartyRole = cursor.lp()
            val termsLength = cursor.u32()
            if (termsLength > CanonicalDeal.MAX_TERMS_BYTES) throw DealFormatException("Terms are too long.")
            val terms = utf8(cursor.bytes(termsLength.toInt()))
            val payment = cursor.lp()
            val currency = when (val length = cursor.u8()) {
                0 -> null
                3 -> {
                    val text = String(cursor.bytes(3), StandardCharsets.US_ASCII)
                    if (!text.matches(Regex("^[A-Z]{3}$"))) throw DealFormatException("Currency is not canonical.")
                    text
                }
                else -> throw DealFormatException("Currency length is not canonical.")
            }
            val amount = when (val flag = cursor.u8()) {
                0 -> null
                1 -> cursor.u64()
                else -> throw DealFormatException("Amount flag is not canonical.")
            }
            val deadline = cursor.lp()
            val effectiveDate = cursor.lp()
            val legalName = cursor.lp()
            if (cursor.remaining() != 0) throw DealFormatException("Draft envelope has trailing bytes.")
            if (partyA.armorChatId == partyB.armorChatId ||
                partyA.messagingPublicKey.contentEquals(partyB.messagingPublicKey)
            ) {
                throw DealFormatException("A participant cannot deal with themself.")
            }
            val payload = DealIdentity.bindingPayload(
                dealId = ids.dealId,
                role = PartyRole.PROPOSER,
                participant = partyA,
                dealSigningPublicKey = signing,
                dealEncryptionPublicKey = encryption,
            )
            DealIdentityCrypto.verify(partyA.messagingPublicKey, binding, payload)
            val pending = PendingChatDeal(
                origin = ChatDealOrigin(conversationId, contactId, partyA, partyB),
                ids = ids,
                createdAtEpochMs = createdAt,
                currency = currency,
                amountMinor = amount,
                terms = terms,
                details = AgreementDetails(title, deadline, payment, proposerRole, counterpartyRole),
                proposerLabel = proposerLabel,
                counterpartyLabel = counterpartyLabel,
                proposerSigningPublic = signing,
                proposerEncryptionPublic = encryption,
                proposerBinding = binding,
            )
            return DealDraftEnvelope(pending, purpose, effectiveDate, legalName)
        }

        private fun writeKey(out: ByteArrayOutput, key: ByteArray) {
            out.u16(key.size)
            out.raw(key)
        }

        private fun writeSignature(out: ByteArrayOutput, signature: ByteArray) {
            out.u16(signature.size)
            out.raw(signature)
        }

        private fun utf8(bytes: ByteArray): String {
            val text = String(bytes, StandardCharsets.UTF_8)
            if (!text.toByteArray(StandardCharsets.UTF_8).contentEquals(bytes)) {
                throw DealFormatException("Text is not valid UTF-8.")
            }
            return text
        }
    }
}

private class Cursor(private val data: ByteArray) {
    private var index = 0

    fun remaining(): Int = data.size - index

    fun u8(): Int {
        if (index >= data.size) throw DealFormatException("Draft envelope is truncated.")
        return data[index++].toInt() and 0xff
    }

    fun u16(): Int = (u8() shl 8) or u8()

    fun u32(): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or u8().toLong() }
        return value
    }

    fun u64(): Long {
        var value = 0L
        repeat(8) {
            val next = u8().toLong()
            if (value > (Long.MAX_VALUE shr 8)) throw DealFormatException("Integer is out of range.")
            value = (value shl 8) or next
        }
        return value
    }

    fun bytes(count: Int): ByteArray {
        if (count < 0 || remaining() < count) throw DealFormatException("Draft envelope is truncated.")
        val slice = data.copyOfRange(index, index + count)
        index += count
        return slice
    }

    fun lp(): String {
        val length = u16()
        val raw = bytes(length)
        val text = String(raw, StandardCharsets.UTF_8)
        if (!text.toByteArray(StandardCharsets.UTF_8).contentEquals(raw)) {
            throw DealFormatException("Text is not valid UTF-8.")
        }
        return text
    }

    fun key(): ByteArray {
        val length = u16()
        if (length != 32) throw DealFormatException("Identity key length is not canonical.")
        return bytes(32)
    }

    fun signature(): ByteArray {
        val length = u16()
        if (length != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        return bytes(64)
    }
}
