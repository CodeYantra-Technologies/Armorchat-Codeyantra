package com.codeyantra.armorchat.feature.identity

import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.codeyantra.armorchat.domain.usecase.CreateIdentityUseCase
import com.codeyantra.armorchat.domain.usecase.ObserveIdentityUseCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class IdentityViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun doubleTapCreatesOnceAndPublishesSuccess() = runTest(dispatcher) {
        val repository = CountingIdentityRepository()
        val viewModel = IdentityViewModel(
            observeIdentity = ObserveIdentityUseCase(repository),
            createIdentity = CreateIdentityUseCase(repository),
        )

        viewModel.create {}
        viewModel.create {}
        advanceUntilIdle()

        assertThat(repository.createCount).isEqualTo(1)
        assertThat(viewModel.creationState.value).isEqualTo(
            IdentityCreationState.Success(repository.identity),
        )
    }
}

private class CountingIdentityRepository : IdentityRepository {
    val identity = UserIdentity(
        armorChatId = "ac1:TESTID000000000000000000000000",
        publicIdentityKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
        createdAtEpochMs = 1L,
        version = 1,
    )
    var createCount = 0
        private set
    private val state = MutableStateFlow<UserIdentity?>(null)

    override fun observeIdentity(): Flow<UserIdentity?> = state

    override suspend fun getIdentity(): UserIdentity? = state.value

    override suspend fun createIdentity(): UserIdentity {
        createCount += 1
        state.value = identity
        return identity
    }

}
