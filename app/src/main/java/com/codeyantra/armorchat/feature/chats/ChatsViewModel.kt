package com.codeyantra.armorchat.feature.chats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.identity.ArmorChatId
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ChatListItem(
    val conversationId: String,
    val contactId: String,
    val displayName: String,
    val shortArmorChatId: String,
    val updatedAtEpochMs: Long,
)

@HiltViewModel
class ChatsViewModel @Inject constructor(
    conversationRepository: ConversationRepository,
    contactRepository: ContactRepository,
) : ViewModel() {
    val items: StateFlow<List<ChatListItem>> = combine(
        conversationRepository.observeConversations(),
        contactRepository.observeContacts(),
    ) { conversations, contacts ->
        val byId = contacts.associateBy { it.id }
        conversations.mapNotNull { conversation ->
            val contact = byId[conversation.contactId] ?: return@mapNotNull null
            ChatListItem(
                conversationId = conversation.id,
                contactId = contact.id,
                displayName = contact.displayName.ifBlank { conversation.title ?: "ArmorChat contact" },
                shortArmorChatId = ArmorChatId.shortDisplay(contact.armorChatId),
                updatedAtEpochMs = conversation.updatedAtEpochMs,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}
