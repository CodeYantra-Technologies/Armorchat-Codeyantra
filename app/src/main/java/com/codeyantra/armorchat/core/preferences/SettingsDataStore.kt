package com.codeyantra.armorchat.core.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.codeyantra.armorchat.domain.model.AppLockType
import com.codeyantra.armorchat.domain.model.AutoLockTimeout
import com.codeyantra.armorchat.domain.model.NotificationPrivacy
import com.codeyantra.armorchat.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "armorchat_settings",
)

@Singleton
class SettingsDataStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val dataStore = context.settingsDataStore

    val themeMode: Flow<ThemeMode> = dataStore.data.map { prefs ->
        when (prefs[THEME_KEY]) {
            ThemeMode.DARK.name -> ThemeMode.DARK
            ThemeMode.LIGHT.name -> ThemeMode.LIGHT
            else -> ThemeMode.SYSTEM
        }
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        dataStore.edit { it[THEME_KEY] = mode.name }
    }

    val developerMlsHarness: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[MLS_HARNESS_KEY] ?: false
    }

    suspend fun setDeveloperMlsHarness(enabled: Boolean) {
        dataStore.edit { it[MLS_HARNESS_KEY] = enabled }
    }

    val cachedPolicy: Flow<String?> = dataStore.data.map { prefs ->
        prefs[CACHED_POLICY_KEY]
    }

    suspend fun setCachedPolicy(raw: String) {
        dataStore.edit { it[CACHED_POLICY_KEY] = raw }
    }

    // App Lock Preferences
    val appLockEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[APP_LOCK_ENABLED_KEY] ?: false
    }

    val appLockType: Flow<AppLockType> = dataStore.data.map { prefs ->
        when (prefs[APP_LOCK_TYPE_KEY]) {
            AppLockType.PASSWORD.name -> AppLockType.PASSWORD
            else -> AppLockType.PIN
        }
    }

    val appLockSalt: Flow<String?> = dataStore.data.map { prefs ->
        prefs[APP_LOCK_SALT_KEY]
    }

    val appLockVerifier: Flow<String?> = dataStore.data.map { prefs ->
        prefs[APP_LOCK_VERIFIER_KEY]
    }

    val biometricEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[APP_LOCK_BIOMETRIC_KEY] ?: false
    }

    val autoLockTimeout: Flow<AutoLockTimeout> = dataStore.data.map { prefs ->
        when (prefs[APP_LOCK_TIMEOUT_KEY]) {
            AutoLockTimeout.AFTER_1_MIN.name -> AutoLockTimeout.AFTER_1_MIN
            AutoLockTimeout.AFTER_5_MIN.name -> AutoLockTimeout.AFTER_5_MIN
            AutoLockTimeout.AFTER_15_MIN.name -> AutoLockTimeout.AFTER_15_MIN
            else -> AutoLockTimeout.IMMEDIATELY
        }
    }

    val obscureRecents: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[APP_LOCK_OBSCURE_RECENTS_KEY] ?: false
    }

    val notificationPrivacy: Flow<NotificationPrivacy> = dataStore.data.map { prefs ->
        when (prefs[NOTIFICATION_PRIVACY_KEY]) {
            NotificationPrivacy.SENDER_ONLY.name -> NotificationPrivacy.SENDER_ONLY
            NotificationPrivacy.GENERIC.name -> NotificationPrivacy.GENERIC
            else -> NotificationPrivacy.SHOW_ALL
        }
    }

    val appLockFailureCount: Flow<Int> = dataStore.data.map { prefs ->
        prefs[APP_LOCK_FAILURE_COUNT_KEY] ?: 0
    }

    val appLockCooldownEndMs: Flow<Long> = dataStore.data.map { prefs ->
        prefs[APP_LOCK_COOLDOWN_END_KEY] ?: 0L
    }

    suspend fun setupAppLock(
        type: AppLockType,
        salt: String,
        verifier: String,
        biometric: Boolean,
        timeout: AutoLockTimeout,
    ) {
        dataStore.edit { prefs ->
            prefs[APP_LOCK_ENABLED_KEY] = true
            prefs[APP_LOCK_TYPE_KEY] = type.name
            prefs[APP_LOCK_SALT_KEY] = salt
            prefs[APP_LOCK_VERIFIER_KEY] = verifier
            prefs[APP_LOCK_BIOMETRIC_KEY] = biometric
            prefs[APP_LOCK_TIMEOUT_KEY] = timeout.name
            prefs[APP_LOCK_FAILURE_COUNT_KEY] = 0
            prefs[APP_LOCK_COOLDOWN_END_KEY] = 0L
        }
    }

    suspend fun setVerifier(verifier: String) {
        dataStore.edit { prefs ->
            prefs[APP_LOCK_VERIFIER_KEY] = verifier
        }
    }

    suspend fun disableAppLock() {
        dataStore.edit { prefs ->
            prefs[APP_LOCK_ENABLED_KEY] = false
            prefs.remove(APP_LOCK_TYPE_KEY)
            prefs.remove(APP_LOCK_SALT_KEY)
            prefs.remove(APP_LOCK_VERIFIER_KEY)
            prefs.remove(APP_LOCK_BIOMETRIC_KEY)
            prefs.remove(APP_LOCK_FAILURE_COUNT_KEY)
            prefs.remove(APP_LOCK_COOLDOWN_END_KEY)
        }
    }

    suspend fun updateThrottlingState(failureCount: Int, cooldownEndMs: Long) {
        dataStore.edit { prefs ->
            prefs[APP_LOCK_FAILURE_COUNT_KEY] = failureCount
            prefs[APP_LOCK_COOLDOWN_END_KEY] = cooldownEndMs
        }
    }

    suspend fun resetThrottlingState() {
        dataStore.edit { prefs ->
            prefs[APP_LOCK_FAILURE_COUNT_KEY] = 0
            prefs[APP_LOCK_COOLDOWN_END_KEY] = 0L
        }
    }

    suspend fun setBiometricEnabled(enabled: Boolean) {
        dataStore.edit { it[APP_LOCK_BIOMETRIC_KEY] = enabled }
    }

    suspend fun setAutoLockTimeout(timeout: AutoLockTimeout) {
        dataStore.edit { it[APP_LOCK_TIMEOUT_KEY] = timeout.name }
    }

    suspend fun setObscureRecents(enabled: Boolean) {
        dataStore.edit { it[APP_LOCK_OBSCURE_RECENTS_KEY] = enabled }
    }

    suspend fun setNotificationPrivacy(privacy: NotificationPrivacy) {
        dataStore.edit { it[NOTIFICATION_PRIVACY_KEY] = privacy.name }
    }

    val aboutStatus: Flow<String> = dataStore.data.map { prefs ->
        prefs[ABOUT_STATUS_KEY] ?: "Securing chats with ArmorChat"
    }

    suspend fun setAboutStatus(status: String) {
        val trimmed = status.trim().take(140)
        dataStore.edit { it[ABOUT_STATUS_KEY] = trimmed }
    }

    private companion object {
        val THEME_KEY = stringPreferencesKey("theme_mode")
        val MLS_HARNESS_KEY = booleanPreferencesKey("developer_mls_harness")
        val CACHED_POLICY_KEY = stringPreferencesKey("cached_network_policy")

        val APP_LOCK_ENABLED_KEY = booleanPreferencesKey("app_lock_enabled")
        val APP_LOCK_TYPE_KEY = stringPreferencesKey("app_lock_type")
        val APP_LOCK_SALT_KEY = stringPreferencesKey("app_lock_salt")
        val APP_LOCK_VERIFIER_KEY = stringPreferencesKey("app_lock_verifier")
        val APP_LOCK_BIOMETRIC_KEY = booleanPreferencesKey("app_lock_biometric")
        val APP_LOCK_TIMEOUT_KEY = stringPreferencesKey("app_lock_timeout")
        val APP_LOCK_OBSCURE_RECENTS_KEY = booleanPreferencesKey("app_lock_obscure_recents")
        val NOTIFICATION_PRIVACY_KEY = stringPreferencesKey("notification_privacy")
        val ABOUT_STATUS_KEY = stringPreferencesKey("user_about_status")

        val APP_LOCK_FAILURE_COUNT_KEY = intPreferencesKey("app_lock_failure_count")
        val APP_LOCK_COOLDOWN_END_KEY = longPreferencesKey("app_lock_cooldown_end")
    }
}
