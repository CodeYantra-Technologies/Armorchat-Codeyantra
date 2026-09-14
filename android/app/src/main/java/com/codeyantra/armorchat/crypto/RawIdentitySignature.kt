package com.codeyantra.armorchat.crypto

import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.signature.Ed25519PrivateKey
import com.google.crypto.tink.subtle.Ed25519Sign

object RawIdentitySignature {
    /** Tink's raw primitive emits 64 bytes. Never strip an assumed keyset prefix. */
    fun sign(handle: KeysetHandle, bytes: ByteArray): ByteArray {
        val key = handle.primary.key as Ed25519PrivateKey
        val seed = key.privateKeyBytes.toByteArray(InsecureSecretKeyAccess.get())
        return try { Ed25519Sign(seed).sign(bytes).also { check(it.size == 64) } } finally { seed.fill(0) }
    }
}
