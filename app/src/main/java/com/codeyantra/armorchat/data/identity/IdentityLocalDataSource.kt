package com.codeyantra.armorchat.data.identity

import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.dao.IdentityDao
import com.codeyantra.armorchat.core.database.entity.IdentityEntity
import com.codeyantra.armorchat.core.security.IdentityStorageException
import com.codeyantra.armorchat.core.security.SecureIdentityKeyStore
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.codeyantra.armorchat.domain.model.UserIdentity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.system.measureTimeMillis

/**
 * Bridges Room public metadata and [SecureIdentityKeyStore] private key material.
 */
@Singleton
class IdentityLocalDataSource @Inject constructor(
    private val identityDao: IdentityDao,
    private val secureIdentityKeyStore: SecureIdentityKeyStore,
    private val clock: AppClock,
) {
    private val createMutex = Mutex()

    fun observeIdentity(): Flow<UserIdentity?> =
        identityDao.observe().map { entity -> entity?.toDomain() }

    suspend fun getIdentity(): UserIdentity? = identityDao.get()?.toDomain()

    /**
     * Idempotent create: if metadata + decryptable key material exist, return them.
     * If metadata exists but key decrypt fails → [IdentityStorageException] (no replace).
     */
    suspend fun createIdentity(): UserIdentity = createMutex.withLock {
        val existingMeta = identityDao.get()
        val hasKeys = secureIdentityKeyStore.hasKeyMaterial()
        IdentityDiagnostics.stage("load-existing", "metadata=${existingMeta != null}, keys=$hasKeys")

        if (existingMeta != null && hasKeys) {
            lateinit var publicKey: ByteArray
            val elapsed = measureTimeMillis {
                publicKey = secureIdentityKeyStore.loadPublicKeyBytes()
            }
            IdentityDiagnostics.stage("load-existing-keys", "success=true, elapsedMs=$elapsed")
            val encoded = Base64Url.encode(publicKey)
            if (encoded != existingMeta.publicIdentityKey) {
                throw IdentityStorageException.InconsistentState(
                    "Stored public key does not match Room identity metadata.",
                )
            }
            val derivedId = ArmorChatId.derive(publicKey)
            if (derivedId != existingMeta.armorChatId) {
                throw IdentityStorageException.InconsistentState(
                    "Stored ArmorChat ID does not match public key.",
                )
            }
            return existingMeta.toDomain()
        }

        if (existingMeta != null && !hasKeys) {
            IdentityDiagnostics.stage("load-existing", "success=false, category=missing-key-material")
            throw IdentityStorageException.Corrupted(
                "Identity metadata exists but encrypted key material is missing.",
            )
        }

        if (existingMeta == null && hasKeys) {
            IdentityDiagnostics.stage("load-existing", "success=false, category=metadata-missing")
            throw IdentityStorageException.InconsistentState(
                "Encrypted identity key material exists but Room metadata is missing.",
            )
        }

        // Fresh create
        lateinit var publicKey: ByteArray
        val keyElapsed = measureTimeMillis {
            publicKey = secureIdentityKeyStore.createAndStore()
        }
        IdentityDiagnostics.stage("secure-key-write", "success=true, elapsedMs=$keyElapsed, existed=false")
        val identity = UserIdentity(
            armorChatId = ArmorChatId.derive(publicKey),
            publicIdentityKey = Base64Url.encode(publicKey),
            createdAtEpochMs = clock.nowEpochMs(),
            version = 1,
        )
        val roomElapsed = measureTimeMillis {
            identityDao.upsert(identity.toEntity())
        }
        IdentityDiagnostics.stage("room-write", "success=true, elapsedMs=$roomElapsed, existed=false")
        return identity
    }

    /**
     * Loads identity and verifies key material is decryptable.
     * Throws if metadata exists but keys cannot be loaded.
     */
    suspend fun requireDecryptableIdentity(): UserIdentity? {
        val meta = identityDao.get() ?: return null
        val publicKey = secureIdentityKeyStore.loadPublicKeyBytes()
        val encoded = Base64Url.encode(publicKey)
        if (encoded != meta.publicIdentityKey || ArmorChatId.derive(publicKey) != meta.armorChatId) {
            throw IdentityStorageException.InconsistentState(
                "Identity metadata does not match decryptable key material.",
            )
        }

        return meta.toDomain()
    }

    private object IdentityDiagnostics {
        fun stage(stage: String, details: String) {
            println("ArmorChatIdentity stage=$stage $details")
        }
    }

    private fun IdentityEntity.toDomain() = UserIdentity(
        armorChatId = armorChatId,
        publicIdentityKey = publicIdentityKey,
        createdAtEpochMs = createdAtEpochMs,
        version = version,
    )

    private fun UserIdentity.toEntity() = IdentityEntity(
        armorChatId = armorChatId,
        publicIdentityKey = publicIdentityKey,
        createdAtEpochMs = createdAtEpochMs,
        version = version,
    )
}
