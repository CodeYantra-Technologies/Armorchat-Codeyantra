package com.codeyantra.armorchat.domain.usecase

import com.codeyantra.armorchat.domain.repository.SettingsRepository
import javax.inject.Inject

class ObserveThemeUseCase @Inject constructor(
    private val settingsRepository: SettingsRepository,
) {
    operator fun invoke() = settingsRepository.observeThemeMode()
}
