package com.codeyantra.armorchat.core.security

import android.content.Context
import android.content.ContextWrapper
import java.io.File
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Software AES-GCM KEK for JVM tests. The Tink Ed25519 signing path stays real. */
class SoftwareIdentityKek : AndroidKeystoreKek() {
    private val key = SecretKeySpec(ByteArray(32).also { SecureRandom().nextBytes(it) }, "AES")

    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val body = cipher.doFinal(plaintext)
        return byteArrayOf(ENVELOPE_VERSION) + iv + body
    }

    override fun decrypt(envelope: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, envelope.copyOfRange(1, 13)))
        return cipher.doFinal(envelope.copyOfRange(13, envelope.size))
    }
}

/** A real [TinkEd25519IdentityKeyStore] rooted in its own temporary no-backup directory. */
fun realTinkIdentityStore(base: Context, root: File): TinkEd25519IdentityKeyStore {
    root.mkdirs()
    val context = object : ContextWrapper(base) {
        override fun getNoBackupFilesDir(): File = root
    }
    return TinkEd25519IdentityKeyStore(context, SoftwareIdentityKek())
}
