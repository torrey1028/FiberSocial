package com.myhobbyislearning.fibersocial.login

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.myhobbyislearning.fibersocial.auth.RavelryAuthManager

@Composable
actual fun WebViewLoginScreen(
    authUrl: String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
) {
    println("FiberSocial: WebViewLoginScreen authUrl=$authUrl")
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                // Ravelry's authorize page renders wider than the screen (more so as the
                // requested OAuth scope grows), and without these it doesn't scale to fit,
                // forcing manual horizontal scroll to reach the Authorize button (issue #278).
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = true
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
                            println("FiberSocial: www.ravelry.com cookies: $wwwCookie")
                            println("FiberSocial: ravelry.com cookies: $rootCookie")
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
            }
        },
    )
}
