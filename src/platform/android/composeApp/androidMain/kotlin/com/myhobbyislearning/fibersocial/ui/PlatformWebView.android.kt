@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.ui

import android.graphics.Bitmap
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
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.describeUrlForLog

@Composable
actual fun PlatformWebView(
    url: String,
    isAllowedNavigation: (String) -> Boolean,
    onBackExhausted: () -> Unit,
    modifier: Modifier,
    // Unused on Android, and that is the point: this WebView and the login one share the
    // app-global CookieManager, so the Ravelry session set during OAuth is already here.
    // Only iOS has to be handed it, because its login web view uses a non-persistent
    // store on purpose. See the expect declaration.
    @Suppress("UNUSED_PARAMETER") sessionCookie: String?,
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
                        // Subframes can't take the user anywhere, and cancelling them
                        // breaks pages that legitimately embed third-party content.
                        if (!request.isForMainFrame) return false
                        val target = request.url.toString()
                        if (isAllowedNavigation(target)) return false
                        // Cancelled, not bounced to the browser: leaving for an external
                        // site is the behavior guideline 4 rejected (issue #481), and
                        // nothing off this flow is why the user opened this screen.
                        DebugLog.log("WebPage blocked navigation to ${describeUrlForLog(target)}")
                        return true
                    }

                    // Second line of defense, the twin of the login view's (issue #447).
                    // shouldOverrideUrlLoading is documented as NOT invoked for POST
                    // requests, and this screen is mostly forms — a Ravelry sign-in POST,
                    // then the deletion form. Without this, one of those could put an
                    // unexpected page on screen with the policy never consulted, which is
                    // the whole failure class #425 exists to close.
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        if (url == "about:blank" || isAllowedNavigation(url)) return
                        DebugLog.log("WebPage caught an off-flow page load: ${describeUrlForLog(url)}")
                        // stopLoading alone would leave whatever already rendered on
                        // screen, so blank it rather than showing an unexpected page.
                        view.stopLoading()
                        view.loadUrl("about:blank")
                    }
                }
                loadUrl(url)
                webViewRef = this
            }
        },
    )
}
