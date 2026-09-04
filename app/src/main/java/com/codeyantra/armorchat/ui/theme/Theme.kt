package com.codeyantra.armorchat.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.domain.model.ThemeMode
import com.codeyantra.armorchat.feature.settings.SettingsViewModel

private val DarkColors = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Navy950,
    primaryContainer = AccentBlueDim,
    onPrimaryContainer = Silver100,
    secondary = Silver300,
    onSecondary = Navy950,
    background = Navy950,
    onBackground = Silver100,
    surface = Navy900,
    onSurface = Silver100,
    surfaceVariant = Navy800,
    onSurfaceVariant = MutedBlue,
    outline = Hairline,
    outlineVariant = Navy700,
    error = Danger,
    onError = Silver100,
)

private val LightColors = lightColorScheme(
    primary = AccentBlueDim,
    onPrimary = Paper50,
    primaryContainer = Color(0xFFD6E7F8),
    onPrimaryContainer = Ink950,
    secondary = Ink700,
    onSecondary = Paper50,
    background = Paper50,
    onBackground = Ink950,
    surface = Color(0xFFFFFFFF),
    onSurface = Ink950,
    surfaceVariant = Paper100,
    onSurfaceVariant = Ink500,
    outline = Color(0x3310141C),
    outlineVariant = Color(0x1A10141C),
    error = Danger,
    onError = Paper50,
)

@Composable
fun ArmorChatTheme(
    viewModel: SettingsViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val dark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ArmorChatTypography,
        content = content,
    )
}
