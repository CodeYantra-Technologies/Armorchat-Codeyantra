package com.codeyantra.armorchat.core.security

import android.content.Context
import com.codeyantra.armorchat.deal.domain.DealIdentity
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
 * The isolated messaging store uses a separate Keystore wrapping key and database.
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
            val clearKeyset = loadSerializedKeyset()
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

    override suspend fun signFramed(context: String, framed: ByteArray): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            require(context in com.codeyantra.armorchat.messaging.ArmorChatContexts.allowed) { "unsupported signing context" }
            val prefix = context.toByteArray(Charsets.US_ASCII) + 0
            require(framed.size <= 8192 + prefix.size)
            require(framed.size >= prefix.size && framed.copyOf(prefix.size).contentEquals(prefix))
            ensureTinkRegistered()
            val serialized = loadSerializedKeyset()
            try {
                val handle = TinkProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get())
                com.codeyantra.armorchat.crypto.RawIdentitySignature.sign(handle, framed)
            } finally {
                serialized.fill(0)
            }
        }
    }

    /**
     * Signs one Armor Deal messaging-identity statement (identity binding,
     * legal name, name-on-version, or Party B review). Payload bytes are signed
     * unmodified. Any other domain, including chat and device contexts, is
     * rejected before the private key is loaded.
     */
    override suspend fun signDealIdentityBinding(payload: ByteArray): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            requireDealIdentityBinding(payload)
            ensureTinkRegistered()
            val serialized = loadSerializedKeyset()
            try {
                val handle = TinkProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get())
                com.codeyantra.armorchat.crypto.RawIdentitySignature.sign(handle, payload)
            } finally {
                serialized.fill(0)
            }
        }
    }

    /** Domain-separated raw Ed25519 device credential signing for the isolated harness. */
    suspend fun signMessagingDevice(signingBytes: ByteArray): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            val prefix = "ArmorChat/device/v1\u0000".toByteArray(Charsets.US_ASCII)
            require(signingBytes.size <= 4096 && signingBytes.take(prefix.size).toByteArray().contentEquals(prefix))
            ensureTinkRegistered()
            val serialized = loadSerializedKeyset()
            try {
                val handle = TinkProtoKeysetFormat.parseKeyset(serialized, InsecureSecretKeyAccess.get())
                com.codeyantra.armorchat.crypto.RawIdentitySignature.sign(handle, signingBytes)
            } finally { serialized.fill(0) }
        }
    }

    override suspend fun exportSerializedKeyset(): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureTinkRegistered()
            val clearKeyset = loadSerializedKeyset()
            try {
                val handle = TinkProtoKeysetFormat.parseKeyset(
                    clearKeyset,
                    InsecureSecretKeyAccess.get(),
                )
                extractPublicKeyBytes(handle)
                clearKeyset
            } catch (e: IdentityStorageException) {
                clearKeyset.fill(0)
                throw e
            } catch (e: Exception) {
                clearKeyset.fill(0)
                throw IdentityStorageException.DecryptFailed(
                    "Parsed identity keyset is invalid after decrypt.",
                    e,
                )
            }
        }
    }

    override suspend fun importSerializedKeyset(serializedKeyset: ByteArray): ByteArray = mutex.withLock {
        withContext(Dispatchers.IO) {
            ensureTinkRegistered()
            val keysetCopy = serializedKeyset.copyOf()
            val publicKey = try {
                val handle = TinkProtoKeysetFormat.parseKeyset(
                    keysetCopy,
                    InsecureSecretKeyAccess.get(),
                )
                extractPublicKeyBytes(handle)
            } catch (e: IdentityStorageException) {
                throw e
            } catch (e: Exception) {
                throw IdentityStorageException.Corrupted(
                    "Imported Ed25519 identity keyset is invalid.",
                    e,
                )
            }
            val envelope = kek.encrypt(keysetCopy)
            keysetCopy.fill(0)
            writeEnvelope(envelope)
            publicKey
        }
    }

    private fun blobFile(): File {
        val dir = File(context.noBackupFilesDir, RELATIVE_DIR)
        return File(dir, BLOB_NAME)
    }

    private fun loadSerializedKeyset(): ByteArray {
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
        return kek.decrypt(envelope)
    }

    private fun writeEnvelope(envelope: ByteArray) {
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
        const val DEAL_BINDING_PREFIX = "ArmorDeal/identity-binding/v1\u0000"
        private const val MAX_BINDING_PAYLOAD = 8192

        /**
         * Explicit allowlist of Armor Deal statement prefixes the messaging
         * identity key may sign: exact domain bytes plus one NUL. Built from
         * [DealIdentity.MESSAGING_SIGNER_DOMAINS] so the payload builders and
         * this gate cannot drift. There is no generic "ArmorDeal/" prefix.
         */
        private val DEAL_SIGNING_PREFIXES: List<ByteArray> =
            DealIdentity.MESSAGING_SIGNER_DOMAINS.map { domain ->
                require(domain.isNotEmpty() && domain.all { it.code in 0x21..0x7e })
                (domain + "\u0000").toByteArray(Charsets.US_ASCII)
            }

        internal fun requireDealIdentityBinding(payload: ByteArray) {
            if (payload.size > MAX_BINDING_PAYLOAD) {
                throw IllegalArgumentException("unsupported signing context")
            }
            val allowed = DEAL_SIGNING_PREFIXES.any { prefix ->
                payload.size > prefix.size && hasPrefix(payload, prefix)
            }
            if (!allowed) throw IllegalArgumentException("unsupported signing context")
        }

        private fun hasPrefix(payload: ByteArray, prefix: ByteArray): Boolean {
            for (i in prefix.indices) if (payload[i] != prefix[i]) return false
            return true
        }
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
