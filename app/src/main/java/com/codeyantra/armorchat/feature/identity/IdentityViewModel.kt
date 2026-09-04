package com.codeyantra.armorchat.feature.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.identity.IdentityQrPayload
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.usecase.CreateIdentityUseCase
import com.codeyantra.armorchat.domain.usecase.ObserveIdentityUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class IdentityViewModel @Inject constructor(
    observeIdentity: ObserveIdentityUseCase,
    private val createIdentity: CreateIdentityUseCase,
) : ViewModel() {

    val identity: StateFlow<UserIdentity?> = observeIdentity()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val hasIdentity: StateFlow<Boolean> = identity
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _createdIdentity = MutableStateFlow<UserIdentity?>(null)
    val createdIdentity: StateFlow<UserIdentity?> = _createdIdentity.asStateFlow()

    fun create(onDone: (UserIdentity) -> Unit) {
        viewModelScope.launch {
            _busy.value = true
            _error.value = null
            val result = createIdentity()
            _busy.value = false
            result.onSuccess { identity ->
                _createdIdentity.value = identity
                onDone(identity)
            }.onFailure { err ->
                _error.value = err.message ?: "Unable to create identity."
            }
        }
    }

    fun qrPayloadFor(identity: UserIdentity): String =
        IdentityQrPayload.serialize(
            IdentityQrPayload(
                id = identity.armorChatId,
                publicKeyBase64Url = identity.publicIdentityKey,
                version = identity.version,
            ),
        )

    fun clearError() {
        _error.value = null
    }
}
