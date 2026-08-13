package com.myhobbyislearning.fibersocial.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalUriHandler
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.describeUrlForLog

@Composable
actual fun rememberOpenWebPage(): (url: String) -> Boolean {
    val uriHandler = LocalUriHandler.current
    return { url ->
        DebugLog.log("openWebPage handing off ${describeUrlForLog(url)}")
        // AndroidUriHandler rethrows ActivityNotFoundException as IllegalArgumentException
        // when nothing can handle an https ACTION_VIEW — no browser installed, or one
        // disabled by a managed profile. See the expect declaration for why the guard
        // lives here instead of at each call site.
        runCatching { uriHandler.openUri(url) }
            .onFailure { DebugLog.log("openWebPage hand-off failed: ${it.message}") }
            .isSuccess
    }
}
