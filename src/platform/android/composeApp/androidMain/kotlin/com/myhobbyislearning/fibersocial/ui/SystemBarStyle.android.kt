package com.myhobbyislearning.fibersocial.ui

import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.activity.SystemBarStyle as ActivitySystemBarStyle

/**
 * With edge-to-edge enabled (see MainActivity's enableEdgeToEdge() call, mandatory
 * anyway once targetSdk >= 35), the system bars are transparent scrims rather than
 * solid-colored, and Window.setStatusBarColor/setNavigationBarColor are deprecated
 * no-ops under it — so icon contrast, not [background], is normally the only thing
 * left to set here dynamically, since it depends on the in-app theme choice, not
 * just device config.
 *
 * [background] is still used for one narrow case: on API 26-28 specifically, the
 * navigation-bar scrim enableEdgeToEdge() paints is a fixed color chosen once from
 * the system dark/light setting (API 29+ repaints it automatically to track
 * isAppearanceLightNavigationBars, so this doesn't apply there). Re-invoking
 * enableEdgeToEdge() here with the resolved theme color keeps that scrim in sync
 * with an in-app ThemeMode override that disagrees with the system setting.
 */
@Composable
internal actual fun SystemBarStyle(dark: Boolean, background: Color) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val activity = view.context as ComponentActivity
            activity.enableEdgeToEdge(
                navigationBarStyle = ActivitySystemBarStyle.auto(background.toArgb(), background.toArgb()),
            )
            WindowCompat.getInsetsController(activity.window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
}
