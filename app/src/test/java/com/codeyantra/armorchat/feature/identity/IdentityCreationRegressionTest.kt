package com.codeyantra.armorchat.feature.identity

import com.codeyantra.armorchat.core.security.IdentityStorageException
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.codeyantra.armorchat.domain.usecase.CreateIdentityBackupUseCase
import com.codeyantra.armorchat.domain.usecase.CreateIdentityUseCase
import com.codeyantra.armorchat.domain.usecase.ObserveIdentityUseCase
import com.codeyantra.armorchat.domain.usecase.RestoreIdentityUseCase
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
class IdentityCreationRegressionTest {
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
    fun freshInstall_createIdentity_emitsReadyAndNavigatesWithoutRestart() = runTest(dispatcher) {
        val repo = FakeIdentityRepository()
        val viewModel = createViewModel(repo)

        // 1. Fresh install starts in NoIdentity after Checking completes
        advanceUntilIdle()
        assertThat(viewModel.identityState.value).isEqualTo(IdentityState.NoIdentity)

        // 2. User creates identity
        var createdIdentity: UserIdentity? = null
        viewModel.create("Amit", "amit-handle") { identity ->
            createdIdentity = identity
        }
        advanceUntilIdle()

        // 3. Persistence completes and authoritative state becomes Ready immediately in same process
        assertThat(createdIdentity).isNotNull()
        assertThat(viewModel.creationState.value).isEqualTo(IdentityCreationState.Success(createdIdentity!!))
        assertThat(viewModel.identityState.value).isEqualTo(IdentityState.Ready(createdIdentity!!))
        assertThat(repo.createCount).isEqualTo(1)
    }

    @Test
    fun doubleTap_orRecomposition_doesNotDuplicateCreation() = runTest(dispatcher) {
        val repo = FakeIdentityRepository()
        val viewModel = createViewModel(repo)
        advanceUntilIdle()

        viewModel.create("Amit", "amit-handle") {}
        viewModel.create("Amit", "amit-handle") {}
        advanceUntilIdle()

        assertThat(repo.createCount).isEqualTo(1)
        assertThat(viewModel.identityState.value).isInstanceOf(IdentityState.Ready::class.java)
    }

    @Test
    fun viewModelRecreation_detectsExistingIdentityAndRemainsReady() = runTest(dispatcher) {
        val repo = FakeIdentityRepository()
        val vm1 = createViewModel(repo)
        advanceUntilIdle()

        vm1.create("Amit", "amit-handle") {}
        advanceUntilIdle()
        assertThat(vm1.identityState.value).isInstanceOf(IdentityState.Ready::class.java)

        // Simulate ViewModel recreation / screen re-open in same process
        val vm2 = createViewModel(repo)
        advanceUntilIdle()
        assertThat(vm2.identityState.value).isEqualTo(vm1.identityState.value)
    }

    @Test
    fun restoreIdentity_followsSameTransitionToReadyWithoutRestart() = runTest(dispatcher) {
        val repo = FakeIdentityRepository()
        val viewModel = createViewModel(repo)
        advanceUntilIdle()
        assertThat(viewModel.identityState.value).isEqualTo(IdentityState.NoIdentity)

        var restored: UserIdentity? = null
        val dummyBackup = byteArrayOf(1, 2, 3)
        viewModel.restoreFromBackup(dummyBackup, "password123456") { identity ->
            restored = identity
        }
        advanceUntilIdle()

        assertThat(restored).isNotNull()
        assertThat(viewModel.restoreState.value).isEqualTo(IdentityRestoreState.Success(restored!!))
        assertThat(viewModel.identityState.value).isEqualTo(IdentityState.Ready(restored!!))
    }

    @Test
    fun persistenceFailure_remainsOnOnboardingWithError() = runTest(dispatcher) {
        val repo = FakeIdentityRepository().apply { failCreate = true }
        val viewModel = createViewModel(repo)
        advanceUntilIdle()

        viewModel.create("Amit", "amit-handle") {}
        advanceUntilIdle()

        assertThat(viewModel.identityState.value).isEqualTo(IdentityState.NoIdentity)
        assertThat(viewModel.creationState.value).isInstanceOf(IdentityCreationState.RecoverableError::class.java)
    }

    @Test
    fun noInfiniteCheckingOrLoadingLoop() = runTest(dispatcher) {
        val repo = FakeIdentityRepository()
        val viewModel = createViewModel(repo)

        // Starts in Checking
        assertThat(viewModel.identityState.value).isEqualTo(IdentityState.Checking)

        advanceUntilIdle()
        // Transitions cleanly to NoIdentity without toggling
        assertThat(viewModel.identityState.value).isEqualTo(IdentityState.NoIdentity)

        viewModel.create("Amit", "amit-handle") {}
        advanceUntilIdle()

        // Transitions cleanly to Ready and stays stable
        assertThat(viewModel.identityState.value).isInstanceOf(IdentityState.Ready::class.java)
    }

    private fun createViewModel(repo: FakeIdentityRepository): IdentityViewModel {
        val fakeMessaging = object : com.codeyantra.armorchat.messaging.ChatMessagingController {
            override val network = MutableStateFlow(com.codeyantra.armorchat.core.network.NetworkAvailability())
            override fun observeSession(contactId: String) = kotlinx.coroutines.flow.flowOf(com.codeyantra.armorchat.messaging.SecureSessionView())
            override suspend fun ensureProvisioned(contact: com.codeyantra.armorchat.domain.model.Contact) = Unit
            override suspend fun getMyContactQrV2(): String? = null
            override suspend fun importPeerContactV2(payload: com.codeyantra.armorchat.domain.identity.ContactQrV2) = com.codeyantra.armorchat.messaging.RouteImportResult.Rejected
            override suspend fun importPeerRoute(raw: String, contactId: String?) = com.codeyantra.armorchat.messaging.RouteImportResult.Rejected
            override fun localRouteQr(contactId: String) = null
            override suspend fun sendText(conversationId: String, text: String) = com.codeyantra.armorchat.domain.repository.SendTextResult.TransportUnavailable
            override suspend fun syncContact(contactId: String) = Unit
            override suspend fun markOpened(conversationId: String, messageId: String) = Unit
            override suspend fun refreshPolicy() = Unit
        }
        return IdentityViewModel(
            observeIdentity = ObserveIdentityUseCase(repo),
            createIdentity = CreateIdentityUseCase(repo),
            createIdentityBackup = CreateIdentityBackupUseCase(repo),
            restoreIdentity = RestoreIdentityUseCase(repo),
            identityRepository = repo,
            messaging = fakeMessaging,
        )
    }

    private class FakeIdentityRepository : IdentityRepository {
        val identity = UserIdentity(
            armorChatId = "ac1:TESTID000000000000000000000000",
            publicIdentityKey = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA",
            createdAtEpochMs = 1000L,
            version = 1,
        )
        var createCount = 0
            private set
        var failCreate = false
        private val state = MutableStateFlow<UserIdentity?>(null)

        override fun observeIdentity(): Flow<UserIdentity?> = state

        override suspend fun getIdentity(): UserIdentity? = state.value

        override suspend fun createIdentity(): UserIdentity {
            if (failCreate) throw IllegalArgumentException("Storage write failed")
            createCount++
            state.value = identity
            return identity
        }

        override suspend fun updateProfile(displayName: String, handle: String) {
            state.value = (state.value ?: identity).copy(displayName = displayName, handle = handle)
        }

        override suspend fun exportIdentityBackup(password: CharArray): ByteArray = byteArrayOf(1)

        override suspend fun restoreIdentityBackup(
            backupBytes: ByteArray,
            password: CharArray,
        ): UserIdentity {
            state.value = identity
            return identity
        }
    }
}
