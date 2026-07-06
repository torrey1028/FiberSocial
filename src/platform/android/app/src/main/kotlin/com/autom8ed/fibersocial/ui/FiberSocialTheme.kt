package com.autom8ed.fibersocial.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import com.autom8ed.fibersocial.settings.ThemeMode

/**
 * App-wide Material theme (issue #153): [ThemeMode.SYSTEM] follows the device's
 * light/dark setting, [ThemeMode.LIGHT]/[ThemeMode.DARK] override it. Also keeps the
 * system bars in step with the chosen scheme — the launch theme (`Theme.FiberSocial`)
 * only follows the system, so an in-app override must restyle the bars itself or a
 * dark app would sit under a light status bar.
 */
@Composable
fun FiberSocialTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colorScheme = if (dark) darkColorScheme() else lightColorScheme()

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            window.navigationBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }

    MaterialTheme(colorScheme = colorScheme, content = content)
}
