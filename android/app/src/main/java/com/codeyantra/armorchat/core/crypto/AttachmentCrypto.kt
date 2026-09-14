package com.codeyantra.armorchat.core.crypto

import com.codeyantra.armorchat.domain.identity.Base64Url
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object AttachmentCrypto {
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val KEY_SIZE_BYTES = 32
    private const val IV_SIZE_BYTES = 12
    private const val TAG_SIZE_BITS = 128

    data class EncryptedAttachment(
        val ciphertext: ByteArray,
        val keyBase64Url: String,
        val ivBase64Url: String,
        val sha256Digest: String,
    )

    suspend fun encrypt(plaintext: ByteArray): EncryptedAttachment = withContext(Dispatchers.Default) {
        val keyBytes = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val ivBytes = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_SIZE_BITS, ivBytes))
        val ciphertext = cipher.doFinal(plaintext)

        val digestBytes = MessageDigest.getInstance("SHA-256").digest(ciphertext)
        EncryptedAttachment(
            ciphertext = ciphertext,
            keyBase64Url = Base64Url.encode(keyBytes),
            ivBase64Url = Base64Url.encode(ivBytes),
            sha256Digest = Base64Url.encode(digestBytes),
        )
    }

    suspend fun decrypt(
        ciphertext: ByteArray,
        keyBase64Url: String,
        ivBase64Url: String,
        expectedSha256DigestHex: String,
    ): ByteArray = withContext(Dispatchers.Default) {
        val actualDigestBytes = MessageDigest.getInstance("SHA-256").digest(ciphertext)
        val actualDigest = Base64Url.encode(actualDigestBytes)
        if (actualDigest != expectedSha256DigestHex) {
            throw IllegalArgumentException("Attachment SHA-256 digest mismatch")
        }

        val keyBytes = Base64Url.decode(keyBase64Url)
        val ivBytes = Base64Url.decode(ivBase64Url)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_SIZE_BITS, ivBytes))
        cipher.doFinal(ciphertext)
    }
}
