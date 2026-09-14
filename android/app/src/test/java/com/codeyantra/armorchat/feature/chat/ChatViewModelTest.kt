package com.codeyantra.armorchat.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.codeyantra.armorchat.core.network.NetworkAvailability
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.model.VerificationMethod
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.MessageRepository
import com.codeyantra.armorchat.domain.repository.SendTextResult
import com.codeyantra.armorchat.messaging.ChatMessagingController
import com.codeyantra.armorchat.messaging.ReplyMetadata
import com.codeyantra.armorchat.messaging.RouteImportResult
import com.codeyantra.armorchat.messaging.SecureSessionState
import com.codeyantra.armorchat.messaging.SecureSessionView
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {
    private val dispatcher = StandardTestDispatcher()

    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val conversation = Conversation("conv-1", "contact-1", "Amit", 10)
    private val contact = Contact(
        id = "contact-1",
        armorChatId = "ac1:0123456789ABCDEFGHJKMNPQRSTVWXYZ",
        publicIdentityKey = "pk",
        displayName = "Amit",
        createdAtEpochMs = 1,
        verificationMethod = VerificationMethod.QR_SCAN,
    )

    private class Contacts(private val contact: Contact) : ContactRepository {
        override fun observeContacts() = MutableStateFlow(listOf(contact))
        override fun observeContact(id: String) = MutableStateFlow(contact.takeIf { it.id == id })
        override suspend fun getContact(id: String) = contact.takeIf { it.id == id }
        override suspend fun findByPublicKey(publicIdentityKey: String) =
            contact.takeIf { it.publicIdentityKey == publicIdentityKey }
        override suspend fun inspectQr(raw: String) = ContactExchangeResult.Invalid
        override suspend fun addQr(raw: String, localName: String) = ContactExchangeResult.Invalid
        override suspend fun addVerifiedBundle(
            parsed: com.codeyantra.armorchat.domain.identity.ParsedContactQr,
            localName: String,
        ) = ContactExchangeResult.Invalid
        override suspend fun rename(id: String, localName: String) = Unit
        override suspend fun addInboundPinned(
            publicIdentityKey: String,
            displayName: String,
            handle: String?,
        ) = contact
    }

    private class Conversations(private val conversation: Conversation) : ConversationRepository {
        override fun observeConversations() = MutableStateFlow(listOf(conversation))
        override fun observeConversation(id: String) = MutableStateFlow(conversation.takeIf { id == conversation.id })
        override suspend fun getConversation(id: String) = conversation.takeIf { id == conversation.id }
        override suspend fun getOrCreateForContact(contact: Contact) = conversation
    }

    private class Messages : MessageRepository {
        val messages = MutableStateFlow<List<Message>>(emptyList())
        override fun observeMessages(conversationId: String) = messages
        override suspend fun sendText(conversationId: String, text: String): SendTextResult {
            return SendTextResult.TransportUnavailable
        }
    }

    private class Messaging(
        session: SecureSessionView = SecureSessionView(),
    ) : ChatMessagingController {
        override val network = MutableStateFlow(NetworkAvailability())
        val sessions = MutableStateFlow(session)
        var sendCalls = 0
        override fun observeSession(contactId: String): Flow<SecureSessionView> = sessions
        override suspend fun ensureProvisioned(contact: Contact) = Unit
        override suspend fun getMyContactQrV2(): String? = null
        override suspend fun importPeerContactV2(payload: com.codeyantra.armorchat.domain.identity.ContactQrV2) = RouteImportResult.Rejected
        override suspend fun importPeerRoute(raw: String, contactId: String?) = RouteImportResult.Rejected
        override fun localRouteQr(contactId: String) = session.localRouteQr
        override suspend fun sendText(conversationId: String, text: String, replyTo: ReplyMetadata?): SendTextResult {
            sendCalls++
            return SendTextResult.TransportUnavailable
        }
        override suspend fun syncContact(contactId: String) = Unit
        override suspend fun markOpened(conversationId: String, messageId: String) = Unit
        override suspend fun refreshPolicy() = Unit
        private val session get() = sessions.value
    }

    private fun vm(
        messages: Messages = Messages(),
        messaging: Messaging = Messaging(),
    ) = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversation.id)),
        conversationRepository = Conversations(conversation),
        messageRepository = messages,
        contactRepository = Contacts(contact),
        messaging = messaging,
    )

    @Test
    fun opensCorrectConversationAndContact() = runTest(dispatcher) {
        val viewModel = vm()
        advanceUntilIdle()
        assertEquals(conversation.id, viewModel.state.value.conversation?.id)
        assertEquals(contact.id, viewModel.state.value.contact?.id)
        assertEquals("Amit", viewModel.state.value.displayName)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }

    @Test
    fun composerStaysDisabledWithoutSecureSession() = runTest(dispatcher) {
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDraftChange("hello")
        advanceUntilIdle()
        assertEquals("hello", viewModel.state.value.draft)
        assertFalse(viewModel.state.value.canSend)
        assertFalse(viewModel.state.value.sessionReady)
    }

    @Test
    fun composerEnablesOnlyAfterEstablishedSession() = runTest(dispatcher) {
        val messaging = Messaging(
            SecureSessionView(state = SecureSessionState.ESTABLISHED, composerAllowed = true),
        )
        val viewModel = vm(messaging = messaging)
        advanceUntilIdle()
        viewModel.onDraftChange("hello")
        advanceUntilIdle()
        assertTrue(viewModel.state.value.sessionReady)
        assertTrue(viewModel.state.value.canSend)
    }

    @Test
    fun sendCannotFakeSuccessfulMessage() = runTest(dispatcher) {
        val messages = Messages()
        val messaging = Messaging()
        val viewModel = vm(messages = messages, messaging = messaging)
        advanceUntilIdle()
        viewModel.onDraftChange("secret")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(1, messaging.sendCalls)
        assertTrue(messages.messages.value.isEmpty())
        assertFalse(viewModel.state.value.canSend)
        assertTrue(viewModel.state.value.notice!!.contains("not sent"))
    }

    @Test
    fun chatScreenStateStaysOnDomainModels() = runTest(dispatcher) {
        val viewModel = vm()
        advanceUntilIdle()
        assertEquals(conversation, viewModel.state.value.conversation)
        assertEquals(contact, viewModel.state.value.contact)
        assertNull(viewModel.state.value.notice)
        assertTrue(viewModel.state.value.messages.isEmpty())
    }
}
