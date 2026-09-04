package com.codeyantra.armorchat.core.security

import android.content.Context
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.google.crypto.tink.InsecureSecretKeyAccess
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.KeysetHandle
import com.google.crypto.tink.TinkProtoKeysetFormat
import com.google.crypto.tink.signature.Ed25519PrivateKey
import com.google.crypto.tink.signature.Ed25519PublicKey
import com.google.crypto.tink.signature.SignatureConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Persists a Tink Ed25519 identity keyset encrypted under [AndroidKeystoreKek].
 *
 * Path: noBackupFilesDir/identity/ed25519_identity.v1.enc
 *
 * Reserved (not implemented): noBackupFilesDir/identity/x25519_session.v1.enc
 * for future session / ECDH keys — architecturally separate from Ed25519.
 */
@Singleton
class TinkEd25519IdentityKeyStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val kek: AndroidKeystoreKek,
) : SecureIdentityKeyStore {

    private val mutex = Mutex()

    override suspend fun hasKeyMaterial(): Boolean = withContext(Dispatchers.IO) {
        blobFile().exists() && blobFile().length() > 0L
    }

    override suspend fun createAndStore(): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureTinkRegistered()
            val handle = try {
                KeysetHandle.generateNew(KeyTemplates.get("ED25519"))
            } catch (e: Exception) {
                throw IdentityStorageException.Corrupted(
                    "Failed to generate Ed25519 identity keyset.",
                    e,
                )
            }
            val publicKey = extractPublicKeyBytes(handle)
            val clearKeyset = try {
                TinkProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get())
            } catch (e: Exception) {
                throw IdentityStorageException.Corrupted(
                    "Failed to serialize Ed25519 identity keyset.",
                    e,
                )
            }
            val envelope = kek.encrypt(clearKeyset)
            // Best-effort wipe of cleartext keyset bytes after encrypt.
            clearKeyset.fill(0)
            val file = blobFile()
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            try {
                tmp.writeBytes(envelope)
                if (!tmp.renameTo(file)) {
                    file.writeBytes(envelope)
                    tmp.delete()
                }
            } catch (e: Exception) {
                tmp.delete()
                throw IdentityStorageException.Corrupted(
                    "Failed to persist encrypted identity key blob.",
                    e,
                )
            }
            publicKey
        }
    }

    override suspend fun loadPublicKeyBytes(): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureTinkRegistered()
            val file = blobFile()
            if (!file.exists() || file.length() == 0L) {
                throw IdentityStorageException.Corrupted(
                    "Encrypted Ed25519 identity blob is missing.",
                )
            }
            val envelope = try {
                file.readBytes()
            } catch (e: Exception) {
                throw IdentityStorageException.Corrupted(
                    "Unable to read encrypted identity blob.",
                    e,
                )
            }
            val clearKeyset = kek.decrypt(envelope)
            try {
                val handle = TinkProtoKeysetFormat.parseKeyset(
                    clearKeyset,
                    InsecureSecretKeyAccess.get(),
                )
                extractPublicKeyBytes(handle)
            } catch (e: IdentityStorageException) {
                throw e
            } catch (e: Exception) {
                throw IdentityStorageException.DecryptFailed(
                    "Parsed identity keyset is invalid after decrypt.",
                    e,
                )
            } finally {
                clearKeyset.fill(0)
            }
        }
    }

    private fun blobFile(): File {
        val dir = File(context.noBackupFilesDir, RELATIVE_DIR)
        return File(dir, BLOB_NAME)
    }

    private fun extractPublicKeyBytes(handle: KeysetHandle): ByteArray {
        val privateKey = handle.primary.key as? Ed25519PrivateKey
            ?: throw IdentityStorageException.Corrupted(
                "Identity keyset primary key is not Ed25519.",
            )
        val publicKey = privateKey.publicKey as Ed25519PublicKey
        val bytes = publicKey.publicKeyBytes.toByteArray()
        if (bytes.size != ArmorChatId.PUBLIC_KEY_LENGTH) {
            throw IdentityStorageException.Corrupted(
                "Unexpected Ed25519 public key length: ${bytes.size}",
            )
        }
        return bytes
    }

    companion object {
        const val RELATIVE_DIR = "identity"
        const val BLOB_NAME = "ed25519_identity.v1.enc"
        // Reserved for future X25519 session keys (not implemented in Phase 1A):
        // const val X25519_BLOB_NAME = "x25519_session.v1.enc"

        private val tinkReady = AtomicBoolean(false)

        private fun ensureTinkRegistered() {
            if (tinkReady.get()) return
            synchronized(tinkReady) {
                if (!tinkReady.get()) {
                    SignatureConfig.register()
                    tinkReady.set(true)
                }
            }
        }
    }
}
