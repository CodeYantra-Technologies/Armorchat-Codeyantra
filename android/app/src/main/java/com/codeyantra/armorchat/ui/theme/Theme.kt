package com.codeyantra.armorchat.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.codeyantra.armorchat.domain.model.ThemeMode
import com.codeyantra.armorchat.feature.settings.SettingsViewModel

val LightColors = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD0E8F7),
    onPrimaryContainer = Color(0xFF002238),

    secondary = LightSecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4FF),
    onSecondaryContainer = Color(0xFF001C47),

    tertiary = LightTertiary,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFC0F2E7),
    onTertiaryContainer = Color(0xFF002A22),

    background = LightBackground,
    onBackground = LightOnBackground,

    surface = LightSurface,
    onSurface = LightOnSurface,

    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,

    outline = LightOutline,
    outlineVariant = LightOutlineVariant,

    error = Color(0xFFD32F2F),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

val DarkColors = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkBackground,
    primaryContainer = Color(0xFF0D374C),
    onPrimaryContainer = Color(0xFFC2F1FF),

    secondary = DarkSecondary,
    onSecondary = DarkBackground,
    secondaryContainer = Color(0xFF0F2B66),
    onSecondaryContainer = Color(0xFFD6E3FF),

    tertiary = DarkTertiary,
    onTertiary = DarkBackground,
    tertiaryContainer = Color(0xFF0C3D30),
    onTertiaryContainer = Color(0xFFB5F6E4),

    background = DarkBackground,
    onBackground = DarkOnBackground,

    surface = DarkSurface,
    onSurface = DarkOnSurface,

    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,

    outline = DarkOutline,
    outlineVariant = DarkOutlineVariant,

    error = ErrorRed,
    onError = DarkBackground,
    errorContainer = Color(0xFF421515),
    onErrorContainer = Color(0xFFFFD6D6),
)

@Composable
fun ArmorChatTheme(
    viewModel: SettingsViewModel = hiltViewModel(),
    content: @Composable () -> Unit,
) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    ArmorChatTheme(themeMode = themeMode, content = content)
}

@Composable
fun ArmorChatTheme(
    themeMode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val isDark = when (themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
    }

    val colorScheme = if (isDark) DarkColors else LightColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !isDark
                controller.isAppearanceLightNavigationBars = !isDark
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = ArmorChatTypography,
        content = content,
    )
}
