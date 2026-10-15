package com.codeyantra.armorchat.deal.domain

import com.codeyantra.armorchat.deal.DealFeature
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.text.Normalizer

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
 * Armor Deal Canonical Bytes version 1.
 *
 * This is a fixed big-endian layout, not JSON. The same logical agreement
 * always produces the same bytes. Money is an integer count of minor units.
 *
 * ```
 * magic "ADCB" | version u8=1
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
 * The parser rejects unsorted parties, duplicate fields, non-NFC text,
 * truncated input, and trailing bytes.
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
    }

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
        ): CanonicalDeal = CanonicalDeal(
            dealId, versionId, parentVersionId, createdAtEpochMs, currency, amountMinor, parties, ancestry, terms,
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
 * A new version is always a new object. When [signatureCount] is non-zero, or
 * the deal can no longer be revised, this throws and the caller keeps [current].
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
        if (!DealLifecycle.allowsRevision(dealStatus)) {
            throw DealStateException("This deal can no longer be revised.")
        }
        if (signatureCount > 0) throw ImmutableVersionException()
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
        )
        if (!before.contentEquals(currentBytes)) throw ImmutableVersionException()
        if (revised.versionId == current.versionId) throw DealFormatException("A revision needs a new version id.")
        if (revised.canonicalBytes().contentEquals(before)) {
            throw DealFormatException("Revision did not change the canonical document.")
        }
        return revised
    }
}

object CanonicalEncoding {
    private val magic = byteArrayOf(0x41, 0x44, 0x43, 0x42) // ADCB

    fun encode(deal: CanonicalDeal): ByteArray {
        val out = ByteArrayOutput()
        out.raw(magic)
        out.u8(DealFeature.CANONICAL_VERSION)
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
        if (version != DealFeature.CANONICAL_VERSION) {
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
        if (cursor.remaining() != 0) throw DealFormatException("Canonical document has trailing bytes.")
        return CanonicalDeal.fromParsed(
            dealId, versionId, parent, createdAt, currency, amount, parties, ancestry, terms,
        )
    }
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
}
