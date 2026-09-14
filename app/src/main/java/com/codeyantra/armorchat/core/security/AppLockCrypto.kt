package com.codeyantra.armorchat.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

sealed interface VerificationResult {
    data object Invalid : VerificationResult
    data class Valid(val needsUpgrade: Boolean) : VerificationResult
}

@Singleton
class AppLockCrypto @Inject constructor() {

    companion object {
        private const val KEYSTORE_ALIAS = "armorchat_app_lock_kek_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val PBKDF2_ALGORITHM = "PBKDF2WithHmacSHA256"

        const val ITERATION_COUNT_V1 = 100_000
        const val ITERATION_COUNT_V2 = 300_000

        private const val KEY_LENGTH_BITS = 256
        private const val SALT_LENGTH_BYTES = 16
        private const val GCM_TAG_BITS = 128
        private const val IV_LENGTH_BYTES = 12

        private const val VERIFIER_TOKEN_V1 = "armorchat_lock_verifier_v1"
        private const val VERIFIER_TOKEN_V2 = "armorchat_lock_verifier_v2"
    }

    private val random = SecureRandom()

    fun generateSalt(): String {
        val salt = ByteArray(SALT_LENGTH_BYTES)
        random.nextBytes(salt)
        return Base64.encodeToString(salt, Base64.NO_WRAP)
    }

    suspend fun createVerifier(pinOrPassword: CharArray, saltBase64: String): String =
        withContext(Dispatchers.Default) {
            createVerifierV2(pinOrPassword, saltBase64)
        }

    suspend fun createVerifierV2(pinOrPassword: CharArray, saltBase64: String): String =
        withContext(Dispatchers.Default) {
            val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
            val derivedKeyBytes = derivePbeKey(pinOrPassword, salt, ITERATION_COUNT_V2)
            try {
                val keystoreKey = getOrCreateKeystoreKey()

                // Layer 1: Encrypt v2 verifier token with derived PBE key
                val pbeCipher = Cipher.getInstance(TRANSFORMATION)
                pbeCipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(derivedKeyBytes, "AES"))
                val pbeIv = pbeCipher.iv
                val pbeCiphertext = pbeCipher.doFinal(VERIFIER_TOKEN_V2.toByteArray(Charsets.UTF_8))

                // Inner payload: pbeIv(12) || pbeCiphertext
                val innerPayload = ByteArray(pbeIv.size + pbeCiphertext.size).also { out ->
                    System.arraycopy(pbeIv, 0, out, 0, pbeIv.size)
                    System.arraycopy(pbeCiphertext, 0, out, pbeIv.size, pbeCiphertext.size)
                }

                // Layer 2: Encrypt inner payload with Android Keystore KEK
                val ksCipher = Cipher.getInstance(TRANSFORMATION)
                ksCipher.init(Cipher.ENCRYPT_MODE, keystoreKey)
                val ksIv = ksCipher.iv
                val ksCiphertext = ksCipher.doFinal(innerPayload)

                // Envelope: ksIv(12) || ksCiphertext
                val envelope = ByteArray(ksIv.size + ksCiphertext.size).also { out ->
                    System.arraycopy(ksIv, 0, out, 0, ksIv.size)
                    System.arraycopy(ksCiphertext, 0, out, ksIv.size, ksCiphertext.size)
                }

                Base64.encodeToString(envelope, Base64.NO_WRAP)
            } finally {
                derivedKeyBytes.fill(0)
            }
        }

    suspend fun verifyPinOrPassword(
        pinOrPassword: CharArray,
        saltBase64: String,
        verifierBase64: String,
    ): VerificationResult = withContext(Dispatchers.Default) {
        // Try V2 verification first (300k iterations)
        if (attemptVerify(pinOrPassword, saltBase64, verifierBase64, ITERATION_COUNT_V2, VERIFIER_TOKEN_V2)) {
            return@withContext VerificationResult.Valid(needsUpgrade = false)
        }
        // Fallback check V1 verification (100k iterations) for legacy verifier envelopes
        if (attemptVerify(pinOrPassword, saltBase64, verifierBase64, ITERATION_COUNT_V1, VERIFIER_TOKEN_V1)) {
            return@withContext VerificationResult.Valid(needsUpgrade = true)
        }
        return@withContext VerificationResult.Invalid
    }

    private fun attemptVerify(
        pinOrPassword: CharArray,
        saltBase64: String,
        verifierBase64: String,
        iterations: Int,
        expectedToken: String,
    ): Boolean {
        return try {
            val salt = Base64.decode(saltBase64, Base64.NO_WRAP)
            val envelope = Base64.decode(verifierBase64, Base64.NO_WRAP)
            if (envelope.size <= IV_LENGTH_BYTES) return false

            val keystoreKey = getExistingKeystoreKey() ?: return false

            // Layer 2 Decrypt with Android Keystore KEK
            val ksIv = envelope.copyOfRange(0, IV_LENGTH_BYTES)
            val ksCiphertext = envelope.copyOfRange(IV_LENGTH_BYTES, envelope.size)
            val ksCipher = Cipher.getInstance(TRANSFORMATION)
            ksCipher.init(Cipher.DECRYPT_MODE, keystoreKey, GCMParameterSpec(GCM_TAG_BITS, ksIv))
            val innerPayload = ksCipher.doFinal(ksCiphertext)

            if (innerPayload.size <= IV_LENGTH_BYTES) return false

            // Layer 1 Decrypt with derived PBE Key
            val pbeIv = innerPayload.copyOfRange(0, IV_LENGTH_BYTES)
            val pbeCiphertext = innerPayload.copyOfRange(IV_LENGTH_BYTES, innerPayload.size)

            val derivedKeyBytes = derivePbeKey(pinOrPassword, salt, iterations)
            try {
                val pbeCipher = Cipher.getInstance(TRANSFORMATION)
                pbeCipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(derivedKeyBytes, "AES"), GCMParameterSpec(GCM_TAG_BITS, pbeIv))
                val decryptedTokenBytes = pbeCipher.doFinal(pbeCiphertext)
                val decryptedToken = String(decryptedTokenBytes, Charsets.UTF_8)
                decryptedToken == expectedToken
            } finally {
                derivedKeyBytes.fill(0)
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun derivePbeKey(password: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        val spec = PBEKeySpec(password, salt, iterations, KEY_LENGTH_BITS)
        val factory = SecretKeyFactory.getInstance(PBKDF2_ALGORITHM)
        val key = factory.generateSecret(spec).encoded
        spec.clearPassword()
        return key
    }

    private fun getOrCreateKeystoreKey(): SecretKey {
        getExistingKeystoreKey()?.let { return it }
        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        val spec = KeyGenParameterSpec.Builder(
            KEYSTORE_ALIAS,
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

    private fun getExistingKeystoreKey(): SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
            val entry = keyStore.getEntry(KEYSTORE_ALIAS, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Exception) {
            null
        }
    }
}
