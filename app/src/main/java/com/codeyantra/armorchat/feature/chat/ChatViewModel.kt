package com.codeyantra.armorchat.feature.chat

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.core.network.MessageTransport
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.Conversation
import com.codeyantra.armorchat.domain.model.Message
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.MessageRepository
import com.codeyantra.armorchat.domain.repository.SendTextResult
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ChatUiState(
    val conversation: Conversation? = null,
    val contact: Contact? = null,
    val messages: List<Message> = emptyList(),
    val draft: String = "",
    val transportAvailable: Boolean = false,
    val notice: String? = null,
    val loading: Boolean = true,
) {
    val displayName: String
        get() = contact?.displayName?.takeIf { it.isNotBlank() }
            ?: conversation?.title?.takeIf { it.isNotBlank() }
            ?: "ArmorChat contact"

    val shortArmorChatId: String?
        get() = contact?.armorChatId?.let { ArmorChatId.shortDisplay(it) }

    /** Send stays disabled until a real transport exists. */
    val canSend: Boolean
        get() = transportAvailable && draft.trim().isNotEmpty() && conversation != null
}

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ChatViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    conversationRepository: ConversationRepository,
    private val messageRepository: MessageRepository,
    contactRepository: ContactRepository,
    transport: MessageTransport,
) : ViewModel() {
    private val conversationId: String = checkNotNull(savedStateHandle["conversationId"])

    private val draft = MutableStateFlow("")
    private val notice = MutableStateFlow<String?>(null)

    private val conversationFlow = conversationRepository.observeConversation(conversationId)
    private val contactFlow = conversationFlow.flatMapLatest { conversation ->
        val contactId = conversation?.contactId
        if (contactId == null) flowOf(null) else contactRepository.observeContact(contactId)
    }

    private data class ChatCore(
        val conversation: Conversation?,
        val contact: Contact?,
        val messages: List<Message>,
    )

    private val core = combine(
        conversationFlow,
        contactFlow,
        messageRepository.observeMessages(conversationId),
    ) { conversation, contact, messages ->
        ChatCore(conversation, contact, messages)
    }

    val state: StateFlow<ChatUiState> = combine(
        core,
        draft,
        notice,
        transport.isAvailable,
    ) { chatCore, draftText, noticeText, available ->
        ChatUiState(
            conversation = chatCore.conversation,
            contact = chatCore.contact,
            messages = chatCore.messages,
            draft = draftText,
            transportAvailable = available,
            notice = noticeText,
            loading = false,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatUiState(),
    )

    fun onDraftChange(value: String) {
        if (value.length <= 8_000) draft.value = value
    }

    fun clearNotice() {
        notice.value = null
    }

    fun send() {
        val text = draft.value
        viewModelScope.launch {
            when (messageRepository.sendText(conversationId, text)) {
                SendTextResult.TransportUnavailable -> {
                    notice.value = "Messaging is not available yet. Your message was not sent."
                }
                SendTextResult.Empty -> Unit
                SendTextResult.ConversationMissing -> {
                    notice.value = "Conversation is missing on this device."
                }
            }
        }
    }
}
