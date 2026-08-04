package com.myhobbyislearning.fibersocial.debug

import androidx.compose.runtime.Composable

/**
 * Returns a lambda that hands [text] to the platform share UI — Android's `ACTION_SEND`
 * chooser, iOS's `UIActivityViewController`.
 *
 * Exists for exporting [DebugLog] captures from a device with no debugger attached: an
 * OTA-installed iPhone build (docs/ios-debug-builds.md) has no other way to get its log
 * lines off the device — no Mac means no Xcode console, and stdout is not in the OS log.
 */
@Composable
expect fun rememberShareText(): (text: String) -> Unit
