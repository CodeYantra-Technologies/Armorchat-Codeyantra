package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class CreateIdentityUseCaseTest {
    @Test
    fun createsIdentityThroughRepository() = runTest {
        val repo = FakeIdentityRepository()
        val useCase = CreateIdentityUseCase(repo)
        val result = useCase()
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().armorChatId).isEqualTo("ac1:TESTID000000000000000000000000")
        assertThat(repo.getIdentity()).isNotNull()
    }
}

internal class FakeIdentityRepository : IdentityRepository {
    private val identity = UserIdentity(
        armorChatId = "ac1:TESTID000000000000000000000000",
        publicIdentityKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        createdAtEpochMs = 1L,
        version = 1,
    )
    private val state = MutableStateFlow<UserIdentity?>(null)

    override fun observeIdentity(): Flow<UserIdentity?> = state

    override suspend fun getIdentity(): UserIdentity? = state.value

    override suspend fun createIdentity(): UserIdentity {
        state.value = identity
        return identity
    }

    override suspend fun updateProfile(displayName: String, handle: String) {
        state.value = (state.value ?: identity).copy(displayName = displayName, handle = handle)
    }

    override suspend fun exportIdentityBackup(password: CharArray): ByteArray = byteArrayOf(1, 2, 3)

    override suspend fun restoreIdentityBackup(
        backupBytes: ByteArray,
        password: CharArray,
    ): UserIdentity {
        state.value = identity
        return identity
    }
}
