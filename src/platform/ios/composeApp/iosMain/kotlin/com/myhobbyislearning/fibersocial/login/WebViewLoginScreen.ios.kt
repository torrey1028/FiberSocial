package com.myhobbyislearning.fibersocial.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.myhobbyislearning.fibersocial.auth.AuthCallback
import com.myhobbyislearning.fibersocial.auth.LOGIN_FLOW_LOST_MESSAGE
import com.myhobbyislearning.fibersocial.auth.LoginNavigationDecision
import com.myhobbyislearning.fibersocial.auth.MALFORMED_AUTH_CALLBACK_MESSAGE
import com.myhobbyislearning.fibersocial.auth.RavelryAuthManager
import com.myhobbyislearning.fibersocial.auth.authFailureMessage
import com.myhobbyislearning.fibersocial.auth.loginNavigationDecision
import com.myhobbyislearning.fibersocial.auth.parseAuthCallback
import com.myhobbyislearning.fibersocial.debug.DebugFlags
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.describeSessionCookie
import com.myhobbyislearning.fibersocial.debug.describeUrlForLog
import com.myhobbyislearning.fibersocial.debug.rememberShareText
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSError
import platform.Foundation.NSHTTPCookie
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKNavigationTypeBackForward
import platform.WebKit.WKNavigationTypeFormSubmitted
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKNavigationTypeReload
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
    buildAuthUrl: () -> String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    onAuthError: (message: String) -> Unit,
    onBack: () -> Unit,
) {
    // remember: WKWebView.navigationDelegate is weak; the composition must hold the
    // strong reference or the delegate is collected mid-login.
    val delegate = remember { LoginNavigationDelegate(buildAuthUrl, onAuthComplete, onAuthError) }
    Column(Modifier.fillMaxSize()) {
        // Debug builds only: a login failure can strand the flow INSIDE the web view
        // (e.g. dumped onto ravelry.com's home page), and iOS has no system back to
        // escape it — which also made the login screen's log export unreachable right
        // after the failures it exists to capture. This bar keeps both an exit and the
        // export reachable from anywhere in the web flow. Absent in release builds.
        if (DebugFlags.debugToolsAvailable) {
            DebugLoginToolbar(onBack)
        }
        LoginWebView(buildAuthUrl, delegate)
    }
}

@Composable
private fun DebugLoginToolbar(onBack: () -> Unit) {
    val shareText = rememberShareText()
    Surface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onBack) { Text("Exit login") }
            TextButton(onClick = { shareText(DebugLog.dump()) }) { Text("Share log") }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun LoginWebView(buildAuthUrl: () -> String, delegate: LoginNavigationDelegate) {
    UIKitView(
        factory = {
            val configuration = WKWebViewConfiguration().apply {
                websiteDataStore = WKWebsiteDataStore.nonPersistentDataStore()
                // On iPhone this defaults to false, which forces ANY <video> on the page
                // into the fullscreen native player — Ravelry's login page has an animated
                // video that would otherwise take over the screen the moment a tap on the
                // form gives it a user gesture to start playing.
                allowsInlineMediaPlayback = true
            }
            WKWebView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0), configuration = configuration).apply {
                navigationDelegate = delegate
                // Lets the standard edge-swipe gesture navigate the web flow's own
                // history within the allowed auth pages — e.g. back out of a sign-up or
                // forgot-password detour taken from the login page (issue #308) —
                // mirroring Android's system-back handling of the same case. iOS has no
                // system-level back button/gesture equivalent to fall back to once
                // history is exhausted, so in release builds nothing triggers onBack
                // here; debug builds wire it to the toolbar's "Exit login" (see
                // WebViewLoginScreen above).
                allowsBackForwardNavigationGestures = true
                val authUrl = buildAuthUrl()
                DebugLog.log("WebView loading $authUrl")
                loadRequest(NSURLRequest(uRL = NSURL(string = authUrl)!!))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

private class LoginNavigationDelegate(
    private val buildAuthUrl: () -> String,
    private val onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    private val onAuthError: (message: String) -> Unit,
) : NSObject(), WKNavigationDelegateProtocol {

    // Restarts performed by this screen; caps the recovery loop.
    private var flowRestarts = 0

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        val url = decidePolicyForNavigationAction.request.URL?.absoluteString ?: ""
        DebugLog.log("WebView navigating to ${describeUrlForLog(url)}")
        if (!url.startsWith(RavelryAuthManager.REDIRECT_URI)) {
            // Off-flow navigations: a user tap is a browse attempt and is silently
            // cancelled — the login WebView is not a Ravelry browser (issue #425;
            // Apple browsed it to the web messages composer and crashed the app from
            // its camera upload, the 2.1(a) rejection; bouncing taps out to Safari
            // was tried and felt broken mid-login). A SERVER-driven move off the flow
            // is different: the flow state behind the current page is dead (observed:
            // a stale authorize challenge bouncing through /account/login?prompt=1 to
            // the home page, issue #434), so staying parked would strand the user —
            // restart with a fresh authorize URL instead, then give up loudly once
            // the restart budget is spent. Only main-frame navigations are policed;
            // a null targetFrame means a new-window attempt, which is treated as
            // main-frame. Subframe loads can't take the user anywhere, and cancelling
            // them would just break allowed pages.
            val isMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
            if (!isMainFrame) {
                decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
                return
            }
            val userInitiated = when (decidePolicyForNavigationAction.navigationType) {
                WKNavigationTypeLinkActivated, WKNavigationTypeFormSubmitted,
                WKNavigationTypeBackForward, WKNavigationTypeReload,
                -> true
                else -> false
            }
            when (loginNavigationDecision(url, userInitiated, flowRestarts)) {
                LoginNavigationDecision.ALLOW ->
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
                LoginNavigationDecision.BLOCK -> {
                    DebugLog.log("WebView cancelled non-login navigation to ${describeUrlForLog(url)}")
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                }
                LoginNavigationDecision.RESTART_FLOW -> {
                    flowRestarts++
                    DebugLog.log("login flow went off the rails (server redirect) — restart #$flowRestarts")
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                    webView.loadRequest(NSURLRequest(uRL = NSURL(string = buildAuthUrl())!!))
                }
                LoginNavigationDecision.FAIL_LOGIN -> {
                    DebugLog.log("login flow lost after $flowRestarts restarts — giving up")
                    decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
                    onAuthError(LOGIN_FLOW_LOST_MESSAGE)
                }
            }
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        // Every branch below must call something. The navigation is already cancelled, so
        // a silent return strands the user on the authorize page (issue #394).
        val callback = parseAuthCallback(url)
        if (callback is AuthCallback.Failure) {
            DebugLog.log("OAuth failed: ${callback.error} description=${callback.description}")
            onAuthError(authFailureMessage(callback))
            return
        }
        if (callback !is AuthCallback.Success) {
            DebugLog.log("OAuth redirect carried neither code nor error")
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
            DebugLog.log("OAuth complete")
            // Never interpolate a cookie directly — describeSessionCookie hides the value
            // unless a debug build opted in (issue #395).
            DebugLog.log("www.ravelry.com cookie ${describeSessionCookie(wwwCookie)}")
            DebugLog.log("ravelry.com cookie ${describeSessionCookie(rootCookie)}")
            onAuthComplete(code, state, wwwCookie.ifEmpty { rootCookie })
        }
    }

    // The iOS analog of Android's onPageFinished logging: with it, the exported trace
    // distinguishes "requested but never loaded" from "loaded and then went wrong".
    override fun webView(webView: WKWebView, didFinishNavigation: WKNavigation?) {
        DebugLog.log("WebView page loaded: ${describeUrlForLog(webView.URL?.absoluteString ?: "")}")
    }

    // The iOS analog of Android's onReceivedError logging. Provisional failures are
    // where network-level errors (offline, DNS, TLS, cancelled loads) surface.
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        DebugLog.log(
            "WebView load failed: ${withError.domain} ${withError.code} " +
                withError.localizedDescription,
        )
    }

    // iOS reclaims WKWebView's content process under memory pressure — typically while
    // the user is off in a password manager mid-login. The default behavior is a dead
    // blank page; reload recovers it. Suspected trigger for the login flow losing its
    // place (a reloaded authorize page carries a stale one-time challenge), so the log
    // line is the evidence either way.
    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        DebugLog.log("WebView content process terminated — reloading ${describeUrlForLog(webView.URL?.absoluteString ?: "")}")
        webView.reload()
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
