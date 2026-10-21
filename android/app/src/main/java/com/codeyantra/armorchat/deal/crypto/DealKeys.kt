package com.codeyantra.armorchat.deal.crypto

import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.requireRawKey
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.hybrid.HpkeParameters
import com.google.crypto.tink.hybrid.HpkePublicKey
import com.google.crypto.tink.hybrid.HybridConfig
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import java.security.GeneralSecurityException

/** Registers the Tink primitives Armor Deal uses. Does not touch messaging keys. */
object DealTink {
    @Synchronized
    fun register() {
        registerQuietly { SignatureConfig.register() }
        registerQuietly { HybridConfig.register() }
        registerQuietly { AeadConfig.register() }
    }

    private fun registerQuietly(block: () -> Unit) {
        try {
            block()
        } catch (e: GeneralSecurityException) {
            val message = e.message.orEmpty()
            if (!message.contains("already", ignoreCase = true)) throw e
        }
    }
}

/**
 * Separate Ed25519 signing key and HPKE encryption key.
 * Neither is an ArmorChat messaging identity key.
 *
 * Device-bound copies are sealed by [DealKeyProtector]. Recoverable copies
 * live only inside an encrypted recovery package. The Android Keystore key
 * that seals the device copy is non-exportable and is not in the package.
 */
class DealKeyMaterial private constructor(
    private val signing: KeysetHandle,
    private val encryption: KeysetHandle,
    val signingPublic: ByteArray,
    val encryptionPublic: ByteArray,
) {
    fun signingHandle(): KeysetHandle = signing

    fun encryptionHandle(): KeysetHandle = encryption

    fun encryptionPublicHandle(): KeysetHandle = encryption.publicKeysetHandle

    fun serializedSigning(): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(signing, InsecureSecretKeyAccess.get())

    fun serializedEncryption(): ByteArray =
        TinkProtoKeysetFormat.serializeKeyset(encryption, InsecureSecretKeyAccess.get())

    /** Public HPKE keyset only. This does not contain the private key. */
    fun serializedPublicEncryption(): ByteArray {
        DealTink.register()
        return TinkProtoKeysetFormat.serializeKeysetWithoutSecret(encryption.publicKeysetHandle)
    }

    override fun toString(): String = "DealKeyMaterial(signingBytes=32, encryptionBytes=32)"

    companion object {
        private val hpkeParameters: HpkeParameters by lazy {
            DealTink.register()
            HpkeParameters.builder()
                .setKemId(HpkeParameters.KemId.DHKEM_X25519_HKDF_SHA256)
                .setKdfId(HpkeParameters.KdfId.HKDF_SHA256)
                .setAeadId(HpkeParameters.AeadId.AES_256_GCM)
                .setVariant(HpkeParameters.Variant.NO_PREFIX)
                .build()
        }

        fun generate(): DealKeyMaterial {
            DealTink.register()
            val signing = KeysetHandle.generateNew(com.google.crypto.tink.KeyTemplates.get("ED25519"))
            val encryption = KeysetHandle.generateNew(hpkeParameters)
            return fromHandles(signing, encryption)
        }

        fun fromSerialized(signingKeyset: ByteArray, encryptionKeyset: ByteArray): DealKeyMaterial {
            DealTink.register()
            if (signingKeyset.isEmpty() || encryptionKeyset.isEmpty() ||
                signingKeyset.size > MAX_KEYSET_BYTES || encryptionKeyset.size > MAX_KEYSET_BYTES
            ) {
                throw DealFormatException("Keyset length is invalid.")
            }
            return try {
                fromHandles(
                    TinkProtoKeysetFormat.parseKeyset(signingKeyset, InsecureSecretKeyAccess.get()),
                    TinkProtoKeysetFormat.parseKeyset(encryptionKeyset, InsecureSecretKeyAccess.get()),
                )
            } catch (e: GeneralSecurityException) {
                throw DealFormatException("Keyset could not be parsed.")
            }
        }

        fun parsePublicEncryption(bytes: ByteArray): KeysetHandle {
            DealTink.register()
            if (bytes.isEmpty() || bytes.size > MAX_KEYSET_BYTES) {
                throw DealFormatException("Keyset length is invalid.")
            }
            return try {
                val handle = TinkProtoKeysetFormat.parseKeysetWithoutSecret(bytes)
                requireRawKey(publicEncryptionRaw(handle), "encryption")
                handle
            } catch (e: DealFormatException) {
                throw e
            } catch (e: GeneralSecurityException) {
                throw DealFormatException("Keyset could not be parsed.")
            }
        }

        fun publicEncryptionRaw(handle: KeysetHandle): ByteArray {
            val raw = (handle.primary.key as HpkePublicKey).publicKeyBytes.toByteArray()
            requireRawKey(raw, "encryption")
            return raw.copyOf()
        }

        private fun fromHandles(signing: KeysetHandle, encryption: KeysetHandle): DealKeyMaterial {
            val signingPublic = (signing.publicKeysetHandle.primary.key as Ed25519PublicKey)
                .publicKeyBytes.toByteArray()
            val encryptionPublic = (encryption.publicKeysetHandle.primary.key as HpkePublicKey)
                .publicKeyBytes.toByteArray()
            requireRawKey(signingPublic, "signing")
            requireRawKey(encryptionPublic, "encryption")
            return DealKeyMaterial(signing, encryption, signingPublic.copyOf(), encryptionPublic.copyOf())
        }

        const val MAX_KEYSET_BYTES = 16 * 1024
    }
}
