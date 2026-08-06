package com.apexlions.film2.studio.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val Film2StudioColorScheme = darkColorScheme(
    primary = Teal,
    onPrimary = TealOnTeal,
    primaryContainer = TealMuted,
    onPrimaryContainer = TealOnTeal,
    secondary = StudioTextSecondary,
    onSecondary = StudioBackground,
    background = StudioBackground,
    onBackground = StudioTextPrimary,
    surface = StudioSurface,
    onSurface = StudioTextPrimary,
    surfaceVariant = StudioSurfaceVariant,
    onSurfaceVariant = StudioTextSecondary,
    surfaceContainer = StudioSurfaceRaised,
    surfaceContainerHigh = StudioSurfaceRaised,
    outline = StudioOutline,
    error = StudioError,
    onError = StudioBackground,
)

@Composable
fun Film2StudioTheme(
    content: @Composable () -> Unit,
) {
    val colorScheme = Film2StudioColorScheme
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowCompat.getInsetsController(window, view).isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Film2StudioTypography,
        content = content,
    )
}
