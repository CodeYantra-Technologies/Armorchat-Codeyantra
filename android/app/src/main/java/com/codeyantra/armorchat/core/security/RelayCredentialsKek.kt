package com.codeyantra.armorchat.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keystore KEK specifically for encrypting local relay credentials (mailbox owner tokens and submit capabilities).
 * Cryptographically separated from identity key material KEK [AndroidKeystoreKek.KEK_ALIAS].
 *
 * Alias: [RELAY_KEK_ALIAS]
 */
@Singleton
class RelayCredentialsKek @Inject constructor() {

    companion object {
        const val RELAY_KEK_ALIAS = "armorchat_relay_credentials_kek_v1"
        const val LEGACY_IDENTITY_KEK_ALIAS = "armorchat_identity_kek_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH = 12
        const val ENVELOPE_VERSION: Byte = 1
    }

    fun encrypt(plaintext: ByteArray): ByteArray {
        val key = getOrCreateKek()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        require(iv.size == IV_LENGTH) { "Unexpected GCM IV length: ${iv.size}" }
        val ciphertext = cipher.doFinal(plaintext)
        return ByteArray(1 + iv.size + ciphertext.size).also { out ->
            out[0] = ENVELOPE_VERSION
            System.arraycopy(iv, 0, out, 1, iv.size)
            System.arraycopy(ciphertext, 0, out, 1 + iv.size, ciphertext.size)
        }
    }

    fun decrypt(envelope: ByteArray): ByteArray {
        if (envelope.isEmpty() || envelope[0] != ENVELOPE_VERSION) {
            error("Unsupported or empty relay credential envelope.")
        }
        if (envelope.size <= 1 + IV_LENGTH) {
            error("Relay credential envelope is truncated.")
        }
        val iv = envelope.copyOfRange(1, 1 + IV_LENGTH)
        val ciphertext = envelope.copyOfRange(1 + IV_LENGTH, envelope.size)

        // Try primary RELAY_KEK_ALIAS first
        val relayKey = getExistingKey(RELAY_KEK_ALIAS)
        if (relayKey != null) {
            val result = runCatching {
                val cipher = Cipher.getInstance(TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, relayKey, GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(ciphertext)
            }.getOrNull()
            if (result != null) return result
        }

        // Safe fallback for existing debug-build session data encrypted under identity KEK
        val legacyKey = getExistingKey(LEGACY_IDENTITY_KEK_ALIAS)
        if (legacyKey != null) {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, legacyKey, GCMParameterSpec(GCM_TAG_BITS, iv))
            return cipher.doFinal(ciphertext)
        }

        error("Relay KEK missing from Android Keystore.")
    }

    private fun getOrCreateKek(): SecretKey {
        getExistingKey(RELAY_KEK_ALIAS)?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEYSTORE,
        )
        val spec = KeyGenParameterSpec.Builder(
            RELAY_KEK_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setKeySize(256)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    private fun getExistingKey(alias: String): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (_: Exception) {
            null
        }
    }
}
