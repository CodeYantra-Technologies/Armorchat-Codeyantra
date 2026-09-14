package com.codeyantra.armorchat.feature.contacts

import com.codeyantra.armorchat.domain.identity.IdentityQrPayload
import com.codeyantra.armorchat.domain.model.*
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.core.network.NetworkAvailability
import com.codeyantra.armorchat.messaging.ChatMessagingController
import com.codeyantra.armorchat.messaging.RouteImportResult
import com.codeyantra.armorchat.messaging.SecureSessionView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class ContactsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private class Conversations : ConversationRepository {
        override fun observeConversations() = MutableStateFlow<List<Conversation>>(emptyList())
        override fun observeConversation(id: String) = MutableStateFlow<Conversation?>(null)
        override suspend fun getConversation(id: String): Conversation? = null
        override suspend fun getOrCreateForContact(contact: Contact) =
            Conversation("c-" + contact.id, contact.id, contact.displayName, 1)
    }
    private class Repository : ContactRepository {
        var inspections = 0
        var saves = 0
        var fail = false
        val payload = IdentityQrPayload("ac1:test-only", "public-test-only")
        override fun observeContacts() = MutableStateFlow<List<Contact>>(emptyList())
        override fun observeContact(id: String) = MutableStateFlow<Contact?>(null)
        override suspend fun getContact(id: String): Contact? = null
        override suspend fun findByPublicKey(publicIdentityKey: String): Contact? = null
        override suspend fun inspectQr(raw: String): ContactExchangeResult {
            inspections++
            if (fail) error("Sensitive internal failure details")
            return ContactExchangeResult.Ready(com.codeyantra.armorchat.domain.identity.ParsedContactQr.V1(payload))
        }
        override suspend fun addQr(raw: String, localName: String): ContactExchangeResult {
            saves++
            return ContactExchangeResult.Saved(Contact("uuid", payload.id, payload.publicKeyBase64Url, localName, 1))
        }
        override suspend fun addVerifiedBundle(
            parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr,
            localName: String,
        ): ContactExchangeResult = addQr("bundle", localName)
        override suspend fun rename(id: String, localName: String) = Unit
        override suspend fun addInboundPinned(
            publicIdentityKey: String,
            displayName: String,
            handle: String?,
        ): Contact = Contact("uuid", "ac1:test", publicIdentityKey, displayName, 1)
    }

    private class FakeMessaging : ChatMessagingController {
        override val network = MutableStateFlow(NetworkAvailability())
        override fun observeSession(contactId: String) = flowOf(SecureSessionView())
        override suspend fun ensureProvisioned(contact: Contact) = Unit
        override suspend fun getMyContactQrV2(): String? = null
        override suspend fun importPeerContactV2(payload: com.codeyantra.armorchat.domain.identity.ContactQrV2) = RouteImportResult.Rejected
        override suspend fun importPeerRoute(raw: String, contactId: String?) = RouteImportResult.Rejected
        override fun localRouteQr(contactId: String) = null
        override suspend fun sendText(conversationId: String, text: String) =
            com.codeyantra.armorchat.domain.repository.SendTextResult.TransportUnavailable
        override suspend fun syncContact(contactId: String) = Unit
        override suspend fun markOpened(conversationId: String, messageId: String) = Unit
        override suspend fun refreshPolicy() = Unit
    }

    @Test fun duplicateFrameAndDoubleSaveAreCoalesced() = runTest(dispatcher) {
        val repository = Repository(); val vm = ContactsViewModel(repository, Conversations(), FakeMessaging())
        vm.scan("public QR"); vm.scan("duplicate frame")
        assertTrue(vm.state.value.busy)
        advanceUntilIdle()
        assertEquals(1, repository.inspections)
        assertTrue(vm.state.value.result is ContactExchangeResult.Ready)
        vm.save("Amit"); vm.save("Amit")
        advanceUntilIdle()
        assertEquals(1, repository.saves)
        assertTrue(vm.state.value.result is ContactExchangeResult.Saved)
        assertFalse(vm.state.value.busy)
    }

    @Test fun resetDropsPendingPayloadAndPreventsSave() = runTest(dispatcher) {
        val repository = Repository(); val vm = ContactsViewModel(repository, Conversations(), FakeMessaging())
        vm.scan("public QR"); advanceUntilIdle()
        vm.reset(); vm.save("Name"); advanceUntilIdle()
        assertEquals(0, repository.saves)
        assertEquals(ExchangeState(), vm.state.value)
    }

    @Test fun storageFailureHasRecoverableNonSensitiveError() = runTest(dispatcher) {
        val repository = Repository().apply { fail = true }; val vm = ContactsViewModel(repository, Conversations(), FakeMessaging())
        vm.scan("public QR"); advanceUntilIdle()
        assertFalse(vm.state.value.busy)
        assertNotNull(vm.state.value.error)
        assertFalse(vm.state.value.error!!.contains("Sensitive"))
        repository.fail = false
        vm.scan("public QR"); advanceUntilIdle()
        assertTrue(vm.state.value.result is ContactExchangeResult.Ready)
        assertNull(vm.state.value.error)
    }
}
