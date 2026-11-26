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
    onPrimaryContainer = CoralOnContainer,
    secondary = Sage,
    onSecondary = CreamSurface,
    secondaryContainer = SageSoft,
    onSecondaryContainer = SageOnContainer,
    tertiary = Terracotta,
    onTertiary = CreamSurface,
    tertiaryContainer = TerracottaSoft,
    onTertiaryContainer = TerracottaOnContainer,
    background = WarmPaper,
    onBackground = Ink,
    surface = CreamSurface,
    onSurface = Ink,
    surfaceVariant = WarmSurfaceVariant,
    onSurfaceVariant = InkMuted,
    surfaceContainerLow = WarmSurfaceLow,
    surfaceContainer = CreamSurface,
    surfaceContainerHigh = WarmSurfaceVariant,
    outline = WarmOutline,
    outlineVariant = WarmOutlineVariant,
    error = LightError,
    onError = LightOnError,
    errorContainer = LightErrorContainer,
    onErrorContainer = LightOnErrorContainer,
)

private val DarkColors = darkColorScheme(
    primary = DarkCoral,
    onPrimary = DarkOnCoral,
    primaryContainer = DarkCoralContainer,
    onPrimaryContainer = DarkOnCoralContainer,
    secondary = DarkSage,
    onSecondary = DarkOnSage,
    secondaryContainer = DarkSageContainer,
    onSecondaryContainer = DarkOnSageContainer,
    tertiary = DarkTerracotta,
    onTertiary = DarkOnCoral,
    tertiaryContainer = DarkTerracottaContainer,
    onTertiaryContainer = DarkOnTerracottaContainer,
    background = WarmPaperDark,
    onBackground = DarkInk,
    surface = NightSurface,
    onSurface = DarkInk,
    surfaceVariant = NightSurfaceVariant,
    onSurfaceVariant = DarkInkMuted,
    surfaceContainerLow = NightSurfaceLow,
    surfaceContainer = NightSurface,
    surfaceContainerHigh = NightSurfaceVariant,
    outline = NightOutline,
    outlineVariant = NightOutlineVariant,
    error = DarkError,
    onError = DarkOnError,
    errorContainer = DarkErrorContainer,
    onErrorContainer = DarkOnErrorContainer,
)

@Composable
fun XinYuTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkColors else LightColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as? Activity ?: return@SideEffect
            val insetsController = WindowCompat.getInsetsController(activity.window, view)
            insetsController.isAppearanceLightStatusBars = !darkTheme
            insetsController.isAppearanceLightNavigationBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colors,
        typography = XinYuTypography,
        shapes = XinYuShapes,
        content = content,
    )
}
