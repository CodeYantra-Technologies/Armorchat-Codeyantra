package com.codeyantra.armorchat.core.crypto

import com.codeyantra.armorchat.core.blob.BlobLayout
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.AttachmentMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AttachmentCryptoTest {

    @Test
    fun encryptAndDecryptRoundTrip_succeeds() = runTest {
        val originalText = "ArmorChat Secure E2EE Attachment Content"
        val originalBytes = originalText.toByteArray(Charsets.UTF_8)

        val encrypted = AttachmentCrypto.encrypt(originalBytes)
        assertNotNull(encrypted.ciphertext)
        assertNotNull(encrypted.keyBase64Url)
        assertNotNull(encrypted.ivBase64Url)
        assertNotNull(encrypted.sha256Digest)

        val decryptedBytes = AttachmentCrypto.decrypt(
            ciphertext = encrypted.ciphertext,
            keyBase64Url = encrypted.keyBase64Url,
            ivBase64Url = encrypted.ivBase64Url,
            expectedSha256DigestHex = encrypted.sha256Digest,
        )

        assertArrayEquals(originalBytes, decryptedBytes)
        assertEquals(originalText, String(decryptedBytes, Charsets.UTF_8))
    }

    @Test(expected = IllegalArgumentException::class)
    fun tamperedCiphertext_failsDigestVerification() = runTest {
        val originalBytes = "Top Secret Document".toByteArray(Charsets.UTF_8)
        val encrypted = AttachmentCrypto.encrypt(originalBytes)

        // Tamper with the ciphertext
        val tamperedCiphertext = encrypted.ciphertext.copyOf()
        tamperedCiphertext[0] = (tamperedCiphertext[0] + 1).toByte()

        // Should throw IllegalArgumentException due to SHA-256 digest mismatch
        AttachmentCrypto.decrypt(
            ciphertext = tamperedCiphertext,
            keyBase64Url = encrypted.keyBase64Url,
            ivBase64Url = encrypted.ivBase64Url,
            expectedSha256DigestHex = encrypted.sha256Digest,
        )
    }

    @Test
    fun streamingAesGcmMatchesOneShot() {
        val key = ByteArray(32) { (it + 3).toByte() }
        val iv = ByteArray(12) { (it + 1).toByte() }
        val plain = ByteArray(200_000) { (it * 31).toByte() }
        val oneShot = AttachmentCrypto.encryptOneShot(plain, key, iv)
        val streamed = ByteArrayOutputStream()
        val digest = AttachmentCrypto.encryptStreaming(ByteArrayInputStream(plain), streamed, key, iv)
        assertArrayEquals(oneShot, streamed.toByteArray())
        assertEquals(AttachmentCrypto.sha256(oneShot), digest)
        val decrypted = ByteArrayOutputStream()
        AttachmentCrypto.decryptStream(
            ByteArrayInputStream(oneShot),
            decrypted,
            Base64Url.encode(key),
            Base64Url.encode(iv),
        )
        assertArrayEquals(plain, decrypted.toByteArray())
    }

    @Test
    fun chunkLayoutMatchesRelaySliceRules() {
        assertEquals(262144, BlobLayout.CHUNK_SIZE)
        assertEquals(3, BlobLayout.chunkCount(70, 32))
        assertEquals(32, BlobLayout.chunkLength(70, 0, 32, 3))
        assertEquals(32, BlobLayout.chunkLength(70, 1, 32, 3))
        assertEquals(6, BlobLayout.chunkLength(70, 2, 32, 3))
        val total = BlobLayout.MAX_CIPHERTEXT_BYTES
        val count = BlobLayout.chunkCount(total)
        val last = BlobLayout.chunkLength(total, count - 1, BlobLayout.CHUNK_SIZE, count)
        assertEquals(total, (count - 1L) * BlobLayout.CHUNK_SIZE + last)
    }

    @Test
    fun attachmentMetadataJsonSerializationRoundTrip() {
        val metadata = AttachmentMetadata(
            attachmentId = "att_12345",
            blobId = "blob_67890",
            originalName = "secret_report.pdf",
            mimeType = "application/pdf",
            sizeBytes = 2048576L,
            ciphertextDigestSha256 = "47DEQpj8HBSa-_TImW-5JCeuQeRkm5NMpJWZG3hSuFU",
            ivBase64Url = "dGVzdF9pdl8xMjM0NQ",
            wrappedKeyBase64Url = "dGVzdF93cmFwcGVkX2tleV8xMjM0NQ",
            cryptoVersion = 1,
        )

        val json = metadata.toJson()
        val parsed = AttachmentMetadata.parseOrNull(json)

        assertNotNull(parsed)
        assertEquals(metadata.attachmentId, parsed?.attachmentId)
        assertEquals(metadata.blobId, parsed?.blobId)
        assertEquals(metadata.originalName, parsed?.originalName)
        assertEquals(metadata.mimeType, parsed?.mimeType)
        assertEquals(metadata.sizeBytes, parsed?.sizeBytes)
        assertEquals(metadata.ciphertextDigestSha256, parsed?.ciphertextDigestSha256)
        assertEquals(metadata.ivBase64Url, parsed?.ivBase64Url)
        assertEquals(metadata.wrappedKeyBase64Url, parsed?.wrappedKeyBase64Url)
    }

    @Test
    fun invalidJson_returnsNullMetadata() {
        assertNull(AttachmentMetadata.parseOrNull(null))
        assertNull(AttachmentMetadata.parseOrNull(""))
        assertNull(AttachmentMetadata.parseOrNull("{ invalid json }"))
        assertNull(AttachmentMetadata.parseOrNull("""{"type":"TEXT"}"""))
    }
}
