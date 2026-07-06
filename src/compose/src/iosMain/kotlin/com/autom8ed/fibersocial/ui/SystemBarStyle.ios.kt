package com.autom8ed.fibersocial.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * No-op on iOS: there are no navigation-bar colors to keep in step, and the status
 * bar's light/dark text follows the host `UIViewController`, which
 * `ComposeUIViewController` already keys off the interface style.
 */
@Composable
internal actual fun SystemBarStyle(dark: Boolean, background: Color) = Unit
