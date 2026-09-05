package com.codeyantra.armorchat.core.security

/**
 * Device-local storage for the long-term Ed25519 identity keyset.
 *
 * Private key material is sealed with a non-exportable Android Keystore
 * AES-256-GCM KEK and written under [android.content.Context.getNoBackupFilesDir].
 *
 * Future X25519 / session keys MUST use a separate storage slot — never reuse
 * the Ed25519 identity key for ECDH.
 */
interface SecureIdentityKeyStore {
    /** True when an encrypted Ed25519 identity blob is present on disk. */
    suspend fun hasKeyMaterial(): Boolean

    /**
     * Generates a new Tink Ed25519 keyset, encrypts it with the Keystore KEK,
     * and persists the blob. Fails hard if Keystore/KEK is unusable.
     *
     * @return raw 32-byte Ed25519 public key
     */
    suspend fun createAndStore(): ByteArray

    /**
     * Decrypts the stored keyset and returns the raw 32-byte Ed25519 public key.
     * Throws [IdentityStorageException] on Keystore/KEK/decrypt failure.
     * Never auto-creates a replacement identity.
     */
    suspend fun loadPublicKeyBytes(): ByteArray

    /**
     * Decrypts and returns the serialized Tink Ed25519 private keyset for an
     * explicit user-controlled encrypted backup flow.
     *
     * Callers must immediately wrap the returned bytes in authenticated backup
     * encryption and wipe the returned array when finished.
     */
    suspend fun exportSerializedKeyset(): ByteArray

    /**
     * Imports a previously serialized Tink Ed25519 keyset, validates it, then
     * seals it with this device's non-exportable Android Keystore KEK.
     *
     * @return raw 32-byte Ed25519 public key
     */
    suspend fun importSerializedKeyset(serializedKeyset: ByteArray): ByteArray
}
