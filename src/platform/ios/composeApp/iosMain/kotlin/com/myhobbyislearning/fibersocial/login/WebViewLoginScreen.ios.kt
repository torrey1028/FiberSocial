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
import com.myhobbyislearning.fibersocial.auth.MALFORMED_AUTH_CALLBACK_MESSAGE
import com.myhobbyislearning.fibersocial.auth.RavelryAuthManager
import com.myhobbyislearning.fibersocial.auth.authFailureMessage
import com.myhobbyislearning.fibersocial.auth.describeAuthFailureForLog
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
import platform.Foundation.NSURLErrorCancelled
import platform.Foundation.NSURLErrorDomain
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
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
    DebugLog.log("WebViewLoginScreen authUrl=${describeUrlForLog(authUrl)}")
    // remember: WKWebView.navigationDelegate is weak; the composition must hold the
    // strong reference or the delegate is collected mid-login.
    val delegate = remember { LoginNavigationDelegate(onAuthComplete, onAuthError) }
    Column(Modifier.fillMaxSize()) {
        // Debug builds only: a login failure can strand the flow INSIDE the web view
        // (e.g. dumped onto ravelry.com's home page), and iOS has no system back to
        // escape it — which also made the login screen's log export unreachable right
        // after the failures it exists to capture. This bar keeps both an exit and the
        // export reachable from anywhere in the web flow. Absent in release builds.
        if (DebugFlags.debugToolsAvailable) {
            DebugLoginToolbar(onBack)
        }
        LoginWebView(authUrl, delegate)
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
private fun LoginWebView(authUrl: String, delegate: LoginNavigationDelegate) {
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
                // history — e.g. back out of a "sign up for an account" detour taken
                // from the login page (issue #308) — mirroring Android's system-back
                // handling of the same case. iOS has no system-level back button/gesture
                // equivalent to fall back to once history is exhausted, so in release
                // builds nothing triggers onBack here; debug builds wire it to the
                // toolbar's "Exit login" (see WebViewLoginScreen above).
                allowsBackForwardNavigationGestures = true
                DebugLog.log("WebView loading ${describeUrlForLog(authUrl)}")
                loadRequest(NSURLRequest(uRL = NSURL(string = authUrl)!!))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** See `contentProcessReloads` in [LoginNavigationDelegate]. */
private const val MAX_CONTENT_PROCESS_RELOADS = 2

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
        DebugLog.log("WebView navigating to ${describeUrlForLog(url)}")
        if (!url.startsWith(RavelryAuthManager.REDIRECT_URI)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
        // Every branch below must call something. The navigation is already cancelled, so
        // a silent return strands the user on the authorize page (issue #394).
        val callback = parseAuthCallback(url)
        if (callback is AuthCallback.Failure) {
            DebugLog.log(describeAuthFailureForLog(callback))
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
    // where network-level errors (offline, DNS, TLS) surface.
    override fun webView(
        webView: WKWebView,
        didFailProvisionalNavigation: WKNavigation?,
        withError: NSError,
    ) {
        // Two "failures" that fire on every SUCCESSFUL login and would plant a phantom
        // network error in every exported trace: NSURLErrorCancelled (-999) whenever an
        // in-flight load is superseded (Ravelry's login page redirects client-side),
        // and WebKit's 102 "Frame load interrupted" when our own decisionHandler
        // cancels the redirect-URI navigation above.
        if (withError.domain == NSURLErrorDomain && withError.code == NSURLErrorCancelled) return
        if (withError.domain == "WebKitErrorDomain" && withError.code == 102L) return
        DebugLog.log(
            "WebView load failed: ${withError.domain} ${withError.code} " +
                withError.localizedDescription,
        )
    }

    // Guards the terminate-reload below: a page that OOMs the content process on every
    // load (or any other repeating kill) must not reload forever — each cycle logs 2-3
    // lines, so an unbounded loop floods the 400-line buffer and evicts the login trace
    // this file exists to capture, while heating the device. One reload is the fix for
    // the observed one-off reclaim (password-manager app switch); the second is margin,
    // matching the login flow's restart-budget philosophy.
    private var contentProcessReloads = 0

    // iOS reclaims WKWebView's content process under memory pressure — typically while
    // the user is off in a password manager mid-login. The default behavior is a dead
    // blank page; reload recovers it. Suspected trigger for the login flow losing its
    // place (a reloaded authorize page carries a stale one-time challenge), so the log
    // line is the evidence either way.
    override fun webViewWebContentProcessDidTerminate(webView: WKWebView) {
        val current = webView.URL?.absoluteString
        when {
            // reload() re-requests the current back-forward item; with nothing committed
            // there is no item and it silently no-ops — log the truth instead of
            // claiming a recovery that cannot happen.
            current == null ->
                DebugLog.log("WebView content process terminated before any page committed — nothing to reload")
            contentProcessReloads >= MAX_CONTENT_PROCESS_RELOADS ->
                DebugLog.log("WebView content process terminated again — reload budget spent, leaving the page dead")
            else -> {
                contentProcessReloads++
                DebugLog.log(
                    "WebView content process terminated — reload #$contentProcessReloads of ${describeUrlForLog(current)}",
                )
                webView.reload()
            }
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
