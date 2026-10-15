package com.codeyantra.armorchat.deal.recovery

import com.codeyantra.armorchat.deal.DealFeature
import com.codeyantra.armorchat.deal.crypto.DealCipher
import com.codeyantra.armorchat.deal.crypto.DealKeyMaterial
import com.codeyantra.armorchat.deal.crypto.DealSignatures
import com.codeyantra.armorchat.deal.crypto.SealedDocument
import com.codeyantra.armorchat.deal.domain.ByteArrayOutput
import com.codeyantra.armorchat.deal.domain.CanonicalDeal
import com.codeyantra.armorchat.deal.domain.CanonicalEncoding
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.DealIds
import com.codeyantra.armorchat.deal.domain.DealRecoveryException
import com.codeyantra.armorchat.deal.domain.DealSignature
import com.codeyantra.armorchat.deal.domain.SignAction
import com.codeyantra.armorchat.deal.domain.utf8Strict
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.generators.SCrypt

/**
 * Versioned recovery package for one party.
 *
 * The package holds that party's signing and encryption keysets and the
 * sealed document. It does not hold the other party's private keys, and it
 * does not hold the Android Keystore wrapping key. A wrong secret or a
 * corrupt frame fails before any local database write. The caller must not
 * treat a lost secret as recoverable.
 *
 * Phase 1 accepts only a high-entropy secret of at least 32 bytes. Password
 * enrollment is not enabled.
 */
class RecoveredDeal(
    val partyId: String,
    val keys: DealKeyMaterial,
    val deal: CanonicalDeal,
    val canonicalBytes: ByteArray,
    val sealed: SealedDocument,
    val signatures: List<DealSignature>,
) {
    override fun toString(): String = "RecoveredDeal(partyId=$partyId, versionId=${deal.versionId})"
}

class DealRecovery(
    private val random: SecureRandom = SecureRandom(),
) {
    fun seal(
        partyId: String,
        secret: ByteArray,
        keys: DealKeyMaterial,
        sealedDocument: SealedDocument,
        deal: CanonicalDeal,
        signatures: List<DealSignature>,
    ): ByteArray {
        requireSecret(secret)
        verifyContents(partyId, keys, sealedDocument, deal, signatures)
        val signing = keys.serializedSigning()
        val encryption = keys.serializedEncryption()
        val sealedBytes = sealedDocument.encode()
        val canonical = deal.canonicalBytes()
        val payload = encodePayload(signing, encryption, sealedBytes, canonical, signatures)
        val salt = ByteArray(SALT_BYTES).also(random::nextBytes)
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val header = header(partyId)
        val derived = derive(secret, salt)
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(payload)
            val out = ByteArrayOutput()
            out.raw(header)
            out.raw(salt)
            out.raw(nonce)
            out.u32(ciphertext.size)
            out.raw(ciphertext)
            out.toByteArray()
        } finally {
            derived.fill(0)
            payload.fill(0)
            signing.fill(0)
            encryption.fill(0)
        }
    }

    fun open(packageBytes: ByteArray, secret: ByteArray): RecoveredDeal {
        if (packageBytes.size > MAX_PACKAGE_BYTES) throw DealRecoveryException.Corrupted()
        val parsed = try {
            parseContainer(packageBytes)
        } catch (e: DealRecoveryException) {
            throw e
        } catch (e: Exception) {
            throw DealRecoveryException.Corrupted()
        }
        requireSecret(secret)
        val derived = derive(secret, parsed.salt)
        val payload = try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derived, "AES"), GCMParameterSpec(TAG_BITS, parsed.nonce))
            cipher.updateAAD(parsed.header)
            cipher.doFinal(parsed.ciphertext)
        } catch (e: AEADBadTagException) {
            throw DealRecoveryException.WrongSecret()
        } catch (e: Exception) {
            throw DealRecoveryException.WrongSecret()
        } finally {
            derived.fill(0)
        }
        return try {
            decodeAndVerify(parsed.partyId, payload)
        } catch (e: DealFormatException) {
            throw DealRecoveryException.Corrupted()
        } finally {
            payload.fill(0)
        }
    }

    private fun verifyContents(
        partyId: String,
        keys: DealKeyMaterial,
        sealedDocument: SealedDocument,
        deal: CanonicalDeal,
        signatures: List<DealSignature>,
    ) {
        val party = deal.parties.firstOrNull { it.partyId == partyId } ?: throw DealRecoveryException.Rejected()
        if (!party.signingPublicKey.contentEquals(keys.signingPublic) ||
            !party.encryptionPublicKey.contentEquals(keys.encryptionPublic)
        ) {
            throw DealRecoveryException.Rejected()
        }
        val plain = DealCipher.decrypt(sealedDocument, partyId, keys.encryptionHandle())
        if (!plain.contentEquals(deal.canonicalBytes())) throw DealRecoveryException.Rejected()
        plain.fill(0)
        verifySignatureSet(deal, signatures)
    }

    private fun decodeAndVerify(partyId: String, payload: ByteArray): RecoveredDeal {
        val cursor = RecoveryCursor(payload)
        if (cursor.u8() != 1) throw DealRecoveryException.Corrupted()
        val signing = cursor.blob(DealKeyMaterial.MAX_KEYSET_BYTES)
        val encryption = cursor.blob(DealKeyMaterial.MAX_KEYSET_BYTES)
        val sealedBytes = cursor.blob(SealedDocument.MAX_CIPHERTEXT_BYTES + 65_536)
        val canonicalBytes = cursor.blob(CanonicalDeal.MAX_TERMS_BYTES + 8_192)
        val signatureCount = cursor.u16()
        if (signatureCount != 2) throw DealRecoveryException.Corrupted()
        val rawSignatures = ArrayList<RawSignature>(signatureCount)
        repeat(signatureCount) {
            rawSignatures += RawSignature(
                partyId = cursor.lp(),
                action = SignAction.fromCode(cursor.u8()),
                signature = cursor.bytes(64),
                publicKey = cursor.bytes(32),
            )
        }
        if (cursor.remaining() != 0) throw DealRecoveryException.Corrupted()
        val keys = try {
            DealKeyMaterial.fromSerialized(signing, encryption)
        } finally {
            signing.fill(0)
            encryption.fill(0)
        }
        val deal = try {
            CanonicalEncoding.parse(canonicalBytes)
        } catch (e: DealFormatException) {
            throw DealRecoveryException.Corrupted()
        }
        if (!deal.canonicalBytes().contentEquals(canonicalBytes)) throw DealRecoveryException.Corrupted()
        val sealed = try {
            SealedDocument.parse(sealedBytes)
        } catch (e: DealFormatException) {
            throw DealRecoveryException.Corrupted()
        }
        val bound = rawSignatures.map { claimed ->
            DealSignature(
                dealId = deal.dealId,
                versionId = deal.versionId,
                partyId = claimed.partyId,
                action = claimed.action,
                signature = claimed.signature,
                signerPublicKey = claimed.publicKey,
            )
        }
        try {
            verifyContents(partyId, keys, sealed, deal, bound)
        } catch (e: DealRecoveryException) {
            throw e
        } catch (e: Exception) {
            throw DealRecoveryException.Rejected()
        }
        return RecoveredDeal(partyId, keys, deal, canonicalBytes.copyOf(), sealed, bound)
    }

    private fun verifySignatureSet(deal: CanonicalDeal, signatures: List<DealSignature>) {
        if (signatures.size != 2) throw DealRecoveryException.Rejected()
        val seen = signatures.map { it.partyId }.toSet()
        if (seen != deal.parties.map { it.partyId }.toSet()) throw DealRecoveryException.Rejected()
        for (signature in signatures) {
            if (signature.action != SignAction.SIGN) throw DealRecoveryException.Rejected()
            val pinned = deal.party(signature.partyId).signingPublicKey
            try {
                DealSignatures.verify(
                    deal = deal,
                    claimed = signature,
                    expectedAction = SignAction.SIGN,
                    expectedDealId = deal.dealId,
                    expectedVersionId = deal.versionId,
                    pinnedPublicKey = pinned,
                )
            } catch (e: Exception) {
                throw DealRecoveryException.Rejected()
            }
        }
    }

    private fun encodePayload(
        signing: ByteArray,
        encryption: ByteArray,
        sealedBytes: ByteArray,
        canonical: ByteArray,
        signatures: List<DealSignature>,
    ): ByteArray {
        val out = ByteArrayOutput()
        out.u8(1)
        out.u32(signing.size)
        out.raw(signing)
        out.u32(encryption.size)
        out.raw(encryption)
        out.u32(sealedBytes.size)
        out.raw(sealedBytes)
        out.u32(canonical.size)
        out.raw(canonical)
        out.u16(signatures.size)
        for (signature in signatures) {
            out.lp(signature.partyId)
            out.u8(signature.action.code)
            out.raw(signature.signature)
            out.raw(signature.signerPublicKey)
        }
        return out.toByteArray()
    }

    private fun header(partyId: String): ByteArray {
        DealIds.require(partyId)
        val out = ByteArrayOutput()
        out.raw(MAGIC)
        out.u8(DealFeature.RECOVERY_FORMAT_VERSION)
        out.u8(SCRYPT_LOG_N)
        out.u8(SCRYPT_R)
        out.u8(SCRYPT_P)
        out.lp(partyId)
        return out.toByteArray()
    }

    private fun parseContainer(bytes: ByteArray): ParsedPackage {
        val cursor = RecoveryCursor(bytes)
        val magic = cursor.bytes(4)
        if (!magic.contentEquals(MAGIC)) throw DealRecoveryException.Corrupted()
        if (cursor.u8() != DealFeature.RECOVERY_FORMAT_VERSION) throw DealRecoveryException.Corrupted()
        if (cursor.u8() != SCRYPT_LOG_N || cursor.u8() != SCRYPT_R || cursor.u8() != SCRYPT_P) {
            throw DealRecoveryException.Corrupted()
        }
        val partyId = cursor.lp()
        DealIds.require(partyId)
        val headerLength = cursor.index
        val header = bytes.copyOfRange(0, headerLength)
        val salt = cursor.bytes(SALT_BYTES)
        val nonce = cursor.bytes(NONCE_BYTES)
        val ciphertextLength = cursor.u32()
        if (ciphertextLength < TAG_BYTES || ciphertextLength > MAX_PACKAGE_BYTES) {
            throw DealRecoveryException.Corrupted()
        }
        val ciphertext = cursor.bytes(ciphertextLength.toInt())
        if (cursor.remaining() != 0) throw DealRecoveryException.Corrupted()
        return ParsedPackage(header, partyId, salt, nonce, ciphertext)
    }

    private fun requireSecret(secret: ByteArray) {
        if (secret.size < MIN_SECRET_BYTES || secret.size > 128) throw DealRecoveryException.WeakSecret()
    }

    private fun derive(secret: ByteArray, salt: ByteArray): ByteArray {
        return SCrypt.generate(secret, salt, SCRYPT_N, SCRYPT_R, SCRYPT_P, 32)
    }

    private class RawSignature(
        val partyId: String,
        val action: SignAction,
        val signature: ByteArray,
        val publicKey: ByteArray,
    )

    private class ParsedPackage(
        val header: ByteArray,
        val partyId: String,
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
    )

    private class RecoveryCursor(private val data: ByteArray) {
        var index: Int = 0
            private set
        fun remaining(): Int = data.size - index
        fun u8(): Int {
            if (index >= data.size) throw DealRecoveryException.Corrupted()
            return data[index++].toInt() and 0xff
        }
        fun u16(): Int = (u8() shl 8) or u8()
        fun u32(): Long {
            var value = 0L
            repeat(4) { value = (value shl 8) or u8().toLong() }
            return value
        }
        fun bytes(count: Int): ByteArray {
            if (count < 0 || remaining() < count) throw DealRecoveryException.Corrupted()
            val slice = data.copyOfRange(index, index + count)
            index += count
            return slice
        }
        fun lp(): String {
            val length = u16()
            return try {
                utf8Strict(bytes(length))
            } catch (e: DealFormatException) {
                throw DealRecoveryException.Corrupted()
            }
        }
        fun blob(max: Int): ByteArray {
            val length = u32()
            if (length <= 0L || length > max) throw DealRecoveryException.Corrupted()
            return bytes(length.toInt())
        }
    }

    companion object {
        const val MIN_SECRET_BYTES = 32
        private const val SCRYPT_LOG_N = 15
        private const val SCRYPT_N = 1 shl SCRYPT_LOG_N
        private const val SCRYPT_R = 8
        private const val SCRYPT_P = 1
        private const val SALT_BYTES = 16
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val MAX_PACKAGE_BYTES = 1_500_000
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private val MAGIC = byteArrayOf(0x41, 0x44, 0x52, 0x50) // ADRP
    }
}
