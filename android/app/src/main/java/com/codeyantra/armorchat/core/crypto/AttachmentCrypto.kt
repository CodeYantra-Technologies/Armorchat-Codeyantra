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

    data class EncryptedAttachmentFile(
        val ciphertextFile: java.io.File,
        val plaintextBytes: Long,
        val keyBase64Url: String,
        val ivBase64Url: String,
        val sha256Digest: String,
    )

    suspend fun encrypt(plaintext: ByteArray): EncryptedAttachment = withContext(Dispatchers.Default) {
        val keyBytes = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val ivBytes = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val ciphertext = encryptOneShot(plaintext, keyBytes, ivBytes)
        EncryptedAttachment(
            ciphertext = ciphertext,
            keyBase64Url = Base64Url.encode(keyBytes),
            ivBase64Url = Base64Url.encode(ivBytes),
            sha256Digest = sha256(ciphertext),
        )
    }

    /**
     * Same AES-256-GCM construction as [encrypt], written to [ciphertextFile]
     * in bounded buffers so a large plaintext is not held as one array.
     */
    fun encryptToFile(
        input: java.io.InputStream,
        ciphertextFile: java.io.File,
        maxPlaintextBytes: Long,
        shouldContinue: () -> Boolean = { true },
        onPlaintextBytes: (Long) -> Unit = {},
    ): EncryptedAttachmentFile {
        val keyBytes = ByteArray(KEY_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        val ivBytes = ByteArray(IV_SIZE_BYTES).also { SecureRandom().nextBytes(it) }
        ciphertextFile.parentFile?.mkdirs()
        var plainBytes = 0L
        val digest = ciphertextFile.outputStream().use { output ->
            encryptStreaming(input, output, keyBytes, ivBytes, maxPlaintextBytes, shouldContinue) { read ->
                plainBytes = read
                onPlaintextBytes(read)
            }
        }
        return EncryptedAttachmentFile(
            ciphertextFile = ciphertextFile,
            plaintextBytes = plainBytes,
            keyBase64Url = Base64Url.encode(keyBytes),
            ivBase64Url = Base64Url.encode(ivBytes),
            sha256Digest = digest,
        )
    }

    fun sha256(bytes: ByteArray): String = Base64Url.encode(MessageDigest.getInstance("SHA-256").digest(bytes))

    fun sha256(input: java.io.InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            digest.update(buf, 0, n)
        }
        return Base64Url.encode(digest.digest())
    }

    fun decryptStream(
        ciphertext: java.io.InputStream,
        output: java.io.OutputStream,
        keyBase64Url: String,
        ivBase64Url: String,
    ) {
        val keyBytes = Base64Url.decode(keyBase64Url)
        val ivBytes = Base64Url.decode(ivBase64Url)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_SIZE_BITS, ivBytes))
        val buf = ByteArray(64 * 1024)
        while (true) {
            val n = ciphertext.read(buf)
            if (n < 0) break
            val piece = cipher.update(buf, 0, n)
            if (piece != null && piece.isNotEmpty()) output.write(piece)
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) output.write(tail)
    }

    internal fun encryptOneShot(plaintext: ByteArray, keyBytes: ByteArray, ivBytes: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_SIZE_BITS, ivBytes))
        return cipher.doFinal(plaintext)
    }

    internal fun encryptStreaming(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        keyBytes: ByteArray,
        ivBytes: ByteArray,
        maxPlaintextBytes: Long = Long.MAX_VALUE,
        shouldContinue: () -> Boolean = { true },
        onPlaintextBytes: (Long) -> Unit = {},
    ): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(keyBytes, "AES"), GCMParameterSpec(TAG_SIZE_BITS, ivBytes))
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (!shouldContinue()) throw kotlinx.coroutines.CancellationException("blob encrypt cancelled")
            val n = input.read(buf)
            if (n < 0) break
            total += n
            if (total > maxPlaintextBytes) throw IllegalArgumentException("attachment too large")
            onPlaintextBytes(total)
            val piece = cipher.update(buf, 0, n)
            if (piece != null && piece.isNotEmpty()) {
                output.write(piece)
                digest.update(piece)
            }
        }
        val tail = cipher.doFinal()
        if (tail.isNotEmpty()) {
            output.write(tail)
            digest.update(tail)
        }
        return Base64Url.encode(digest.digest())
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
