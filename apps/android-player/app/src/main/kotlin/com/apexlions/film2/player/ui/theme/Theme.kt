package com.apexlions.film2.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

// Always dark — this is a deliberate Netflix-style near-black design, not a
// light/dark-adaptive Material default. isSystemInDarkTheme() is intentionally unused.
private val Film2DarkColorScheme = darkColorScheme(
    primary = Gold,
    onPrimary = GoldOnGold,
    primaryContainer = GoldMuted,
    onPrimaryContainer = GoldOnGold,
    secondary = TextSecondary,
    onSecondary = NearBlack,
    background = NearBlack,
    onBackground = TextPrimary,
    surface = SurfaceDark,
    onSurface = TextPrimary,
    surfaceVariant = SurfaceVariantDark,
    onSurfaceVariant = TextSecondary,
    surfaceContainer = SurfaceRaised,
    surfaceContainerHigh = SurfaceRaised,
    outline = OutlineDark,
    error = ErrorRed,
    onError = NearBlack,
)

@Composable
fun Film2PlayerTheme(
    content: @Composable () -> Unit,
) {
    val colorScheme = Film2DarkColorScheme
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
        typography = Film2PlayerTypography,
        content = content,
    )
}
