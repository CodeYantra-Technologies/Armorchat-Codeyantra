package com.codeyantra.armorchat.feature.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.core.security.IdentityStorageException
import com.codeyantra.armorchat.domain.identity.IdentityQrPayload
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.usecase.CreateIdentityUseCase
import com.codeyantra.armorchat.domain.usecase.ObserveIdentityUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface IdentityCreationState {
    data object Idle : IdentityCreationState
    data object Creating : IdentityCreationState
    data class Success(val identity: UserIdentity) : IdentityCreationState
    data class RecoverableError(val message: String) : IdentityCreationState
    data class FatalStorageError(val message: String) : IdentityCreationState
}

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

    private val _creationState = MutableStateFlow<IdentityCreationState>(IdentityCreationState.Idle)
    val creationState: StateFlow<IdentityCreationState> = _creationState.asStateFlow()

    fun create(onDone: (UserIdentity) -> Unit) {
        if (!canStartCreation(_creationState.value)) return
        viewModelScope.launch {
            if (!canStartCreation(_creationState.value)) return@launch
            _creationState.value = IdentityCreationState.Creating
            IdentityDiagnostics.stage("ui-create", "started")
            val result = createIdentity()
            result.onSuccess { identity ->
                _creationState.value = IdentityCreationState.Success(identity)
                IdentityDiagnostics.stage("ui-create", "success, existed-or-created=true")
                onDone(identity)
            }.onFailure { err ->
                val message = err.message ?: "Unable to create identity."
                _creationState.value = if (err is IdentityStorageException) {
                    IdentityCreationState.FatalStorageError(message)
                } else {
                    IdentityCreationState.RecoverableError(message)
                }
                IdentityDiagnostics.stage("ui-create", "success=false, category=${err::class.simpleName}")
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
        if (_creationState.value !is IdentityCreationState.Creating) {
            _creationState.value = IdentityCreationState.Idle
        }
    }

    private fun canStartCreation(state: IdentityCreationState): Boolean =
        state is IdentityCreationState.Idle || state is IdentityCreationState.RecoverableError
}

private object IdentityDiagnostics {
    fun stage(stage: String, details: String) {
        println("ArmorChatIdentity stage=$stage $details")
    }
}
