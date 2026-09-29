package com.codeyantra.armorchat.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.model.AttachmentMetadata
import com.codeyantra.armorchat.domain.model.MessageStatus
import com.codeyantra.armorchat.domain.model.MessageType
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.domain.repository.MessageRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ChatListItem(
    val conversationId: String,
    val contactId: String,
    val displayName: String,
    val handleDisplay: String,
    val lastMessageText: String?,
    val isLastMessageOutgoing: Boolean,
    val lastMessageStatus: MessageStatus?,
    val lastMessageEpochMs: Long,
    val unreadCount: Int,
    val updatedAtEpochMs: Long,
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    conversationRepository: ConversationRepository,
    contactRepository: ContactRepository,
    messageRepository: MessageRepository,
) : ViewModel() {

    val searchQuery = MutableStateFlow("")

    private val allListItems = combine(
        conversationRepository.observeConversations(),
        contactRepository.observeContacts(),
        messageRepository.observeAllMessages(),
    ) { conversations, contacts, allMessages ->
        val contactsById = contacts.associateBy { it.id }
        val messagesByConv = allMessages.groupBy { it.conversationId }

        conversations.mapNotNull { conversation ->
            val contact = contactsById[conversation.contactId] ?: return@mapNotNull null
            val convMessages = messagesByConv[conversation.id].orEmpty()
            val lastMsg = convMessages.lastOrNull()
            val unread = convMessages.count { !it.isOutgoing && it.status != MessageStatus.READ }

            ChatListItem(
                conversationId = conversation.id,
                contactId = contact.id,
                displayName = contact.displayName.ifBlank { conversation.title ?: "ArmorChat contact" },
                handleDisplay = contact.handleDisplay ?: "Secure chat",
                lastMessageText = lastMsg?.let { previewFor(it) },
                isLastMessageOutgoing = lastMsg?.isOutgoing ?: false,
                lastMessageStatus = lastMsg?.status,
                lastMessageEpochMs = lastMsg?.createdAtEpochMs ?: conversation.updatedAtEpochMs,
                unreadCount = unread,
                updatedAtEpochMs = conversation.updatedAtEpochMs,
            )
        }.sortedByDescending { it.lastMessageEpochMs }
    }

    val items: StateFlow<List<ChatListItem>> = combine(
        allListItems,
        searchQuery,
    ) { list, query ->
        val q = query.trim().lowercase()
        if (q.isEmpty()) list
        else list.filter { item ->
            item.displayName.lowercase().contains(q) ||
            item.handleDisplay.lowercase().contains(q) ||
            item.lastMessageText?.lowercase()?.contains(q) == true
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
    }

    private fun previewFor(message: com.codeyantra.armorchat.domain.model.Message): String? {
        val meta = AttachmentMetadata.parseOrNull(message.localPlaintext)
        return when {
            meta != null || message.type != MessageType.TEXT -> when {
                message.type == MessageType.IMAGE || meta?.mediaClass == "image" -> "Photo"
                message.type == MessageType.VOICE || meta?.mediaClass == "voice" -> "Voice message"
                else -> meta?.originalName?.ifBlank { "Document" } ?: "Document"
            }
            else -> message.localPlaintext?.takeIf { it.isNotBlank() }
        }
    }
}
