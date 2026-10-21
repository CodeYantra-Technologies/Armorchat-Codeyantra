package com.codeyantra.armorchat.deal.domain

import com.codeyantra.armorchat.deal.DealFeature
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer
import java.time.DateTimeException
import java.time.LocalDate

enum class PartyRole(val code: Int) {
    PROPOSER(1),
    COUNTERPARTY(2),
    ;

    companion object {
        fun fromCode(code: Int): PartyRole =
            entries.firstOrNull { it.code == code } ?: throw DealFormatException("Unknown party role.")
    }
}

enum class SignAction(val wire: String, val code: Int) {
    SIGN("SIGN", 1),
    REJECT("REJECT", 2),
    ;

    companion object {
        fun fromCode(code: Int): SignAction =
            entries.firstOrNull { it.code == code } ?: throw DealFormatException("Unknown signature action.")

        fun fromWire(wire: String): SignAction =
            entries.firstOrNull { it.wire == wire } ?: throw DealFormatException("Unknown signature action.")
    }
}

/**
 * One party on a canonical agreement. Public keys are raw 32-byte values.
 * The Ed25519 signing key and the X25519 HPKE key are different keys.
 */
class DealParty(
    val partyId: String,
    val role: PartyRole,
    val label: String,
    val signingPublicKey: ByteArray,
    val encryptionPublicKey: ByteArray,
) {
    init {
        DealIds.require(partyId)
        requireCanonicalText(label, allowNewlines = false, maxBytes = 80, emptyAllowed = false)
        requireRawKey(signingPublicKey, "signing")
        requireRawKey(encryptionPublicKey, "encryption")
        if (signingPublicKey.contentEquals(encryptionPublicKey)) {
            throw DealFormatException("Signing and encryption keys must be distinct.")
        }
    }
}

/**
 * Armor Deal Canonical Bytes. Version 1 is the original layout. Version 2 appends agreement details.
 *
 * This is a fixed big-endian layout, not JSON. The same logical agreement
 * always produces the same bytes. Money is an integer count of minor units.
 *
 * ```
 * magic "ADCB" | version u8
 * dealId, versionId, parentVersionId : u16-length UTF-8 (parent length 0 if genesis)
 * createdAtEpochMs u64
 * currencyLength u8 (0 or 3) | currency ASCII
 * amountPresent u8 (0 or 1) | amountMinor u64 when present
 * partyCount u16=2
 * parties, strictly sorted by partyId bytes:
 *   partyId, role u8, label, signingKey u16+32, encryptionKey u16+32
 * ancestryCount u16 | ancestry version ids in historical order
 * termsLength u32 | terms UTF-8
 * ```
 *
 * Version 1 stops there. Version 2 appends five length-prefixed strings:
 * title, deadline (`YYYY-MM-DD`), payment conditions, proposer role, and
 * counterparty role. A version 1 document has no [AgreementDetails].
 *
 * Version 3 appends the version 2 strings and then an identity section:
 * pinned ArmorChat IDs, messaging public keys, deal signing and encryption
 * keys, binding signatures, and legal-name slots. A missing binding or an
 * empty legal name is a draft. Parsing still rejects unsorted parties,
 * non-NFC text, truncated input, trailing bytes, and an identity section
 * that does not match the parties. Version 1 and version 2 bytes are unchanged.
 *
 * Version 4 appends purpose and an optional effective date after that identity
 * section. Those fields are contractual. They are not written onto version 1, 2, or 3.
 */
class CanonicalDeal private constructor(
    val dealId: String,
    val versionId: String,
    val parentVersionId: String?,
    val createdAtEpochMs: Long,
    val currency: String?,
    val amountMinor: Long?,
    val parties: List<DealParty>,
    val ancestry: List<String>,
    val terms: String,
    val details: AgreementDetails? = null,
    val identity: DealIdentityBlock? = null,
    val annex: ContractAnnex? = null,
) {
    init {
        DealIds.require(dealId)
        DealIds.require(versionId)
        if (parentVersionId != null) DealIds.require(parentVersionId)
        if (createdAtEpochMs <= 0L || createdAtEpochMs > MAX_TIMESTAMP_MS) {
            throw DealFormatException("Timestamp is outside the accepted range.")
        }
        validateMoney(currency, amountMinor)
        if (parties.size != 2) throw DealFormatException("Phase 1 agreements have two parties.")
        if (parties.map { it.partyId }.distinct().size != 2) {
            throw DealFormatException("Party identifiers must be unique.")
        }
        if (parties.map { it.role }.toSet() != setOf(PartyRole.PROPOSER, PartyRole.COUNTERPARTY)) {
            throw DealFormatException("One proposer and one counterparty are required.")
        }
        for (index in 1 until parties.size) {
            if (compareOpaque(parties[index - 1].partyId, parties[index].partyId) >= 0) {
                throw DealFormatException("Parties are not strictly sorted.")
            }
        }
        if (parties[0].signingPublicKey.contentEquals(parties[1].signingPublicKey) ||
            parties[0].encryptionPublicKey.contentEquals(parties[1].encryptionPublicKey)
        ) {
            throw DealFormatException("Parties must have distinct public keys.")
        }
        validateAncestry(versionId, parentVersionId, ancestry)
        requireCanonicalText(terms, allowNewlines = true, maxBytes = MAX_TERMS_BYTES, emptyAllowed = false)
        if (details != null && details.canonicalVersion != DealFeature.CANONICAL_VERSION_V2) {
            throw DealFormatException("Agreement details do not match a supported canonical version.")
        }
        if (identity != null && details == null) {
            throw DealFormatException("Canonical version 3 requires agreement details.")
        }
        if (annex != null && (identity == null || details == null)) {
            throw DealFormatException("Canonical version 4 requires identity bindings and agreement details.")
        }
        if (identity != null) DealIdentity.verify(dealId, parties, identity)
    }

    val canonicalVersion: Int
        get() = when {
            annex != null -> DealFeature.CANONICAL_VERSION_V4
            identity != null -> DealFeature.CANONICAL_VERSION_V3
            details != null -> DealFeature.CANONICAL_VERSION_V2
            else -> DealFeature.CANONICAL_VERSION
        }

    fun withIdentity(identity: DealIdentityBlock): CanonicalDeal = create(
        dealId = dealId,
        versionId = versionId,
        parentVersionId = parentVersionId,
        createdAtEpochMs = createdAtEpochMs,
        currency = currency,
        amountMinor = amountMinor,
        parties = parties,
        ancestry = ancestry,
        terms = terms,
        details = details,
        identity = identity,
        annex = annex,
    )

    fun party(partyId: String): DealParty =
        parties.firstOrNull { it.partyId == partyId } ?: throw DealCryptoException.UnknownParty()

    fun canonicalBytes(): ByteArray = CanonicalEncoding.encode(this)

    fun documentHash(): ByteArray = sha256(canonicalBytes())

    companion object {
        const val MAX_TERMS_BYTES = 48_000
        const val MAX_TIMESTAMP_MS = 4_102_444_800_000L
        const val MAX_AMOUNT_MINOR = 1_000_000_000_000_000L

        fun create(
            dealId: String,
            versionId: String,
            parentVersionId: String?,
            createdAtEpochMs: Long,
            currency: String?,
            amountMinor: Long?,
            parties: List<DealParty>,
            ancestry: List<String>,
            terms: String,
            details: AgreementDetails? = null,
            identity: DealIdentityBlock? = null,
            annex: ContractAnnex? = null,
        ): CanonicalDeal {
            val sorted = parties.sortedWith { left, right -> compareOpaque(left.partyId, right.partyId) }
            return CanonicalDeal(
                dealId = dealId,
                versionId = versionId,
                parentVersionId = parentVersionId,
                createdAtEpochMs = createdAtEpochMs,
                currency = currency,
                amountMinor = amountMinor,
                parties = sorted,
                ancestry = ancestry,
                terms = terms,
                details = details,
                identity = identity?.alignedTo(sorted),
                annex = annex,
            )
        }

        internal fun fromParsed(
            dealId: String,
            versionId: String,
            parentVersionId: String?,
            createdAtEpochMs: Long,
            currency: String?,
            amountMinor: Long?,
            parties: List<DealParty>,
            ancestry: List<String>,
            terms: String,
            details: AgreementDetails? = null,
            identity: DealIdentityBlock? = null,
            annex: ContractAnnex? = null,
        ): CanonicalDeal = CanonicalDeal(
            dealId, versionId, parentVersionId, createdAtEpochMs, currency, amountMinor,
            parties, ancestry, terms, details, identity, annex,
        )
    }
}

class DealSignature(
    val dealId: String,
    val versionId: String,
    val partyId: String,
    val action: SignAction,
    val signature: ByteArray,
    val signerPublicKey: ByteArray,
) {
    init {
        DealIds.require(dealId)
        DealIds.require(versionId)
        DealIds.require(partyId)
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        requireRawKey(signerPublicKey, "signing")
    }

    override fun toString(): String = "DealSignature(dealId=$dealId, versionId=$versionId, partyId=$partyId, action=$action)"
}

/**
 * A new version is always a new object. The caller keeps [current].
 *
 * [signatureCount] is the number of SIGN signatures on [current]'s version.
 * Zero signatures can be revised while the agreement is still open. Exactly
 * one signature can be revised only from [DealStatus.PARTIALLY_SIGNED], and
 * the old signature is not attached to the result. Two signatures, or any
 * finalized status, refuse the revision.
 */
object DealVersions {
    fun revise(
        current: CanonicalDeal,
        currentBytes: ByteArray,
        signatureCount: Int,
        dealStatus: DealStatus,
        newVersionId: String,
        newTerms: String,
        revisedAtEpochMs: Long,
    ): CanonicalDeal {
        val before = currentBytes.copyOf()
        if (signatureCount < 0) throw DealFormatException("Signature count cannot be negative.")
        if (DealLifecycle.claimsFinality(dealStatus) || signatureCount >= 2) {
            throw ImmutableVersionException()
        }
        if (!DealLifecycle.allowsRevision(dealStatus)) {
            throw DealStateException("This deal can no longer be revised.")
        }
        if (signatureCount == 1 && dealStatus != DealStatus.PARTIALLY_SIGNED) {
            throw ImmutableVersionException()
        }
        val revised = CanonicalDeal.create(
            dealId = current.dealId,
            versionId = newVersionId,
            parentVersionId = current.versionId,
            createdAtEpochMs = revisedAtEpochMs,
            currency = current.currency,
            amountMinor = current.amountMinor,
            parties = current.parties,
            ancestry = current.ancestry + current.versionId,
            terms = newTerms,
            details = current.details,
            identity = current.identity,
            annex = current.annex,
        )
        DealIdentity.requireSameParticipants(current, revised)
        if (!before.contentEquals(currentBytes)) throw ImmutableVersionException()
        if (revised.versionId == current.versionId) throw DealFormatException("A revision needs a new version id.")
        if (revised.canonicalBytes().contentEquals(before)) {
            throw DealFormatException("Revision did not change the canonical document.")
        }
        return revised
    }

    /**
     * Returns [signatures] unchanged when every one is bound to
     * [supersededVersionId]. A signature that already names [successorVersionId],
     * or any other version, is rejected. The successor starts unsigned.
     */
    fun historicalSignatures(
        signatures: List<DealSignature>,
        supersededVersionId: String,
        successorVersionId: String,
    ): List<DealSignature> {
        DealIds.require(supersededVersionId)
        DealIds.require(successorVersionId)
        if (successorVersionId == supersededVersionId) {
            throw DealFormatException("A revision needs a new version id.")
        }
        if (signatures.any { it.versionId == successorVersionId }) {
            throw DealConflictException()
        }
        if (signatures.any { it.versionId != supersededVersionId }) {
            throw DealStateException("A signature cannot move onto another version.")
        }
        return signatures.toList()
    }
}

object CanonicalEncoding {
    private val magic = byteArrayOf(0x41, 0x44, 0x43, 0x42) // ADCB

    fun encode(deal: CanonicalDeal): ByteArray {
        val out = ByteArrayOutput()
        out.raw(magic)
        out.u8(deal.canonicalVersion)
        out.lp(deal.dealId)
        out.lp(deal.versionId)
        out.lp(deal.parentVersionId ?: "")
        out.u64(deal.createdAtEpochMs)
        if (deal.currency == null) {
            out.u8(0)
        } else {
            out.u8(3)
            out.raw(deal.currency.toByteArray(StandardCharsets.US_ASCII))
        }
        if (deal.amountMinor == null) {
            out.u8(0)
        } else {
            out.u8(1)
            out.u64(deal.amountMinor)
        }
        out.u16(deal.parties.size)
        for (party in deal.parties) {
            out.lp(party.partyId)
            out.u8(party.role.code)
            out.lp(party.label)
            out.u16(party.signingPublicKey.size)
            out.raw(party.signingPublicKey)
            out.u16(party.encryptionPublicKey.size)
            out.raw(party.encryptionPublicKey)
        }
        out.u16(deal.ancestry.size)
        for (ancestor in deal.ancestry) out.lp(ancestor)
        val terms = deal.terms.toByteArray(StandardCharsets.UTF_8)
        out.u32(terms.size)
        out.raw(terms)
        deal.details?.let { details ->
            out.lp(details.title)
            out.lp(details.deadlineDate)
            out.lp(details.paymentConditions)
            out.lp(details.proposerRole)
            out.lp(details.counterpartyRole)
        }
        deal.identity?.let { identity ->
            out.u8(DealIdentity.SECTION_VERSION)
            out.u16(identity.pins.size)
            for (pin in identity.pins) {
                out.lp(pin.partyId)
                out.u8(pin.role.code)
                out.lp(pin.participant.armorChatId)
                writeKey(out, pin.participant.messagingPublicKey)
                writeKey(out, pin.dealSigningPublicKey)
                writeKey(out, pin.dealEncryptionPublicKey)
                out.lp(pin.legalName)
                out.u8(pin.nameStatus.code)
                writeSignature(out, pin.bindingSignature)
                writeSignature(out, pin.nameConfirmation)
            }
        }
        deal.annex?.let { annex ->
            out.lp(annex.purpose)
            if (annex.effectiveDate == null) {
                out.u8(0)
            } else {
                out.u8(1)
                out.lp(annex.effectiveDate)
            }
        }
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): CanonicalDeal {
        if (bytes.size > CanonicalDeal.MAX_TERMS_BYTES + 8_192) {
            throw DealFormatException("Canonical document is too large.")
        }
        val cursor = Cursor(bytes)
        val magicBytes = cursor.bytes(4)
        if (!magicBytes.contentEquals(magic)) throw DealFormatException("Not an Armor Deal canonical document.")
        val version = cursor.u8()
        if (version != DealFeature.CANONICAL_VERSION &&
            version != DealFeature.CANONICAL_VERSION_V2 &&
            version != DealFeature.CANONICAL_VERSION_V3 &&
            version != DealFeature.CANONICAL_VERSION_V4
        ) {
            throw DealFormatException("Unsupported canonical version.")
        }
        val dealId = cursor.lp()
        val versionId = cursor.lp()
        val parentRaw = cursor.lp()
        val parent = parentRaw.ifEmpty { null }
        val createdAt = cursor.u64()
        val currencyLength = cursor.u8()
        val currency = when (currencyLength) {
            0 -> null
            3 -> {
                val raw = cursor.bytes(3)
                val text = String(raw, StandardCharsets.US_ASCII)
                if (!text.matches(Regex("^[A-Z]{3}$"))) throw DealFormatException("Currency is not canonical.")
                text
            }
            else -> throw DealFormatException("Currency length is not canonical.")
        }
        val amountPresent = cursor.u8()
        val amount = when (amountPresent) {
            0 -> null
            1 -> cursor.u64()
            else -> throw DealFormatException("Amount flag is not canonical.")
        }
        val partyCount = cursor.u16()
        if (partyCount != 2) throw DealFormatException("Party count is not canonical.")
        val parties = ArrayList<DealParty>(partyCount)
        repeat(partyCount) {
            val partyId = cursor.lp()
            val role = PartyRole.fromCode(cursor.u8())
            val label = cursor.lp()
            val signingLen = cursor.u16()
            if (signingLen != 32) throw DealFormatException("Signing key length is not canonical.")
            val signing = cursor.bytes(32)
            val encryptionLen = cursor.u16()
            if (encryptionLen != 32) throw DealFormatException("Encryption key length is not canonical.")
            val encryption = cursor.bytes(32)
            parties += DealParty(partyId, role, label, signing, encryption)
        }
        val ancestryCount = cursor.u16()
        if (ancestryCount > 64) throw DealFormatException("Ancestry is too long.")
        val ancestry = ArrayList<String>(ancestryCount)
        repeat(ancestryCount) { ancestry += cursor.lp() }
        val termsLength = cursor.u32()
        if (termsLength > CanonicalDeal.MAX_TERMS_BYTES) throw DealFormatException("Terms are too long.")
        val terms = utf8Strict(cursor.bytes(termsLength.toInt()))
        val details = if (version == DealFeature.CANONICAL_VERSION) {
            null
        } else {
            AgreementDetails(
                title = cursor.lp(),
                deadlineDate = cursor.lp(),
                paymentConditions = cursor.lp(),
                proposerRole = cursor.lp(),
                counterpartyRole = cursor.lp(),
            )
        }
        val identity = if (
            version == DealFeature.CANONICAL_VERSION_V3 || version == DealFeature.CANONICAL_VERSION_V4
        ) {
            cursor.identityBlock()
        } else {
            null
        }
        val annex = if (version == DealFeature.CANONICAL_VERSION_V4) cursor.annex() else null
        if (cursor.remaining() != 0) throw DealFormatException("Canonical document has trailing bytes.")
        return CanonicalDeal.fromParsed(
            dealId, versionId, parent, createdAt, currency, amount, parties, ancestry, terms, details, identity, annex,
        )
    }

    private fun writeKey(out: ByteArrayOutput, key: ByteArray) {
        out.u16(key.size)
        out.raw(key)
    }

    private fun writeSignature(out: ByteArrayOutput, signature: ByteArray?) {
        if (signature == null) {
            out.u16(0)
        } else {
            out.u16(signature.size)
            out.raw(signature)
        }
    }
}

/**
 * Fields added by canonical version 2. They are part of the signed bytes.
 * A version 1 document has no [AgreementDetails] and keeps its original encoding.
 */
class AgreementDetails(
    val title: String,
    val deadlineDate: String,
    val paymentConditions: String,
    val proposerRole: String,
    val counterpartyRole: String,
) {
    val canonicalVersion: Int = DealFeature.CANONICAL_VERSION_V2

    init {
        requireCanonicalText(title, allowNewlines = false, maxBytes = MAX_TITLE_BYTES, emptyAllowed = false)
        requireCanonicalDeadline(deadlineDate)
        requireCanonicalText(
            paymentConditions,
            allowNewlines = true,
            maxBytes = MAX_PAYMENT_BYTES,
            emptyAllowed = true,
        )
        requireCanonicalText(proposerRole, allowNewlines = false, maxBytes = MAX_ROLE_BYTES, emptyAllowed = false)
        requireCanonicalText(counterpartyRole, allowNewlines = false, maxBytes = MAX_ROLE_BYTES, emptyAllowed = false)
    }

    companion object {
        const val MAX_TITLE_BYTES = 120
        const val MAX_ROLE_BYTES = 40
        const val MAX_PAYMENT_BYTES = 4_000
    }
}

/**
 * Contractual fields added by canonical version 4. They are part of the signed bytes.
 * A version 1, 2, or 3 document has no [ContractAnnex].
 */
class ContractAnnex(
    val purpose: String,
    val effectiveDate: String?,
) {
    init {
        requireCanonicalText(purpose, allowNewlines = false, maxBytes = MAX_PURPOSE_BYTES, emptyAllowed = false)
        if (effectiveDate != null) requireCanonicalDeadline(effectiveDate)
    }

    override fun toString(): String = "ContractAnnex"

    companion object {
        const val MAX_PURPOSE_BYTES = 400
    }
}

internal fun requireCanonicalDeadline(value: String) {
    if (!value.matches(Regex("^[0-9]{4}-[0-9]{2}-[0-9]{2}$"))) {
        throw DealFormatException("Deadline is not a calendar date.")
    }
    val parsed = try {
        LocalDate.parse(value)
    } catch (e: DateTimeException) {
        throw DealFormatException("Deadline is not a real calendar date.")
    }
    if (parsed.toString() != value) throw DealFormatException("Deadline is not a calendar date.")
}

internal fun compareOpaque(left: String, right: String): Int {
    val a = left.toByteArray(StandardCharsets.UTF_8)
    val b = right.toByteArray(StandardCharsets.UTF_8)
    val length = minOf(a.size, b.size)
    for (index in 0 until length) {
        val difference = (a[index].toInt() and 0xff) - (b[index].toInt() and 0xff)
        if (difference != 0) return difference
    }
    return a.size - b.size
}

internal fun requireRawKey(bytes: ByteArray, label: String) {
    if (bytes.size != 32) throw DealFormatException("The $label public key must be 32 bytes.")
}

internal fun requireCanonicalText(value: String, allowNewlines: Boolean, maxBytes: Int, emptyAllowed: Boolean) {
    if (!emptyAllowed && value.isEmpty()) throw DealFormatException("Text is empty.")
    if (Normalizer.normalize(value, Normalizer.Form.NFC) != value) {
        throw DealFormatException("Text is not NFC.")
    }
    val encoded = value.toByteArray(StandardCharsets.UTF_8)
    if (encoded.size > maxBytes) throw DealFormatException("Text is too long.")
    if (utf8Strict(encoded) != value) throw DealFormatException("Text is not canonical UTF-8.")
    for (ch in value) {
        val code = ch.code
        val newline = allowNewlines && (ch == '\n' || ch == '\t')
        if (code < 0x20 || code == 0x7f) {
            if (!newline) throw DealFormatException("Text contains a disallowed control character.")
        }
    }
}

internal fun validateMoney(currency: String?, amountMinor: Long?) {
    if ((currency == null) != (amountMinor == null)) {
        throw DealFormatException("Currency and amount must both be present or both be absent.")
    }
    if (currency != null && !currency.matches(Regex("^[A-Z]{3}$"))) {
        throw DealFormatException("Currency is not an ISO 4217 code.")
    }
    if (amountMinor != null && (amountMinor < 0L || amountMinor > CanonicalDeal.MAX_AMOUNT_MINOR)) {
        throw DealFormatException("Amount is not a canonical minor-unit integer.")
    }
}

internal fun validateAncestry(versionId: String, parentVersionId: String?, ancestry: List<String>) {
    if (parentVersionId == null && ancestry.isNotEmpty()) {
        throw DealFormatException("Genesis versions cannot carry ancestry.")
    }
    if (parentVersionId != null && ancestry.isEmpty()) {
        throw DealFormatException("A child version must record its ancestry.")
    }
    if (parentVersionId != null && ancestry.last() != parentVersionId) {
        throw DealFormatException("Ancestry must end with the parent version.")
    }
    if (ancestry.distinct().size != ancestry.size) throw DealFormatException("Ancestry repeats a version.")
    if (versionId in ancestry || versionId == parentVersionId) {
        throw DealFormatException("A version cannot be its own ancestor.")
    }
    ancestry.forEach(DealIds::require)
}

internal fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

internal fun utf8Strict(bytes: ByteArray): String {
    return try {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (e: CharacterCodingException) {
        throw DealFormatException("Text is not valid UTF-8.")
    }
}

internal class ByteArrayOutput {
    private val out = java.io.ByteArrayOutputStream()
    fun raw(bytes: ByteArray) { out.write(bytes) }
    fun u8(value: Int) {
        if (value !in 0..255) throw DealFormatException("Byte is out of range.")
        out.write(value)
    }
    fun u16(value: Int) {
        if (value !in 0..65535) throw DealFormatException("u16 is out of range.")
        out.write(value ushr 8)
        out.write(value)
    }
    fun u32(value: Int) {
        if (value < 0) throw DealFormatException("u32 is out of range.")
        out.write(value ushr 24)
        out.write(value ushr 16)
        out.write(value ushr 8)
        out.write(value)
    }
    fun u64(value: Long) {
        if (value < 0L) throw DealFormatException("u64 is out of range.")
        out.write((value ushr 56).toInt())
        out.write((value ushr 48).toInt())
        out.write((value ushr 40).toInt())
        out.write((value ushr 32).toInt())
        out.write((value ushr 24).toInt())
        out.write((value ushr 16).toInt())
        out.write((value ushr 8).toInt())
        out.write(value.toInt())
    }
    fun lp(value: String) {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        if (bytes.size > 65535) throw DealFormatException("String is too long.")
        u16(bytes.size)
        raw(bytes)
    }
    fun toByteArray(): ByteArray = out.toByteArray()
}

private class Cursor(private val data: ByteArray) {
    private var index = 0
    fun remaining(): Int = data.size - index
    fun u8(): Int {
        if (index >= data.size) throw DealFormatException("Canonical document is truncated.")
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
        if (count < 0 || remaining() < count) throw DealFormatException("Canonical document is truncated.")
        val slice = data.copyOfRange(index, index + count)
        index += count
        return slice
    }
    fun lp(): String {
        val length = u16()
        return utf8Strict(bytes(length))
    }

    fun identityBlock(): DealIdentityBlock {
        val section = u8()
        if (section != DealIdentity.SECTION_VERSION) {
            throw DealFormatException("Unsupported identity section.")
        }
        val count = u16()
        if (count != 2) throw DealFormatException("Identity count is not canonical.")
        val pins = ArrayList<DealPartyPin>(count)
        repeat(count) {
            val partyId = lp()
            val role = PartyRole.fromCode(u8())
            val armorChatId = lp()
            val messaging = key()
            val signing = key()
            val encryption = key()
            val legalName = lp()
            val nameStatus = LegalNameStatus.fromCode(u8())
            val binding = signature()
            val nameConfirmation = signature()
            pins += DealPartyPin(
                partyId = partyId,
                role = role,
                participant = VerifiedDealParticipant.verify(armorChatId, messaging),
                dealSigningPublicKey = signing,
                dealEncryptionPublicKey = encryption,
                legalName = legalName,
                nameStatus = nameStatus,
                bindingSignature = binding,
                nameConfirmation = nameConfirmation,
            )
        }
        return DealIdentityBlock(pins)
    }

    fun annex(): ContractAnnex {
        val purpose = lp()
        val effective = when (val flag = u8()) {
            0 -> null
            1 -> lp()
            else -> throw DealFormatException("Effective date flag is not canonical.")
        }
        return ContractAnnex(purpose, effective)
    }

    private fun key(): ByteArray {
        val length = u16()
        if (length != 32) throw DealFormatException("Identity key length is not canonical.")
        return bytes(32)
    }

    private fun signature(): ByteArray? {
        val length = u16()
        return when (length) {
            0 -> null
            64 -> bytes(64)
            else -> throw DealFormatException("Identity signature length is not canonical.")
        }
    }
}
