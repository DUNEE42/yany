package com.xinyu.companion.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(
    primary = Coral,
    onPrimary = CreamSurface,
    primaryContainer = CoralSoft,
    onPrimaryContainer = Ink,
    secondary = Sage,
    background = WarmPaper,
    onBackground = Ink,
    surface = CreamSurface,
    onSurface = Ink,
    surfaceVariant = ColorTokens.LightSurfaceVariant,
    onSurfaceVariant = InkMuted,
    error = ColorTokens.Error,
)

private val DarkColors = darkColorScheme(
    primary = ColorTokens.DarkCoral,
    onPrimary = Ink,
    primaryContainer = ColorTokens.DarkCoralContainer,
    secondary = ColorTokens.DarkSage,
    background = WarmPaperDark,
    onBackground = ColorTokens.DarkText,
    surface = NightSurface,
    onSurface = ColorTokens.DarkText,
    surfaceVariant = ColorTokens.DarkSurfaceVariant,
    onSurfaceVariant = ColorTokens.DarkMuted,
    error = ColorTokens.DarkError,
)

private object ColorTokens {
    val LightSurfaceVariant = androidx.compose.ui.graphics.Color(0xFFF0E7DD)
    val Error = androidx.compose.ui.graphics.Color(0xFFB3261E)
    val DarkText = androidx.compose.ui.graphics.Color(0xFFF3EEE8)
    val DarkMuted = androidx.compose.ui.graphics.Color(0xFFB5AFA8)
    val DarkSurfaceVariant = androidx.compose.ui.graphics.Color(0xFF303339)
    val DarkError = androidx.compose.ui.graphics.Color(0xFFFFB4AB)
    val DarkCoral = androidx.compose.ui.graphics.Color(0xFFFFB4A8)
    val DarkCoralContainer = androidx.compose.ui.graphics.Color(0xFF71352E)
    val DarkSage = androidx.compose.ui.graphics.Color(0xFFA8C2AE)
}

@Composable
fun XinYuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = androidx.compose.material3.Typography(),
        content = content,
    )
}

