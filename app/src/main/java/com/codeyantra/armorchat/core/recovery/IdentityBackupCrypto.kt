package com.codeyantra.armorchat.core.recovery

import com.codeyantra.armorchat.domain.identity.ArmorChatId
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import org.bouncycastle.crypto.generators.SCrypt

data class IdentityBackupPayload(
    val serializedIdentityKeyset: ByteArray,
    val identityCreatedAtEpochMs: Long,
    val cryptoVersion: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdentityBackupPayload) return false
        return serializedIdentityKeyset.contentEquals(other.serializedIdentityKeyset) &&
            identityCreatedAtEpochMs == other.identityCreatedAtEpochMs &&
            cryptoVersion == other.cryptoVersion
    }

    override fun hashCode(): Int {
        var result = serializedIdentityKeyset.contentHashCode()
        result = 31 * result + identityCreatedAtEpochMs.hashCode()
        result = 31 * result + cryptoVersion
        return result
    }
}

/**
 * ArmorChat identity backup format v1.
 *
 * Container:
 * magic(8) || formatVersion(1) || kdfId(1) || aeadId(1) ||
 * scryptLogN(1) || scryptR(4) || scryptP(4) || keyLen(1) ||
 * saltLen(1) || nonceLen(1) || ciphertextLen(4) ||
 * salt || nonce || ciphertextAndTag
 */
class IdentityBackupCrypto(
    private val random: SecureRandom = SecureRandom(),
) {
    @Inject constructor() : this(SecureRandom())

    fun encrypt(payload: IdentityBackupPayload, password: CharArray): ByteArray {
        requirePassword(password)
        val salt = ByteArray(SALT_LENGTH).also(random::nextBytes)
        val nonce = ByteArray(GCM_NONCE_LENGTH).also(random::nextBytes)
        val key = deriveKey(password, salt)
        val plaintext = encodePayload(payload)
        return try {
            val header = encodeHeader(salt, nonce, ciphertextLength = plaintext.size + GCM_TAG_BYTES)
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.ENCRYPT_MODE,
                SecretKeySpec(key, AES_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, nonce),
            )
            cipher.updateAAD(header)
            val ciphertext = cipher.doFinal(plaintext)
            header + salt + nonce + ciphertext
        } catch (e: Exception) {
            throw IdentityBackupException.Corrupted("Unable to create encrypted identity backup.", e)
        } finally {
            key.fill(0)
            plaintext.fill(0)
        }
    }

    fun decrypt(backupBytes: ByteArray, password: CharArray): IdentityBackupPayload {
        requirePassword(password)
        val parsed = parseContainer(backupBytes)
        val key = deriveKey(password, parsed.salt)
        return try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORMATION)
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(key, AES_ALGORITHM),
                GCMParameterSpec(GCM_TAG_BITS, parsed.nonce),
            )
            cipher.updateAAD(parsed.header)
            val plaintext = cipher.doFinal(parsed.ciphertext)
            decodePayload(plaintext).also {
                plaintext.fill(0)
            }
        } catch (e: IdentityBackupException) {
            throw e
        } catch (e: Exception) {
            throw IdentityBackupException.InvalidPassword(cause = e)
        } finally {
            key.fill(0)
        }
    }

    private fun deriveKey(password: CharArray, salt: ByteArray): ByteArray {
        val passwordBytes = String(password).toByteArray(StandardCharsets.UTF_8)
        return try {
            SCrypt.generate(
                passwordBytes,
                salt,
                SCRYPT_N,
                SCRYPT_R,
                SCRYPT_P,
                DERIVED_KEY_LENGTH,
            )
        } finally {
            passwordBytes.fill(0)
        }
    }

    private fun encodePayload(payload: IdentityBackupPayload): ByteArray {
        if (payload.serializedIdentityKeyset.isEmpty()) {
            throw IdentityBackupException.Corrupted("Identity keyset payload is empty.")
        }
        return ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeByte(PAYLOAD_VERSION)
                out.writeInt(payload.cryptoVersion)
                out.writeLong(payload.identityCreatedAtEpochMs)
                out.writeInt(payload.serializedIdentityKeyset.size)
                out.write(payload.serializedIdentityKeyset)
            }
            bytes.toByteArray()
        }
    }

    private fun decodePayload(plaintext: ByteArray): IdentityBackupPayload {
        try {
            DataInputStream(ByteArrayInputStream(plaintext)).use { input ->
                val version = input.readUnsignedByte()
                if (version != PAYLOAD_VERSION) {
                    throw IdentityBackupException.UnsupportedFormat("Unsupported backup payload version: $version.")
                }
                val cryptoVersion = input.readInt()
                val createdAt = input.readLong()
                val keysetLength = input.readInt()
                if (cryptoVersion <= 0 || createdAt <= 0L) {
                    throw IdentityBackupException.Corrupted("Backup payload metadata is invalid.")
                }
                if (keysetLength <= 0 || keysetLength > MAX_KEYSET_BYTES) {
                    throw IdentityBackupException.Corrupted("Backup identity keyset length is invalid.")
                }
                val keyset = ByteArray(keysetLength)
                input.readFully(keyset)
                if (input.available() != 0) {
                    throw IdentityBackupException.Corrupted("Backup payload contains trailing bytes.")
                }
                return IdentityBackupPayload(
                    serializedIdentityKeyset = keyset,
                    identityCreatedAtEpochMs = createdAt,
                    cryptoVersion = cryptoVersion,
                )
            }
        } catch (e: IdentityBackupException) {
            throw e
        } catch (e: Exception) {
            throw IdentityBackupException.Corrupted("Unable to parse backup payload.", e)
        }
    }

    private fun parseContainer(bytes: ByteArray): ParsedContainer {
        if (bytes.size > MAX_BACKUP_BYTES) {
            throw IdentityBackupException.Corrupted("Backup file is unexpectedly large.")
        }
        val minimumSize = HEADER_LENGTH + SALT_LENGTH + GCM_NONCE_LENGTH + GCM_TAG_BYTES
        if (bytes.size < minimumSize) {
            throw IdentityBackupException.Corrupted("Backup file is truncated.")
        }
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val magic = ByteArray(MAGIC.size)
        input.readFully(magic)
        if (!magic.contentEquals(MAGIC)) {
            throw IdentityBackupException.UnsupportedFormat("File is not an ArmorChat identity backup.")
        }
        val formatVersion = input.readUnsignedByte()
        val kdfId = input.readUnsignedByte()
        val aeadId = input.readUnsignedByte()
        val logN = input.readUnsignedByte()
        val r = input.readInt()
        val p = input.readInt()
        val keyLength = input.readUnsignedByte()
        val saltLength = input.readUnsignedByte()
        val nonceLength = input.readUnsignedByte()
        val ciphertextLength = input.readInt()
        if (
            formatVersion != FORMAT_VERSION ||
            kdfId != KDF_SCRYPT ||
            aeadId != AEAD_AES_256_GCM ||
            logN != SCRYPT_LOG_N ||
            r != SCRYPT_R ||
            p != SCRYPT_P ||
            keyLength != DERIVED_KEY_LENGTH ||
            saltLength != SALT_LENGTH ||
            nonceLength != GCM_NONCE_LENGTH
        ) {
            throw IdentityBackupException.UnsupportedFormat("Unsupported ArmorChat backup parameters.")
        }
        if (ciphertextLength < GCM_TAG_BYTES || ciphertextLength > MAX_CIPHERTEXT_BYTES) {
            throw IdentityBackupException.Corrupted("Backup ciphertext length is invalid.")
        }
        val expectedLength = HEADER_LENGTH + saltLength + nonceLength + ciphertextLength
        if (bytes.size != expectedLength) {
            throw IdentityBackupException.Corrupted("Backup file length does not match its header.")
        }
        val salt = ByteArray(saltLength)
        input.readFully(salt)
        val nonce = ByteArray(nonceLength)
        input.readFully(nonce)
        val ciphertext = ByteArray(ciphertextLength)
        input.readFully(ciphertext)
        return ParsedContainer(
            salt = salt,
            nonce = nonce,
            ciphertext = ciphertext,
            header = bytes.copyOfRange(0, HEADER_LENGTH),
        )
    }

    private fun encodeHeader(
        salt: ByteArray,
        nonce: ByteArray,
        ciphertextLength: Int?,
    ): ByteArray {
        require(salt.size == SALT_LENGTH)
        require(nonce.size == GCM_NONCE_LENGTH)
        return ByteArrayOutputStream(HEADER_LENGTH).use { bytes ->
            DataOutputStream(bytes).use { out ->
                out.write(MAGIC)
                out.writeByte(FORMAT_VERSION)
                out.writeByte(KDF_SCRYPT)
                out.writeByte(AEAD_AES_256_GCM)
                out.writeByte(SCRYPT_LOG_N)
                out.writeInt(SCRYPT_R)
                out.writeInt(SCRYPT_P)
                out.writeByte(DERIVED_KEY_LENGTH)
                out.writeByte(SALT_LENGTH)
                out.writeByte(GCM_NONCE_LENGTH)
                out.writeInt(ciphertextLength ?: 0)
            }
            bytes.toByteArray()
        }
    }

    private fun requirePassword(password: CharArray) {
        if (password.size < MIN_PASSWORD_CHARS) {
            throw IdentityBackupException.Corrupted(
                "Backup password must be at least $MIN_PASSWORD_CHARS characters.",
            )
        }
    }

    private data class ParsedContainer(
        val salt: ByteArray,
        val nonce: ByteArray,
        val ciphertext: ByteArray,
        val header: ByteArray,
    )

    companion object {
        val MAGIC: ByteArray = "ACIDBKP1".toByteArray(StandardCharsets.US_ASCII)
        const val FORMAT_VERSION = 1
        const val PAYLOAD_VERSION = 1
        const val KDF_SCRYPT = 1
        const val AEAD_AES_256_GCM = 1
        const val SCRYPT_LOG_N = 15
        const val SCRYPT_N = 1 shl SCRYPT_LOG_N
        const val SCRYPT_R = 8
        const val SCRYPT_P = 1
        const val DERIVED_KEY_LENGTH = 32
        const val SALT_LENGTH = 16
        const val GCM_NONCE_LENGTH = 12
        const val GCM_TAG_BITS = 128
        const val GCM_TAG_BYTES = GCM_TAG_BITS / 8
        const val MIN_PASSWORD_CHARS = 12
        const val MAX_KEYSET_BYTES = 16 * 1024
        const val MAX_CIPHERTEXT_BYTES = MAX_KEYSET_BYTES + 64
        const val MAX_BACKUP_BYTES = 32 * 1024
        private const val HEADER_LENGTH = 27
        private const val AES_ALGORITHM = "AES"
        private const val AES_GCM_TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
