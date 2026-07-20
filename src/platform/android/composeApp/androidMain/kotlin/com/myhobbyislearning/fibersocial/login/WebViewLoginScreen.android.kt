@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.login

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import com.myhobbyislearning.fibersocial.auth.RavelryAuthManager
import com.myhobbyislearning.fibersocial.debug.describeSessionCookie

@Composable
actual fun WebViewLoginScreen(
    authUrl: String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    onBack: () -> Unit,
) {
    println("FiberSocial: WebViewLoginScreen authUrl=$authUrl")
    // Holds the created WebView so BackHandler below can check/drive its own history —
    // AndroidView's factory runs once the underlying view exists, which BackHandler
    // (evaluated on every composition) can't reach any other way.
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // System back navigates the WEB flow's own history first — e.g. backing out of a
    // "sign up for an account" detour taken from the login page — and only leaves the
    // screen entirely once there's nowhere further back to go within it. Without this,
    // nothing here handles back at all, so it falls through to the Activity default and
    // exits the app outright (issue #308).
    BackHandler {
        val webView = webViewRef
        if (webView != null && webView.canGoBack()) {
            webView.goBack()
        } else {
            onBack()
        }
    }
    // Edge-to-edge (mandatory once targetSdk >= 35) draws content behind the system
    // bars by default; without this, the OAuth page's own header/submit controls can
    // end up under the status/navigation bar rather than just under app chrome.
    AndroidView(
        modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Ravelry's authorize page renders wider than the screen (more so as the
                // requested OAuth scope grows), and without these it doesn't scale to fit,
                // forcing manual horizontal scroll to reach the Authorize button (issue #278).
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
                // Lets the user pinch-zoom in on the authorize form themselves instead of
                // reading it at the fit-to-width size. Controls hidden — the on-screen
                // +/- buttons look out of place in a login flow; pinch still works without them.
                settings.setSupportZoom(true)
                settings.builtInZoomControls = true
                settings.displayZoomControls = false
                // Clear cookies so the user must log in fresh — ensures _ravelry_session is set
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
                CookieManager.getInstance().setAcceptCookie(true)
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        println("FiberSocial: WebView navigating to ${url.take(120)}")
                        if (url.startsWith(RavelryAuthManager.REDIRECT_URI)) {
                            val redirect = Uri.parse(url)
                            val code = redirect.getQueryParameter("code") ?: return true
                            val state = redirect.getQueryParameter("state")
                            val cm = CookieManager.getInstance()
                            val wwwCookie = cm.getCookie("https://www.ravelry.com") ?: ""
                            val rootCookie = cm.getCookie("https://ravelry.com") ?: ""
                            println("FiberSocial: OAuth complete")
                            // Never interpolate a cookie directly — describeSessionCookie
                            // hides the value unless a debug build opted in (issue #395).
                            println("FiberSocial: www.ravelry.com cookie ${describeSessionCookie(wwwCookie)}")
                            println("FiberSocial: ravelry.com cookie ${describeSessionCookie(rootCookie)}")
                            val cookie = wwwCookie.ifEmpty { rootCookie }
                            onAuthComplete(code, state, cookie)
                            return true
                        }
                        return false
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        println("FiberSocial: WebView error ${error.errorCode} ${error.description} url=${request.url}")
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        println("FiberSocial: WebView page loaded: ${url.take(120)}")
                    }
                }
                println("FiberSocial: WebView loading $authUrl")
                loadUrl(authUrl)
                webViewRef = this
            }
        },
    )
}
