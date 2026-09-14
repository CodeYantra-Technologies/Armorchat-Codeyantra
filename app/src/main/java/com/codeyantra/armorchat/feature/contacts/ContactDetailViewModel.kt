package com.codeyantra.armorchat.feature.contacts

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import com.codeyantra.armorchat.messaging.ChatMessagingController
import com.codeyantra.armorchat.messaging.SecureSessionView
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class ContactDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val contactRepository: ContactRepository,
    private val conversationRepository: ConversationRepository,
    private val messaging: ChatMessagingController,
) : ViewModel() {
    private val contactId: String = checkNotNull(savedStateHandle["contactId"])

    val contact: StateFlow<Contact?> = contactRepository.observeContact(contactId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val session: StateFlow<SecureSessionView> = contactRepository.observeContact(contactId)
        .flatMapLatest { contact ->
            if (contact == null) flowOf(SecureSessionView())
            else messaging.observeSession(contact.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), SecureSessionView())

    fun openConversation(onReady: (String) -> Unit) {
        viewModelScope.launch {
            val current = contact.value ?: return@launch
            val conversation = conversationRepository.getOrCreateForContact(current)
            onReady(conversation.id)
        }
    }

    fun renameAlias(newName: String) {
        viewModelScope.launch {
            contactRepository.rename(contactId, newName)
        }
    }

    fun removeContact(onRemoved: () -> Unit) {
        viewModelScope.launch {
            contactRepository.deleteContact(contactId)
            onRemoved()
        }
    }
}
