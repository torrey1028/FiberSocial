package com.autom8ed.fibersocial.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import com.autom8ed.fibersocial.composeapp.resources.Res
import com.autom8ed.fibersocial.composeapp.resources.fibersocial_logo
import com.autom8ed.fibersocial.composeapp.resources.fibersocial_logo_dark
import com.autom8ed.fibersocial.settings.ThemeMode
import org.jetbrains.compose.resources.DrawableResource

/**
 * Whether the active app theme is dark. Set by [FiberSocialTheme] from the resolved
 * [ThemeMode] — read this instead of `isSystemInDarkTheme()` for theme-dependent assets,
 * because the in-app override can disagree with the system setting (and resource
 * `-night`/`-dark` qualifiers only ever follow the system).
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** The FiberSocial logo drawable matching the active theme. */
@Composable
fun appLogoResource(): DrawableResource =
    if (LocalDarkTheme.current) Res.drawable.fibersocial_logo_dark else Res.drawable.fibersocial_logo

/**
 * Keeps the platform's system chrome (Android status/navigation bars) in step with the
 * active color scheme. No-op where the platform has no equivalent.
 */
@Composable
internal expect fun SystemBarStyle(dark: Boolean, background: Color)

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

    SystemBarStyle(dark = dark, background = colorScheme.background)

    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = colorScheme, content = content)
    }
}
