package com.codeyantra.armorchat.core.security

import android.content.Context
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.codeyantra.armorchat.core.preferences.SettingsDataStore
import com.codeyantra.armorchat.domain.model.AppLockType
import com.codeyantra.armorchat.domain.model.AutoLockTimeout
import com.codeyantra.armorchat.domain.model.NotificationPrivacy
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Singleton
class AppLockManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsDataStore: SettingsDataStore,
    private val appLockCrypto: AppLockCrypto,
) : DefaultLifecycleObserver {

    private val scope = CoroutineScope(Dispatchers.Main + Job())

    private val _isLocked = MutableStateFlow(false)
    val isLocked: StateFlow<Boolean> = _isLocked.asStateFlow()

    private val _remainingCooldownSeconds = MutableStateFlow(0)
    val remainingCooldownSeconds: StateFlow<Int> = _remainingCooldownSeconds.asStateFlow()

    private var lastBackgroundTimestampMs: Long = 0L
    private var failureCount = 0
    private var cooldownEndTimestampMs: Long = 0L
    private var cooldownTimerJob: Job? = null

    val appLockEnabled: Flow<Boolean> = settingsDataStore.appLockEnabled
    val appLockType: Flow<AppLockType> = settingsDataStore.appLockType
    val biometricEnabled: Flow<Boolean> = settingsDataStore.biometricEnabled
    val autoLockTimeout: Flow<AutoLockTimeout> = settingsDataStore.autoLockTimeout
    val obscureRecents: Flow<Boolean> = settingsDataStore.obscureRecents
    val notificationPrivacy: Flow<NotificationPrivacy> = settingsDataStore.notificationPrivacy

    init {
        scope.launch(Dispatchers.IO) {
            val enabled = settingsDataStore.appLockEnabled.first()
            if (enabled) {
                _isLocked.value = true
            }

            // Restore persistent failure count and cooldown timestamp
            failureCount = settingsDataStore.appLockFailureCount.first()
            cooldownEndTimestampMs = settingsDataStore.appLockCooldownEndMs.first()

            val now = System.currentTimeMillis()
            if (now < cooldownEndTimestampMs) {
                val remainingSec = ((cooldownEndTimestampMs - now) / 1000L).toInt().coerceAtLeast(1)
                startCooldownTimer(remainingSec)
            }
        }
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    override fun onStop(owner: LifecycleOwner) {
        lastBackgroundTimestampMs = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        scope.launch {
            val enabled = settingsDataStore.appLockEnabled.first()
            if (!enabled) {
                _isLocked.value = false
                return@launch
            }
            if (_isLocked.value) return@launch

            val timeout = settingsDataStore.autoLockTimeout.first()
            val now = System.currentTimeMillis()
            val elapsed = now - lastBackgroundTimestampMs

            if (lastBackgroundTimestampMs > 0L && elapsed >= timeout.timeoutMs) {
                _isLocked.value = true
            }
        }
    }

    fun lockNow() {
        scope.launch {
            val enabled = settingsDataStore.appLockEnabled.first()
            if (enabled) {
                _isLocked.value = true
            }
        }
    }

    suspend fun verifyCandidate(candidate: String): Boolean {
        if (isCooldownActive()) return false

        val salt = settingsDataStore.appLockSalt.first() ?: return false
        val verifier = settingsDataStore.appLockVerifier.first() ?: return false

        val candidateChars = candidate.toCharArray()
        val result = try {
            appLockCrypto.verifyPinOrPassword(candidateChars, salt, verifier)
        } finally {
            candidateChars.fill('\u0000')
        }

        return when (result) {
            is VerificationResult.Valid -> {
                failureCount = 0
                cooldownEndTimestampMs = 0L
                settingsDataStore.resetThrottlingState()

                if (result.needsUpgrade) {
                    // Seamlessly upgrade legacy v1 verifier envelope to v2
                    val upgradeChars = candidate.toCharArray()
                    try {
                        val newVerifier = appLockCrypto.createVerifierV2(upgradeChars, salt)
                        settingsDataStore.setVerifier(newVerifier)
                    } finally {
                        upgradeChars.fill('\u0000')
                    }
                }

                _isLocked.value = false
                true
            }
            VerificationResult.Invalid -> {
                failureCount++
                triggerCooldownIfNeeded()
                false
            }
        }
    }

    fun unlockWithBiometric(): Boolean {
        if (isCooldownActive()) return false
        failureCount = 0
        cooldownEndTimestampMs = 0L
        scope.launch { settingsDataStore.resetThrottlingState() }
        _isLocked.value = false
        return true
    }

    suspend fun setupAppLock(
        pinOrPassword: String,
        type: AppLockType,
        biometric: Boolean,
        timeout: AutoLockTimeout,
    ) {
        val salt = appLockCrypto.generateSalt()
        val secretChars = pinOrPassword.toCharArray()
        val verifier = try {
            appLockCrypto.createVerifier(secretChars, salt)
        } finally {
            secretChars.fill('\u0000')
        }

        settingsDataStore.setupAppLock(type, salt, verifier, biometric, timeout)
        _isLocked.value = false
        failureCount = 0
        cooldownEndTimestampMs = 0L
    }

    suspend fun changePinOrPassword(newPinOrPassword: String, newType: AppLockType) {
        val salt = appLockCrypto.generateSalt()
        val secretChars = newPinOrPassword.toCharArray()
        val verifier = try {
            appLockCrypto.createVerifier(secretChars, salt)
        } finally {
            secretChars.fill('\u0000')
        }

        val currentBio = settingsDataStore.biometricEnabled.first()
        val currentTimeout = settingsDataStore.autoLockTimeout.first()
        settingsDataStore.setupAppLock(newType, salt, verifier, currentBio, currentTimeout)
    }

    suspend fun disableAppLock() {
        settingsDataStore.disableAppLock()
        _isLocked.value = false
        failureCount = 0
        cooldownEndTimestampMs = 0L
    }

    private fun isCooldownActive(): Boolean {
        val now = System.currentTimeMillis()
        return now < cooldownEndTimestampMs
    }

    private fun triggerCooldownIfNeeded() {
        val cooldownSeconds = when {
            failureCount >= 15 -> 300
            failureCount >= 10 -> 60
            failureCount >= 5 -> 30
            else -> 0
        }
        if (cooldownSeconds > 0) {
            cooldownEndTimestampMs = System.currentTimeMillis() + (cooldownSeconds * 1000L)
            startCooldownTimer(cooldownSeconds)
            scope.launch {
                settingsDataStore.updateThrottlingState(failureCount, cooldownEndTimestampMs)
            }
        } else {
            scope.launch {
                settingsDataStore.updateThrottlingState(failureCount, 0L)
            }
        }
    }

    private fun startCooldownTimer(seconds: Int) {
        cooldownTimerJob?.cancel()
        cooldownTimerJob = scope.launch {
            var remaining = seconds
            while (remaining > 0) {
                _remainingCooldownSeconds.value = remaining
                delay(1000L)
                remaining--
            }
            _remainingCooldownSeconds.value = 0
        }
    }
}
