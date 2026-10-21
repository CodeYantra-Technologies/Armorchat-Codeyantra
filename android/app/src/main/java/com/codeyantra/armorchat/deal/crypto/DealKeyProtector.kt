package com.codeyantra.armorchat.deal.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.codeyantra.armorchat.deal.domain.DealKeyException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

/**
 * Seals Armor Deal private keysets. Implementations must not return the wrapping key.
 *
 * [AndroidKeystoreDealKeyProtector] uses a non-exportable Android Keystore key.
 * That key is device-bound: a recovery package cannot contain it, and a new
 * device must be given a recovery secret to unwrap the account keys and then
 * seal them again under the new device's Keystore key.
 */
interface DealKeyProtector {
    fun seal(plaintext: ByteArray, purpose: String): ByteArray
    fun open(sealed: ByteArray, purpose: String): ByteArray
}

/**
 * AES-256-GCM key encryption key in Android Keystore.
 *
 * Alias [ALIAS] is distinct from the messaging identity, relay, and session KEKs.
 * There is no software fallback. If the alias is missing at open time, device
 * access is treated as revoked and local rows are left unchanged by the caller.
 */
class AndroidKeystoreDealKeyProtector(
    private val alias: String = ALIAS,
) : DealKeyProtector {
    init {
        if (alias != ALIAS && alias != PROBE_ALIAS) throw DealKeyException.KeystoreUnavailable()
    }

    override fun seal(plaintext: ByteArray, purpose: String): ByteArray {
        val key = getOrCreate()
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            if (iv.size != IV_LENGTH) throw DealKeyException.KeystoreUnavailable()
            cipher.updateAAD(aad(purpose))
            val ciphertext = cipher.doFinal(plaintext)
            ByteArray(MAGIC.size + 1 + iv.size + ciphertext.size).also { out ->
                MAGIC.copyInto(out)
                out[MAGIC.size] = VERSION
                iv.copyInto(out, MAGIC.size + 1)
                ciphertext.copyInto(out, MAGIC.size + 1 + iv.size)
            }
        } catch (e: DealKeyException) {
            throw e
        } catch (e: Exception) {
            throw DealKeyException.KeystoreUnavailable()
        }
    }

    override fun open(sealed: ByteArray, purpose: String): ByteArray {
        if (sealed.size < MAGIC.size + 1 + IV_LENGTH + TAG_BYTES ||
            !sealed.copyOfRange(0, MAGIC.size).contentEquals(MAGIC) ||
            sealed[MAGIC.size] != VERSION
        ) {
            throw DealKeyException.RevokedDeviceAccess()
        }
        val key = getExisting() ?: throw DealKeyException.RevokedDeviceAccess()
        return try {
            val ivStart = MAGIC.size + 1
            val iv = sealed.copyOfRange(ivStart, ivStart + IV_LENGTH)
            val ciphertext = sealed.copyOfRange(ivStart + IV_LENGTH, sealed.size)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, iv))
            cipher.updateAAD(aad(purpose))
            cipher.doFinal(ciphertext)
        } catch (e: DealKeyException) {
            throw e
        } catch (e: Exception) {
            throw DealKeyException.RevokedDeviceAccess()
        }
    }

    private fun getOrCreate(): javax.crypto.SecretKey {
        getExisting()?.let { return it }
        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .setRandomizedEncryptionRequired(true)
                    .setUserAuthenticationRequired(false)
                    .build(),
            )
            generator.generateKey()
        } catch (e: Exception) {
            throw DealKeyException.KeystoreUnavailable()
        }
    }

    private fun getExisting(): javax.crypto.SecretKey? {
        return try {
            val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE)
            keyStore.load(null)
            val entry = keyStore.getEntry(alias, null) as? KeyStore.SecretKeyEntry
            entry?.secretKey
        } catch (e: Exception) {
            throw DealKeyException.KeystoreUnavailable()
        }
    }

    private fun aad(purpose: String): ByteArray {
        if (purpose.isEmpty() || purpose.length > 64) throw DealKeyException.KeystoreUnavailable()
        return (AAD_PREFIX + purpose).toByteArray(Charsets.UTF_8)
    }

    companion object {
        const val ALIAS = "armorchat_deal_local_kek_v1"

        /**
         * Debug instrumentation alias. The app never constructs this alias.
         * Device tests may create and delete only this entry.
         */
        const val PROBE_ALIAS = "armorchat_deal_keystore_probe_v1"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_LENGTH = 12
        private const val TAG_BITS = 128
        private const val TAG_BYTES = 16
        private const val VERSION: Byte = 1
        private val MAGIC = byteArrayOf(0x41, 0x44, 0x4b, 0x53) // ADKS
        private const val AAD_PREFIX = "ArmorDeal/keystore/v1\u0000"
    }
}
