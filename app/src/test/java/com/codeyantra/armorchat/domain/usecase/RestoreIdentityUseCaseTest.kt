package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Test

class RestoreIdentityUseCaseTest {
    @Test
    fun restoresIdentityThroughRepository() = runTest {
        val repo = RestoreFakeIdentityRepository()
        val useCase = RestoreIdentityUseCase(repo)
        val result = useCase(byteArrayOf(1, 2, 3), "correct horse battery".toCharArray())
        assertThat(result.isSuccess).isTrue()
        assertThat(result.getOrThrow().armorChatId).isEqualTo(repo.identity.armorChatId)
    }
}

private class RestoreFakeIdentityRepository : IdentityRepository {
    val identity = UserIdentity(
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

    override suspend fun exportIdentityBackup(password: CharArray): ByteArray = byteArrayOf(1, 2, 3)

    override suspend fun restoreIdentityBackup(
        backupBytes: ByteArray,
        password: CharArray,
    ): UserIdentity {
        state.value = identity
        return identity
    }
}
