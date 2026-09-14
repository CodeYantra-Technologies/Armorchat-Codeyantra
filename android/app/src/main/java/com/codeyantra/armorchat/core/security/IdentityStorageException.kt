package com.codeyantra.armorchat.core.security

/**
 * Typed recoverable failure for local identity key storage.
 *
 * Callers must surface these to the user. Never silently regenerate identity
 * and never fall back to plaintext private-key storage.
 */
sealed class IdentityStorageException(
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause) {

    class KeystoreUnavailable(
        message: String = "Android Keystore is unavailable on this device.",
        cause: Throwable? = null,
    ) : IdentityStorageException(message, cause)

    class KekUnusable(
        message: String = "Identity key-encryption key is missing or unusable.",
        cause: Throwable? = null,
    ) : IdentityStorageException(message, cause)

    class DecryptFailed(
        message: String = "Encrypted identity key material could not be decrypted.",
        cause: Throwable? = null,
    ) : IdentityStorageException(message, cause)

    class Corrupted(
        message: String = "Stored identity key material is missing or corrupted.",
        cause: Throwable? = null,
    ) : IdentityStorageException(message, cause)

    class InconsistentState(
        message: String = "Identity metadata and key material are inconsistent.",
        cause: Throwable? = null,
    ) : IdentityStorageException(message, cause)
}
