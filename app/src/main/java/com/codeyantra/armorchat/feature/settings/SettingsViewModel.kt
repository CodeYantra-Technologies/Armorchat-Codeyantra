package com.codeyantra.armorchat.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codeyantra.armorchat.domain.model.ThemeMode
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
) : ViewModel() {

    val themeMode: StateFlow<ThemeMode> = observeTheme()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ThemeMode.SYSTEM)

    fun onThemeSelected(mode: ThemeMode) {
        viewModelScope.launch { setTheme(mode) }
    }
}
