package com.codeyantra.armorchat.core.crypto

/**
 * Boundary for a future audited end-to-end encryption implementation.
 *
 * Do not invent a custom protocol here. Callers in this phase must not
 * treat any implementation as production security.
 */
interface CryptoEngine {
    suspend fun encrypt(plaintext: ByteArray, recipientPublicKey: ByteArray): Ciphertext
    suspend fun decrypt(ciphertext: Ciphertext, senderPublicKey: ByteArray): ByteArray
}

@JvmInline
value class Ciphertext(val bytes: ByteArray)
