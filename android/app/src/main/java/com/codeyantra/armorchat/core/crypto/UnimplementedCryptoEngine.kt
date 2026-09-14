package com.codeyantra.armorchat.core.crypto

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Placeholder so the crypto boundary exists at compile time.
 * Always fails — production encryption is not implemented.
 */
@Singleton
class UnimplementedCryptoEngine @Inject constructor() : CryptoEngine {
    override suspend fun encrypt(plaintext: ByteArray, recipientPublicKey: ByteArray): Ciphertext {
        unused(plaintext, recipientPublicKey)
        error(MESSAGE)
    }

    override suspend fun decrypt(ciphertext: Ciphertext, senderPublicKey: ByteArray): ByteArray {
        unused(ciphertext, senderPublicKey)
        error(MESSAGE)
    }

    private fun unused(vararg ignored: Any?) = Unit

    private companion object {
        const val MESSAGE =
            "Production encryption is not implemented. CryptoEngine is a boundary only."
    }
}
