package com.codeyantra.armorchat.deal.crypto

import com.codeyantra.armorchat.crypto.RawIdentitySignature
import com.codeyantra.armorchat.deal.domain.DealCryptoException
import com.codeyantra.armorchat.deal.domain.DealFormatException
import com.codeyantra.armorchat.deal.domain.requireRawKey
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.subtle.Ed25519Verify
import java.security.GeneralSecurityException

/** Ed25519 helpers for ArmorChat messaging keys used as deal-binding authorities. */
object DealIdentityCrypto {
    fun publicKey(handle: KeysetHandle): ByteArray {
        DealTink.register()
        val bytes = (handle.publicKeysetHandle.primary.key as Ed25519PublicKey).publicKeyBytes.toByteArray()
        requireRawKey(bytes, "messaging")
        return bytes.copyOf()
    }

    fun sign(handle: KeysetHandle, payload: ByteArray): ByteArray {
        DealTink.register()
        return RawIdentitySignature.sign(handle, payload)
    }

    fun verify(publicKey: ByteArray, signature: ByteArray, payload: ByteArray) {
        if (signature.size != 64) throw DealFormatException("Ed25519 signature must be 64 bytes.")
        requireEd25519Point(publicKey)
        try {
            Ed25519Verify(publicKey).verify(signature, payload)
        } catch (e: GeneralSecurityException) {
            throw DealCryptoException.InvalidSignature()
        } catch (e: IllegalArgumentException) {
            throw DealCryptoException.InvalidSignature()
        }
    }

    fun requireEd25519Point(publicKey: ByteArray) {
        requireRawKey(publicKey, "messaging")
        val point = try {
            org.bouncycastle.math.ec.rfc8032.Ed25519.validatePublicKeyFull(publicKey, 0)
        } catch (e: IllegalArgumentException) {
            false
        }
        if (!point) throw DealFormatException("Messaging public key is not a valid Ed25519 point.")
        try {
            Ed25519Verify(publicKey)
        } catch (e: GeneralSecurityException) {
            throw DealFormatException("Messaging public key is not a valid Ed25519 point.")
        } catch (e: IllegalArgumentException) {
            throw DealFormatException("Messaging public key is not a valid Ed25519 point.")
        }
    }
}
