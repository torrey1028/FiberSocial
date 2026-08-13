@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.ui

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import com.myhobbyislearning.fibersocial.auth.isRavelryWebUrl
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.describeUrlForLog

@Composable
actual fun PlatformWebView(
    url: String,
    onBackExhausted: () -> Unit,
    modifier: Modifier,
) {
    // Same shape as the login web view: AndroidView's factory is where the view exists,
    // and BackHandler re-evaluates every composition, so the reference is held here.
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    BackHandler {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) webView.goBack() else onBackExhausted()
    }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            WebView(context).apply {
                // Ravelry's pages are unusable without both — the sign-in form and the
                // profile editor are script-driven.
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Ravelry renders wider than a phone screen; without these the page does
                // not scale to fit and controls sit off-screen (same fix as issue #278).
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // NOT cleared, unlike the login web view: that one wipes cookies to force
                // a fresh OAuth login, while this one wants whatever Ravelry session the
                // app-global CookieManager already holds, so the user often lands on the
                // deletion page already signed in instead of having to log in again.
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        if (!request.isForMainFrame) return false
                        val target = request.url.toString()
                        if (isRavelryWebUrl(target)) return false
                        // Cancelled, not bounced to the browser: leaving for an external
                        // site is the behavior guideline 4 rejected (issue #481), and
                        // nothing off Ravelry is part of why the user opened this screen.
                        DebugLog.log("WebPage blocked off-site navigation to ${describeUrlForLog(target)}")
                        return true
                    }
                }
                loadUrl(url)
                webViewRef = this
            }
        },
    )
}
