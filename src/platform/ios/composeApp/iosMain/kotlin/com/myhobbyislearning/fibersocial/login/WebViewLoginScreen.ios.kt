package com.myhobbyislearning.fibersocial.login

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.myhobbyislearning.fibersocial.auth.AuthCallback
import com.myhobbyislearning.fibersocial.auth.MALFORMED_AUTH_CALLBACK_MESSAGE
import com.myhobbyislearning.fibersocial.auth.RavelryAuthManager
import com.myhobbyislearning.fibersocial.auth.authFailureMessage
import com.myhobbyislearning.fibersocial.auth.parseAuthCallback
import com.myhobbyislearning.fibersocial.debug.describeSessionCookie
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.WebKit.WKWebsiteDataStore
import platform.darwin.NSObject

/**
 * iOS login web view. A real `WKWebView` (not `ASWebAuthenticationSession`, which hides
 * its cookie jar) because the scrapers need the `_ravelry_session` cookie captured at
 * the OAuth redirect. A non-persistent `WKWebsiteDataStore` gives every login a fresh
 * jar — the same effect as Android's `CookieManager.removeAllCookies()` — and the only
 * copy of the session cookie that survives is the one handed to [onAuthComplete].
 */
@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun WebViewLoginScreen(
    authUrl: String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    onAuthError: (message: String) -> Unit,
    onBack: () -> Unit,
) {
    println("FiberSocial: WebViewLoginScreen authUrl=$authUrl")
    // remember: WKWebView.navigationDelegate is weak; the composition must hold the
    // strong reference or the delegate is collected mid-login.
    val delegate = remember { LoginNavigationDelegate(onAuthComplete, onAuthError) }
    UIKitView(
        factory = {
            val configuration = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
            }
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration).apply {
                navigationDelegate = delegate
                // Lets the standard edge-swipe gesture navigate the web flow's own
                // history — e.g. back out of a "sign up for an account" detour taken
                // from the login page (issue #308) — mirroring Android's system-back
                // handling of the same case. iOS has no system-level back button/gesture
                // equivalent to fall back to once history is exhausted, so unlike
                // Android's onBack, there's no natural trigger to wire it to here yet;
                // [onBack] exists for signature parity with the common `expect`.
                allowsBackForwardNavigationGestures = true
                println("FiberSocial: WebView loading $authUrl")
                loadRequest(NSURLRequest(uRL = NSURL(string = authUrl)!!))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private class LoginNavigationDelegate(
    private val onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    private val onAuthError: (message: String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString ?: ""
        println("FiberSocial: WebView navigating to ${url.take(120)}")
        if (!url.startsWith(RavelryAuthManager.REDIRECT_URI)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        // Every branch below must call something. The navigation is already cancelled, so
        // a silent return strands the user on the authorize page (issue #394).
        val callback = parseAuthCallback(url)
        if (callback is AuthCallback.Failure) {
            println("FiberSocial: OAuth failed: ${callback.error}")
            onAuthError(authFailureMessage(callback))
            return
        }
        if (callback !is AuthCallback.Success) {
            println("FiberSocial: OAuth redirect carried neither code nor error")
            onAuthError(MALFORMED_AUTH_CALLBACK_MESSAGE)
            return
        }
        val code = callback.code
        val state = callback.state
        webView.configuration.websiteDataStore.httpCookieStore.getAllCookies { cookies ->
            val all = cookies?.filterIsInstance<NSHTTPCookie>().orEmpty()
            // Same fallback as Android: cookies for www.ravelry.com first, then the
            // bare ravelry.com origin.
            val wwwCookie = cookieHeader(all, host = "www.ravelry.com")
            val rootCookie = cookieHeader(all, host = "ravelry.com")
            println("FiberSocial: OAuth complete")
            // Never interpolate a cookie directly — describeSessionCookie hides the value
            // unless a debug build opted in (issue #395).
            println("FiberSocial: www.ravelry.com cookie ${describeSessionCookie(wwwCookie)}")
            println("FiberSocial: ravelry.com cookie ${describeSessionCookie(rootCookie)}")
            onAuthComplete(code, state, wwwCookie.ifEmpty { rootCookie })
        }
    }

    /** RFC 6265 Cookie header line for the cookies applicable to [host]. */
    private fun cookieHeader(cookies: List<NSHTTPCookie>, host: String): String =
        cookies
            .filter { cookie ->
                val domain = cookie.domain.removePrefix(".")
                host == domain || host.endsWith(".$domain")
            }
            .joinToString("; ") { "${it.name}=${it.value}" }
}
