package com.codeyantra.armorchat.deal.crypto

import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.domain.ByteArrayOutput
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.compareOpaque
import com.codeyantra.armorchat.deal.domain.sha256
import com.codeyantra.armorchat.deal.domain.utf8Strict
import com.google.crypto.tink.HybridDecrypt
import com.google.crypto.tink.HybridEncrypt
import com.google.crypto.tink.KeysetHandle
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class DekEnvelope(
    val partyId: String,
    val wrappedDek: ByteArray,
) {
    override fun toString(): String = "DekEnvelope(partyId=$partyId, bytes=${wrappedDek.size})"
}

/**
 * One AES-256-GCM encryption of the canonical document, plus one Tink HPKE
 * envelope of that data key per party. The document nonce is not reused.
 */
class SealedDocument(
    val dealId: String,
    val versionId: String,
    val nonce: ByteArray,
    val ciphertext: ByteArray,
    val envelopes: List<DekEnvelope>,
) {
    init {
        DealIds.require(dealId)
        DealIds.require(versionId)
        if (nonce.size != NONCE_BYTES) throw DealFormatException("Document nonce must be 12 bytes.")
        if (ciphertext.size < TAG_BYTES || ciphertext.size > MAX_CIPHERTEXT_BYTES) {
            throw DealFormatException("Document ciphertext size is invalid.")
        }
        if (envelopes.size != 2) throw DealFormatException("Two party envelopes are required.")
        for (index in 1 until envelopes.size) {
            if (compareOpaque(envelopes[index - 1].partyId, envelopes[index].partyId) >= 0) {
                throw DealFormatException("Key envelopes are not strictly sorted.")
            }
        }
    }

    fun encode(): ByteArray = DealCipher.encode(this)

    fun ciphertextHash(): ByteArray = sha256(ciphertext)

    override fun toString(): String = "SealedDocument(dealId=$dealId, versionId=$versionId, bytes=${ciphertext.size})"

    companion object {
        const val NONCE_BYTES = 12
        const val TAG_BYTES = 16
        const val MAX_CIPHERTEXT_BYTES = 1024 * 1024

        fun parse(bytes: ByteArray): SealedDocument = DealCipher.parse(bytes)
    }
}

class PartyWrapTarget(
    val partyId: String,
    val encryptionPublic: KeysetHandle,
)

object DealCipher {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val TAG_BITS = 128
    private val magic = byteArrayOf(0x41, 0x44, 0x45, 0x43) // ADEC

    fun encrypt(
        dealId: String,
        versionId: String,
        canonical: ByteArray,
        recipients: List<PartyWrapTarget>,
        random: SecureRandom = SecureRandom(),
    ): SealedDocument {
        DealTink.register()
        if (canonical.isEmpty() || canonical.size > 64_000) throw DealFormatException("Document plaintext size is invalid.")
        if (recipients.size != 2) throw DealFormatException("Two recipients are required.")
        val dek = ByteArray(32)
        val nonce = ByteArray(SealedDocument.NONCE_BYTES)
        random.nextBytes(dek)
        random.nextBytes(nonce)
        try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(dek, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(documentAad(dealId, versionId))
            val ciphertext = cipher.doFinal(canonical)
            val envelopes = recipients.map { recipient ->
                val hybrid = recipient.encryptionPublic.getPrimitive(HybridEncrypt::class.java)
                val wrapped = hybrid.encrypt(dek, dekAad(dealId, versionId, recipient.partyId))
                DekEnvelope(recipient.partyId, wrapped)
            }.sortedWith { left, right -> compareOpaque(left.partyId, right.partyId) }
            return SealedDocument(dealId, versionId, nonce.copyOf(), ciphertext, envelopes)
        } catch (e: DealFormatException) {
            throw e
        } catch (e: Exception) {
            throw DealCryptoException.AuthenticationFailed()
        } finally {
            dek.fill(0)
        }
    }

    /**
     * Returns the full canonical plaintext or throws. A tag failure does not
     * return a prefix of the document.
     */
    fun decrypt(
        sealed: SealedDocument,
        partyId: String,
        encryptionPrivate: KeysetHandle,
    ): ByteArray {
        DealTink.register()
        val envelope = sealed.envelopes.firstOrNull { it.partyId == partyId }
            ?: throw DealCryptoException.UnknownParty()
        val dek = try {
            encryptionPrivate.getPrimitive(HybridDecrypt::class.java)
                .decrypt(envelope.wrappedDek, dekAad(sealed.dealId, sealed.versionId, partyId))
        } catch (e: Exception) {
            throw DealCryptoException.AuthenticationFailed()
        }
        if (dek.size != 32) {
            dek.fill(0)
            throw DealCryptoException.AuthenticationFailed()
        }
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(dek, "AES"),
                GCMParameterSpec(TAG_BITS, sealed.nonce),
            )
            cipher.updateAAD(documentAad(sealed.dealId, sealed.versionId))
            cipher.doFinal(sealed.ciphertext)
        } catch (e: AEADBadTagException) {
            throw DealCryptoException.AuthenticationFailed()
        } catch (e: DealCryptoException) {
            throw e
        } catch (e: Exception) {
            throw DealCryptoException.AuthenticationFailed()
        } finally {
            dek.fill(0)
        }
    }

    fun encode(document: SealedDocument): ByteArray {
        val out = ByteArrayOutput()
        out.raw(magic)
        out.u8(DealFeature.ENCRYPTION_FORMAT_VERSION)
        out.lp(document.dealId)
        out.lp(document.versionId)
        out.raw(document.nonce)
        out.u32(document.ciphertext.size)
        out.raw(document.ciphertext)
        out.u16(document.envelopes.size)
        for (envelope in document.envelopes) {
            out.lp(envelope.partyId)
            out.u32(envelope.wrappedDek.size)
            out.raw(envelope.wrappedDek)
        }
        return out.toByteArray()
    }

    fun parse(bytes: ByteArray): SealedDocument {
        if (bytes.size > SealedDocument.MAX_CIPHERTEXT_BYTES + 65_536) {
            throw DealFormatException("Sealed document is too large.")
        }
        val cursor = CryptoCursor(bytes)
        if (!cursor.bytes(4).contentEquals(magic)) throw DealFormatException("Not a sealed Armor Deal document.")
        if (cursor.u8() != DealFeature.ENCRYPTION_FORMAT_VERSION) {
            throw DealFormatException("Unsupported document encryption version.")
        }
        val dealId = cursor.lp()
        val versionId = cursor.lp()
        val nonce = cursor.bytes(SealedDocument.NONCE_BYTES)
        val ciphertextLength = cursor.u32()
        if (ciphertextLength > SealedDocument.MAX_CIPHERTEXT_BYTES) {
            throw DealFormatException("Document ciphertext is too large.")
        }
        val ciphertext = cursor.bytes(ciphertextLength.toInt())
        val count = cursor.u16()
        val envelopes = ArrayList<DekEnvelope>(count)
        repeat(count) {
            val partyId = cursor.lp()
            val wrappedLength = cursor.u32()
            if (wrappedLength <= 0L || wrappedLength > 16_384L) {
                throw DealFormatException("Key envelope size is invalid.")
            }
            envelopes += DekEnvelope(partyId, cursor.bytes(wrappedLength.toInt()))
        }
        if (cursor.remaining() != 0) throw DealFormatException("Sealed document has trailing bytes.")
        return SealedDocument(dealId, versionId, nonce, ciphertext, envelopes)
    }

    internal fun documentAad(dealId: String, versionId: String): ByteArray {
        val out = ByteArrayOutput()
        out.raw("ArmorDeal/doc/v1".toByteArray(Charsets.UTF_8))
        out.u8(0)
        out.lp(dealId)
        out.lp(versionId)
        out.u16(DealFeature.ENCRYPTION_FORMAT_VERSION)
        out.lp("deal-document")
        return out.toByteArray()
    }

    internal fun dekAad(dealId: String, versionId: String, partyId: String): ByteArray {
        val out = ByteArrayOutput()
        out.raw("ArmorDeal/dek/v1".toByteArray(Charsets.UTF_8))
        out.u8(0)
        out.lp(dealId)
        out.lp(versionId)
        out.lp(partyId)
        out.lp("party-dek")
        return out.toByteArray()
    }
}

internal class CryptoCursor(private val data: ByteArray) {
    private var index = 0
    fun remaining(): Int = data.size - index
    fun u8(): Int {
        if (index >= data.size) throw DealFormatException("Sealed document is truncated.")
        return data[index++].toInt() and 0xff
    }
    fun u16(): Int = (u8() shl 8) or u8()
    fun u32(): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or u8().toLong() }
        return value
    }
    fun bytes(count: Int): ByteArray {
        if (count < 0 || remaining() < count) throw DealFormatException("Sealed document is truncated.")
        val slice = data.copyOfRange(index, index + count)
        index += count
        return slice
    }
    fun lp(): String {
        val length = u16()
        return utf8Strict(bytes(length))
    }
}
