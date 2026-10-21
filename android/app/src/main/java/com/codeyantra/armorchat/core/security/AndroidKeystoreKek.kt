package com.codeyantra.armorchat.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.KeyStoreException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-exportable AES-256-GCM key-encryption key in Android Keystore.
 *
 * Alias: [KEK_ALIAS]
 *
 * Open only so JVM unit tests can substitute a software KEK and exercise the
 * real Tink identity signer; production always injects this class.
 */
@Singleton
open class AndroidKeystoreKek @Inject constructor() {

    companion object {
        const val KEK_ALIAS = "armorchat_identity_kek_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH = 12
        /** Envelope: version(1) || iv(12) || ciphertext+tag */
        const val ENVELOPE_VERSION: Byte = 1
    }

    open fun encrypt(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKek()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            require(iv.size == IV_LENGTH) { "Unexpected GCM IV length: ${iv.size}" }
            val ciphertext = cipher.doFinal(plaintext)
            ByteArray(1 + iv.size + ciphertext.size).also { out ->
                out[0] = ENVELOPE_VERSION
                System.arraycopy(iv, 0, out, 1, iv.size)
                System.arraycopy(ciphertext, 0, out, 1 + iv.size, ciphertext.size)
            }
        } catch (e: IdentityStorageException) {
            throw e
        } catch (e: Exception) {
            throw IdentityStorageException.KekUnusable(
                "Failed to encrypt identity key material with Keystore KEK.",
                e,
            )
        }
    }

    open fun decrypt(envelope: ByteArray): ByteArray {
        if (envelope.isEmpty() || envelope[0] != ENVELOPE_VERSION) {
            throw IdentityStorageException.Corrupted("Unsupported or empty identity envelope.")
        }
        if (envelope.size <= 1 + IV_LENGTH) {
            throw IdentityStorageException.Corrupted("Identity envelope is truncated.")
        }
        val key = getExistingKek()
            ?: throw IdentityStorageException.KekUnusable(
                "Identity KEK alias $KEK_ALIAS is missing from Android Keystore.",
            )
        return try {
            val iv = envelope.copyOfRange(1, 1 + IV_LENGTH)
            val ciphertext = envelope.copyOfRange(1 + IV_LENGTH, envelope.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: IdentityStorageException) {
            throw e
        } catch (e: Exception) {
            throw IdentityStorageException.DecryptFailed(
                "AES-GCM decrypt of identity key material failed.",
                e,
            )
        }
    }

    fun hasKek(): Boolean = getExistingKek() != null

    private fun getOrCreateKek(): SecretKey {
        getExistingKek()?.let { return it }
        return try {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                ANDROID_KEYSTORE,
            )
            val spec = KeyGenParameterSpec.Builder(
                KEK_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setKeySize(256)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        } catch (e: Exception) {
            throw IdentityStorageException.KeystoreUnavailable(
                "Unable to create Android Keystore KEK ($KEK_ALIAS).",
                e,
            )
        }
    }

    private fun getExistingKek(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(KEK_ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: KeyStoreException) {
            throw IdentityStorageException.KeystoreUnavailable(
                "Android Keystore could not be opened.",
                e,
            )
        } catch (e: Exception) {
            throw IdentityStorageException.KeystoreUnavailable(
                "Android Keystore access failed.",
                e,
            )
        }
    }
}
