package com.codeyantra.armorchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.core.preferences.SettingsDataStore
import com.codeyantra.armorchat.core.security.AppLockManager
import com.codeyantra.armorchat.domain.model.AppLockType
import com.codeyantra.armorchat.domain.model.AutoLockTimeout
import com.codeyantra.armorchat.domain.model.NotificationPrivacy
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class PrivacySecurityViewModel @Inject constructor(
    private val appLockManager: AppLockManager,
    private val settingsDataStore: SettingsDataStore,
) : ViewModel() {

    val appLockEnabled: StateFlow<Boolean> = appLockManager.appLockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val appLockType: StateFlow<AppLockType> = appLockManager.appLockType
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppLockType.PIN)

    val biometricEnabled: StateFlow<Boolean> = appLockManager.biometricEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val autoLockTimeout: StateFlow<AutoLockTimeout> = appLockManager.autoLockTimeout
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AutoLockTimeout.IMMEDIATELY)

    val obscureRecents: StateFlow<Boolean> = appLockManager.obscureRecents
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val notificationPrivacy: StateFlow<NotificationPrivacy> = appLockManager.notificationPrivacy
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotificationPrivacy.SHOW_ALL)

    fun lockNow() {
        appLockManager.lockNow()
    }

    suspend fun verifyCandidate(candidate: String): Boolean {
        return appLockManager.verifyCandidate(candidate)
    }

    fun setupAppLock(secret: String, type: AppLockType, biometric: Boolean, timeout: AutoLockTimeout) {
        viewModelScope.launch {
            appLockManager.setupAppLock(secret, type, biometric, timeout)
        }
    }

    fun changePinOrPassword(newSecret: String, type: AppLockType) {
        viewModelScope.launch {
            appLockManager.changePinOrPassword(newSecret, type)
        }
    }

    fun disableAppLock() {
        viewModelScope.launch {
            appLockManager.disableAppLock()
        }
    }

    fun setBiometricEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setBiometricEnabled(enabled)
        }
    }

    fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        viewModelScope.launch {
            settingsDataStore.setAutoLockTimeout(timeout)
        }
    }

    fun setObscureRecents(enabled: Boolean) {
        viewModelScope.launch {
            settingsDataStore.setObscureRecents(enabled)
        }
    }

    fun setNotificationPrivacy(privacy: NotificationPrivacy) {
        viewModelScope.launch {
            settingsDataStore.setNotificationPrivacy(privacy)
        }
    }
}
