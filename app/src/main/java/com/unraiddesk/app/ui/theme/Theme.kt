package com.unraiddesk.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.unraiddesk.app.core.data.prefs.AppPreferencesRepository.ThemeMode

data class SemanticColors(
    val running: Color,
    val warning: Color,
    val error: Color,
    val offline: Color
)

val LocalSemanticColors = staticCompositionLocalOf {
    SemanticColors(
        running = StatusGreenLight,
        warning = StatusAmberLight,
        error = StatusRedLight,
        offline = StatusSlateLight
    )
}

private val LightColors = lightColorScheme(
    primary = UnraidOrange,
    onPrimary = Color.White,
    background = LightBackground,
    onBackground = Color(0xFF181C22),
    surface = LightSurface,
    onSurface = Color(0xFF181C22),
    surfaceContainerLow = LightSurface,
    surfaceContainer = LightSurfaceVariant,
    surfaceContainerHighest = Color(0xFFE2E6EE),
    outline = LightOutline,
    outlineVariant = LightOutline
)

private val DarkColors = darkColorScheme(
    primary = UnraidOrangeDark,
    onPrimary = Color(0xFF281209),
    background = DarkBackground,
    onBackground = Color(0xFFE6EAF0),
    surface = DarkSurface,
    onSurface = Color(0xFFE6EAF0),
    surfaceContainerLow = DarkSurface,
    surfaceContainer = DarkSurfaceVariant,
    surfaceContainerHighest = Color(0xFF2C333F),
    outline = DarkOutline,
    outlineVariant = DarkOutline
)

@Composable
fun UnraidDeskTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = if (isDark) DarkColors else LightColors
    val semantic = if (isDark) {
        SemanticColors(
            running = StatusGreenDark,
            warning = StatusAmberDark,
            error = StatusRedDark,
            offline = StatusSlateDark
        )
    } else {
        SemanticColors(
            running = StatusGreenLight,
            warning = StatusAmberLight,
            error = StatusRedLight,
            offline = StatusSlateLight
        )
    }

    CompositionLocalProvider(LocalSemanticColors provides semantic) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = UnraidDeskTypography,
            content = content
        )
    }
}
