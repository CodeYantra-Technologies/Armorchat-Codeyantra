package com.codeyantra.armorchat.feature.identity

import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.repository.IdentityRepository
import com.codeyantra.armorchat.domain.usecase.CreateIdentityUseCase
import com.codeyantra.armorchat.domain.usecase.CreateIdentityBackupUseCase
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
        val viewModel = IdentityViewModel(
            observeIdentity = ObserveIdentityUseCase(repository),
            createIdentity = CreateIdentityUseCase(repository),
            createIdentityBackup = CreateIdentityBackupUseCase(repository),
            restoreIdentity = RestoreIdentityUseCase(repository),
            identityRepository = repository,
            messaging = fakeMessaging,
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
