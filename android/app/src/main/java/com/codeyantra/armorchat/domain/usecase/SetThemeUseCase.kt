package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.model.ThemeMode
import com.codeyantra.armorchat.domain.repository.SettingsRepository
import javax.inject.Inject

class SetThemeUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    suspend operator fun invoke(mode: ThemeMode) {
        settingsRepository.setThemeMode(mode)
    }
}
