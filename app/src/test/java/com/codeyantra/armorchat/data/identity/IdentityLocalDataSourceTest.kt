package com.codeyantra.armorchat.data.identity

import com.codeyantra.armorchat.core.common.AppClock
import com.codeyantra.armorchat.core.database.dao.IdentityDao
import com.codeyantra.armorchat.core.database.entity.IdentityEntity
import com.codeyantra.armorchat.core.security.IdentityStorageException
import com.codeyantra.armorchat.core.security.SecureIdentityKeyStore
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.identity.Base64Url
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import org.junit.Test

class IdentityLocalDataSourceTest {

    private val publicKey = ByteArray(32) { (it * 3).toByte() }

    @Test
    fun create_isIdempotent() = runTest {
        val keys = FakeSecureIdentityKeyStore(publicKey)
        val dao = FakeIdentityDao()
        val ds = IdentityLocalDataSource(dao, keys, FixedClock(1000L))

        val first = ds.createIdentity()
        val second = ds.createIdentity()

        assertThat(second.armorChatId).isEqualTo(first.armorChatId)
        assertThat(second.publicIdentityKey).isEqualTo(first.publicIdentityKey)
        assertThat(keys.createCount).isEqualTo(1)
        assertThat(first.armorChatId).isEqualTo(ArmorChatId.derive(publicKey))
    }

    @Test
    fun concurrentCreate_callsGenerateOnlyOneIdentity() = runTest {
        val keys = FakeSecureIdentityKeyStore(publicKey)
        val dao = FakeIdentityDao()
        val ds = IdentityLocalDataSource(dao, keys, FixedClock(1000L))

        val identities = listOf(
            async { ds.createIdentity() },
            async { ds.createIdentity() },
            async { ds.createIdentity() },
        ).awaitAll()

        assertThat(identities.map { it.armorChatId }.toSet()).hasSize(1)
        assertThat(keys.createCount).isEqualTo(1)
    }

    @Test
    fun create_doesNotReplaceWhenDecryptFails() = runTest {
        val keys = FakeSecureIdentityKeyStore(publicKey, failLoad = true)
        keys.markExisting()
        val dao = FakeIdentityDao()
        dao.upsert(
            IdentityEntity(
                armorChatId = ArmorChatId.derive(publicKey),
                publicIdentityKey = Base64Url.encode(publicKey),
                createdAtEpochMs = 1L,
                version = 1,
            ),
        )
        val ds = IdentityLocalDataSource(dao, keys, FixedClock(2000L))

        try {
            ds.createIdentity()
            throw AssertionError("expected IdentityStorageException")
        } catch (e: IdentityStorageException) {
            assertThat(e).isInstanceOf(IdentityStorageException.DecryptFailed::class.java)
        }
        assertThat(keys.createCount).isEqualTo(0)
        assertThat(dao.get()?.armorChatId).isEqualTo(ArmorChatId.derive(publicKey))
    }

    @Test
    fun create_surfacesMissingKeyMaterialWithExistingMetadata() = runTest {
        val keys = FakeSecureIdentityKeyStore(publicKey)
        val dao = FakeIdentityDao()
        dao.upsert(
            IdentityEntity(
                armorChatId = "ac1:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
                publicIdentityKey = Base64Url.encode(publicKey),
                createdAtEpochMs = 1L,
                version = 1,
            ),
        )
        val ds = IdentityLocalDataSource(dao, keys, FixedClock(3L))
        try {
            ds.createIdentity()
            throw AssertionError("expected IdentityStorageException")
        } catch (e: IdentityStorageException.Corrupted) {
            assertThat(e.message).contains("missing")
        }
        assertThat(keys.createCount).isEqualTo(0)
    }

    @Test
    fun create_doesNotRebuildWhenKeyMaterialHasNoMetadata() = runTest {
        val keys = FakeSecureIdentityKeyStore(publicKey).also { it.markExisting() }
        val dao = FakeIdentityDao()
        val ds = IdentityLocalDataSource(dao, keys, FixedClock(3L))

        try {
            ds.createIdentity()
            throw AssertionError("expected IdentityStorageException")
        } catch (e: IdentityStorageException.InconsistentState) {
            assertThat(e.message).contains("metadata")
        }
        assertThat(keys.createCount).isEqualTo(0)
        assertThat(dao.get()).isNull()
    }

}

private class FixedClock(private val now: Long) : AppClock {
    override fun nowEpochMs(): Long = now
}

private class FakeIdentityDao : IdentityDao {
    private val state = MutableStateFlow<IdentityEntity?>(null)

    override fun observe(): Flow<IdentityEntity?> = state

    override suspend fun get(): IdentityEntity? = state.value

    override suspend fun upsert(entity: IdentityEntity) {
        state.value = entity
    }

    override suspend fun clear() {
        state.value = null
    }
}

private class FakeSecureIdentityKeyStore(
    private val publicKey: ByteArray,
    private val failLoad: Boolean = false,
) : SecureIdentityKeyStore {
    var createCount = 0
        private set
    private var stored = false

    fun markExisting() {
        stored = true
    }

    override suspend fun hasKeyMaterial(): Boolean = stored

    override suspend fun createAndStore(): ByteArray {
        createCount++
        stored = true
        return publicKey.copyOf()
    }

    override suspend fun loadPublicKeyBytes(): ByteArray {
        if (!stored) {
            throw IdentityStorageException.Corrupted("missing")
        }
        if (failLoad) {
            throw IdentityStorageException.DecryptFailed("boom")
        }
        return publicKey.copyOf()
    }

}
