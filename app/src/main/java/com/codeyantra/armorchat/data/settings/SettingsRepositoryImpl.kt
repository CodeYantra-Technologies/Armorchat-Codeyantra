package com.codeyantra.armorchat.data.settings

import com.codeyantra.armorchat.core.preferences.SettingsDataStore
import com.codeyantra.armorchat.domain.model.ThemeMode
import com.codeyantra.armorchat.domain.repository.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

@Singleton
class SettingsRepositoryImpl @Inject constructor(
    private val settingsDataStore: SettingsDataStore,
) : SettingsRepository {
    override fun observeThemeMode(): Flow<ThemeMode> = settingsDataStore.themeMode

    override suspend fun setThemeMode(mode: ThemeMode) {
        settingsDataStore.setThemeMode(mode)
    }

    override fun observeDeveloperMlsHarness(): Flow<Boolean> = settingsDataStore.developerMlsHarness

    override suspend fun setDeveloperMlsHarness(enabled: Boolean) {
        settingsDataStore.setDeveloperMlsHarness(enabled)
    }
}
