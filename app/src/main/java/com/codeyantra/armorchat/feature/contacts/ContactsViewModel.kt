package com.codeyantra.armorchat.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.model.Contact
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import com.codeyantra.armorchat.domain.repository.ContactRepository
import com.codeyantra.armorchat.domain.repository.ConversationRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ExchangeState(
    val result: ContactExchangeResult? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactRepository,
    private val conversationRepository: ConversationRepository,
) : ViewModel() {
    private val mutableState = MutableStateFlow(ExchangeState())
    val state = mutableState.asStateFlow()
    val contacts = repository.observeContacts().catch {
        mutableState.value = state.value.copy(error = "Could not load local contacts. Please try again.")
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var pendingQr: String? = null

    fun reset() {
        if (!state.value.busy) {
            pendingQr = null
            mutableState.value = ExchangeState()
        }
    }

    fun scan(raw: String) {
        if (state.value.busy) return
        pendingQr = raw
        execute { repository.inspectQr(raw) }
    }

    fun save(name: String) {
        val raw = pendingQr ?: return
        if (state.value.busy) return
        execute { repository.addQr(raw, name) }
    }

    fun openConversation(contact: Contact, onReady: (conversationId: String) -> Unit) {
        viewModelScope.launch {
            try {
                val conversation = conversationRepository.getOrCreateForContact(contact)
                onReady(conversation.id)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(
                    error = "Could not open conversation. Please try again.",
                )
            }
        }
    }

    private fun execute(block: suspend () -> ContactExchangeResult) {
        mutableState.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try {
                mutableState.value = ExchangeState(result = block())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                mutableState.value = state.value.copy(
                    busy = false,
                    error = "Could not access local contacts. Please try again.",
                )
            }
        }
    }
}
