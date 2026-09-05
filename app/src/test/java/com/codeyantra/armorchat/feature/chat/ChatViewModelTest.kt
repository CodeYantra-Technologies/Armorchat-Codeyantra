package com.codeyantra.armorchat.feature.chat

import androidx.lifecycle.SavedStateHandle
import com.codeyantra.armorchat.core.network.InboundEnvelope
import com.codeyantra.armorchat.core.network.MessageTransport
import com.codeyantra.armorchat.core.network.OutboundEnvelope
import com.codeyantra.armorchat.core.network.TransportResult
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.model.VerificationMethod
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.MessageRepository
import com.codeyantra.armorchat.domain.repository.SendTextResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
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
        override suspend fun inspectQr(raw: String) = ContactExchangeResult.Invalid
        override suspend fun addQr(raw: String, localName: String) = ContactExchangeResult.Invalid
        override suspend fun rename(id: String, localName: String) = Unit
    }

    private class Conversations(private val conversation: Conversation) : ConversationRepository {
        override fun observeConversations() = MutableStateFlow(listOf(conversation))
        override fun observeConversation(id: String) = MutableStateFlow(conversation.takeIf { it.id == id })
        override suspend fun getConversation(id: String) = conversation.takeIf { it.id == id }
        override suspend fun getOrCreateForContact(contact: Contact) = conversation
    }

    private class Messages : MessageRepository {
        val messages = MutableStateFlow<List<Message>>(emptyList())
        var sendCalls = 0
        override fun observeMessages(conversationId: String) = messages
        override suspend fun sendText(conversationId: String, text: String): SendTextResult {
            sendCalls++
            return SendTextResult.TransportUnavailable
        }
    }

    private class Transport(available: Boolean = false) : MessageTransport {
        override val isAvailable = flowOf(available)
        override suspend fun send(envelope: OutboundEnvelope) = TransportResult.Unavailable
        override fun incoming(): Flow<InboundEnvelope> = emptyFlow()
    }

    private fun vm(messages: Messages = Messages(), available: Boolean = false) = ChatViewModel(
        savedStateHandle = SavedStateHandle(mapOf("conversationId" to conversation.id)),
        conversationRepository = Conversations(conversation),
        messageRepository = messages,
        contactRepository = Contacts(contact),
        transport = Transport(available),
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
    fun composerDraftUpdates() = runTest(dispatcher) {
        val viewModel = vm()
        advanceUntilIdle()
        viewModel.onDraftChange("hello")
        advanceUntilIdle()
        assertEquals("hello", viewModel.state.value.draft)
        assertFalse(viewModel.state.value.canSend)
    }

    @Test
    fun sendCannotFakeSuccessfulMessage() = runTest(dispatcher) {
        val messages = Messages()
        val viewModel = vm(messages = messages, available = false)
        advanceUntilIdle()
        viewModel.onDraftChange("secret")
        viewModel.send()
        advanceUntilIdle()
        assertEquals(1, messages.sendCalls)
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
