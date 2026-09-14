package com.codeyantra.armorchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.model.ThemeMode
import com.codeyantra.armorchat.domain.repository.SettingsRepository
import com.codeyantra.armorchat.domain.usecase.ObserveThemeUseCase
import com.codeyantra.armorchat.domain.usecase.SetThemeUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    observeTheme: ObserveThemeUseCase,
    private val setTheme: SetThemeUseCase,
    settingsRepository: SettingsRepository,
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = observeTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    val developerMlsHarness: StateFlow<Boolean> = settingsRepository.observeDeveloperMlsHarness()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val settings = settingsRepository

    fun onThemeSelected(mode: ThemeMode) {
        viewModelScope.launch { setTheme(mode) }
    }

    fun onDeveloperMlsHarness(enabled: Boolean) {
        viewModelScope.launch { settings.setDeveloperMlsHarness(enabled) }
    }
}
