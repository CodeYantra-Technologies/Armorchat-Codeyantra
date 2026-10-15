package com.codeyantra.armorchat.deal.domain

/** Failures that carry no contract text, keys, or recovery secrets. */
open class DealException(message: String) : RuntimeException(message)

class DealFormatException(message: String) : DealException(message)

class DealStateException(message: String) : DealException(message)

class ImmutableVersionException : DealException("A signed deal version cannot be edited.")

class DealConflictException : DealException("Existing deal data does not match this restore.")

sealed class DealCryptoException(message: String) : DealException(message) {
    class AuthenticationFailed : DealCryptoException("Authenticated decryption failed.")
    class InvalidSignature : DealCryptoException("Signature rejected.")
    class WrongSigner : DealCryptoException("Signer key does not match the pinned party.")
    class WrongVersion : DealCryptoException("Signature is not bound to this version.")
    class WrongDeal : DealCryptoException("Signature is not bound to this deal.")
    class WrongAction : DealCryptoException("Signature is not bound to this action.")
    class UnknownParty : DealCryptoException("No key envelope exists for this party.")
}

sealed class DealRecoveryException(message: String) : DealException(message) {
    class WeakSecret : DealRecoveryException("Recovery secret does not meet the entropy floor.")
    class WrongSecret : DealRecoveryException("Recovery secret was rejected.")
    class Corrupted : DealRecoveryException("Recovery package is corrupted.")
    class MissingObject : DealRecoveryException("Backup object is missing.")
    class Rejected : DealRecoveryException("Recovered deal failed verification.")
}

sealed class DealKeyException(message: String) : DealException(message) {
    class KeystoreUnavailable : DealKeyException("Android Keystore is unavailable for Armor Deal.")
    class RevokedDeviceAccess : DealKeyException("Device-bound Armor Deal key is unavailable.")
}

class DealAccessException private constructor(message: String) : DealException(message) {
    class Unauthenticated : DealException("Authentication is required.")
    class Forbidden : DealException("This participant cannot access the deal.")
}
