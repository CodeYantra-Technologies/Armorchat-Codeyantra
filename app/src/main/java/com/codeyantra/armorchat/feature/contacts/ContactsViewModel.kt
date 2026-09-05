package com.codeyantra.armorchat.feature.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.model.ContactExchangeResult
import com.codeyantra.armorchat.domain.repository.ContactRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ExchangeState(
    val result: ContactExchangeResult? = null,
    val busy: Boolean = false,
    val error: String? = null,
)

@HiltViewModel
class ContactsViewModel @Inject constructor(private val repository: ContactRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(ExchangeState())
    val state = mutableState.asStateFlow()
    val contacts = repository.observeContacts().catch {
        mutableState.value = state.value.copy(error = "Could not load local contacts. Please try again.")
        emit(emptyList())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private var pendingQr: String? = null

    fun reset() { if (!state.value.busy) { pendingQr = null; mutableState.value = ExchangeState() } }
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
    private fun execute(block: suspend () -> ContactExchangeResult) {
        mutableState.value = state.value.copy(busy = true, error = null)
        viewModelScope.launch {
            try { mutableState.value = ExchangeState(result = block()) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutableState.value = state.value.copy(busy = false,
                error = "Could not access local contacts. Please try again.") }
        }
    }
}
