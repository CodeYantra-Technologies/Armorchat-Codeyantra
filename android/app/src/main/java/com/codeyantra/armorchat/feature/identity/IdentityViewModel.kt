package com.codeyantra.armorchat.feature.identity

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.core.preferences.SettingsDataStore
import com.codeyantra.armorchat.core.security.IdentityStorageException
import com.codeyantra.armorchat.domain.identity.IdentityQrPayload
import com.codeyantra.armorchat.domain.model.UserIdentity
import com.codeyantra.armorchat.domain.usecase.CreateIdentityBackupUseCase
import com.codeyantra.armorchat.domain.usecase.CreateIdentityUseCase
import com.codeyantra.armorchat.domain.usecase.ObserveIdentityUseCase
import com.codeyantra.armorchat.domain.usecase.RestoreIdentityUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed interface IdentityState {
    data object Checking : IdentityState
    data object NoIdentity : IdentityState
    data class Ready(val identity: UserIdentity) : IdentityState
    data class Error(val message: String) : IdentityState
}

sealed interface IdentityCreationState {
    data object Idle : IdentityCreationState
    data object Creating : IdentityCreationState
    data class Success(val identity: UserIdentity) : IdentityCreationState
    data class RecoverableError(val message: String) : IdentityCreationState
    data class FatalStorageError(val message: String) : IdentityCreationState
}

sealed interface IdentityBackupState {
    data object Idle : IdentityBackupState
    data object Creating : IdentityBackupState
    data class Ready(val bytes: ByteArray, val fileName: String) : IdentityBackupState {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Ready) return false
            return bytes.contentEquals(other.bytes) && fileName == other.fileName
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + fileName.hashCode()
    }
    data class Saved(val message: String) : IdentityBackupState
    data class Error(val message: String) : IdentityBackupState
}

sealed interface IdentityRestoreState {
    data object Idle : IdentityRestoreState
    data object Restoring : IdentityRestoreState
    data class Success(val identity: UserIdentity) : IdentityRestoreState
    data class Error(val message: String) : IdentityRestoreState
}

@HiltViewModel
class IdentityViewModel @Inject constructor(
    observeIdentity: ObserveIdentityUseCase,
    private val createIdentity: CreateIdentityUseCase,
    private val createIdentityBackup: CreateIdentityBackupUseCase,
    private val restoreIdentity: RestoreIdentityUseCase,
    private val identityRepository: com.codeyantra.armorchat.domain.repository.IdentityRepository,
    private val messaging: com.codeyantra.armorchat.messaging.ChatMessagingController,
    private val settingsDataStore: SettingsDataStore? = null,
) : ViewModel() {

    val identity: StateFlow<UserIdentity?> = observeIdentity()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val aboutStatus: StateFlow<String> = (settingsDataStore?.aboutStatus ?: kotlinx.coroutines.flow.flowOf("Securing chats with ArmorChat"))
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "Securing chats with ArmorChat")

    val hasIdentity: StateFlow<Boolean> = identity
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val identityState: StateFlow<IdentityState> = identity
        .map { user ->
            if (user != null) IdentityState.Ready(user) else IdentityState.NoIdentity
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, IdentityState.Checking)

    private val _creationState = MutableStateFlow<IdentityCreationState>(IdentityCreationState.Idle)
    val creationState: StateFlow<IdentityCreationState> = _creationState.asStateFlow()

    private val _backupState = MutableStateFlow<IdentityBackupState>(IdentityBackupState.Idle)
    val backupState: StateFlow<IdentityBackupState> = _backupState.asStateFlow()

    private val _restoreState = MutableStateFlow<IdentityRestoreState>(IdentityRestoreState.Idle)
    val restoreState: StateFlow<IdentityRestoreState> = _restoreState.asStateFlow()

    fun create(
        displayName: String = "",
        handle: String = "",
        onDone: (UserIdentity) -> Unit,
    ) {
        if (!canStartCreation(_creationState.value)) return
        viewModelScope.launch {
            if (!canStartCreation(_creationState.value)) return@launch
            _creationState.value = IdentityCreationState.Creating
            IdentityDiagnostics.stage("ui-create", "started")
            val result = createIdentity(displayName, handle)
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

    suspend fun getMyContactQrV2(): String? = messaging.getMyContactQrV2()

    suspend fun getMyContactQrV3(refresh: Boolean = false): String? {
        com.codeyantra.armorchat.messaging.SafeLog.event("stage=QR_V3_START result=PASS")
        return try {
            val result = messaging.getMyContactQrV3(refresh)
            if (result != null) {
                com.codeyantra.armorchat.messaging.SafeLog.event("stage=QR_RETURNED_TO_UI result=PASS content_length=${result.length}")
            } else {
                com.codeyantra.armorchat.messaging.SafeLog.event("stage=QR_RETURNED_TO_UI result=FAIL")
            }
            result
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (err: Exception) {
            com.codeyantra.armorchat.messaging.SafeLog.event("stage=QR_RETURNED_TO_UI result=FAIL exception=${err::class.java.name}")
            throw err
        }
    }

    fun saveProfile(displayName: String, handle: String, about: String, onDone: () -> Unit = {}) {
        viewModelScope.launch {
            runCatching {
                identityRepository.updateProfile(displayName, handle)
                settingsDataStore?.setAboutStatus(about)
            }
            onDone()
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

    fun createBackup(password: String, confirmPassword: String) {
        if (_backupState.value is IdentityBackupState.Creating) return
        val validationError = validateBackupPassword(password, confirmPassword)
        if (validationError != null) {
            _backupState.value = IdentityBackupState.Error(validationError)
            return
        }
        viewModelScope.launch {
            _backupState.value = IdentityBackupState.Creating
            createIdentityBackup(password.toCharArray())
                .onSuccess { bytes ->
                    _backupState.value = IdentityBackupState.Ready(
                        bytes = bytes,
                        fileName = backupFileName(),
                    )
                }
                .onFailure { err ->
                    _backupState.value = IdentityBackupState.Error(
                        err.message ?: "Unable to create identity backup.",
                    )
                }
        }
    }

    fun markBackupSaved() {
        val state = _backupState.value
        if (state is IdentityBackupState.Ready) {
            state.bytes.fill(0)
        }
        _backupState.value = IdentityBackupState.Saved("Identity backup created.")
    }

    fun clearBackupState() {
        val state = _backupState.value
        if (state is IdentityBackupState.Ready) {
            state.bytes.fill(0)
        }
        _backupState.value = IdentityBackupState.Idle
    }

    fun restoreFromBackup(backupBytes: ByteArray, password: String, onDone: (UserIdentity) -> Unit) {
        if (_restoreState.value is IdentityRestoreState.Restoring) return
        if (password.length < 12) {
            _restoreState.value = IdentityRestoreState.Error("Enter the backup password.")
            return
        }
        viewModelScope.launch {
            _restoreState.value = IdentityRestoreState.Restoring
            restoreIdentity(backupBytes, password.toCharArray())
                .onSuccess { identity ->
                    _restoreState.value = IdentityRestoreState.Success(identity)
                    onDone(identity)
                }
                .onFailure { err ->
                    _restoreState.value = IdentityRestoreState.Error(
                        err.message ?: "Unable to restore identity backup.",
                    )
                }
        }
    }

    fun clearRestoreState() {
        if (_restoreState.value !is IdentityRestoreState.Restoring) {
            _restoreState.value = IdentityRestoreState.Idle
        }
    }

    private fun canStartCreation(state: IdentityCreationState): Boolean =
        state is IdentityCreationState.Idle || state is IdentityCreationState.RecoverableError

    private fun validateBackupPassword(password: String, confirmPassword: String): String? {
        if (password.length < 12) return "Use at least 12 characters for the backup password."
        if (password != confirmPassword) return "Backup passwords do not match."
        return null
    }

    private fun backupFileName(): String =
        "armorchat-identity-${System.currentTimeMillis()}.armorbackup"
}

private object IdentityDiagnostics {
    fun stage(stage: String, details: String) {
        println("ArmorChatIdentity stage=$stage $details")
    }
}
