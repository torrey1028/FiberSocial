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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.myhobbyislearning.fibersocial.auth.AuthCallback
import com.myhobbyislearning.fibersocial.auth.MALFORMED_AUTH_CALLBACK_MESSAGE
import com.myhobbyislearning.fibersocial.auth.RavelryAuthManager
import com.myhobbyislearning.fibersocial.auth.LOGIN_FLOW_LOST_MESSAGE
import com.myhobbyislearning.fibersocial.auth.LoginNavigationDecision
import com.myhobbyislearning.fibersocial.auth.authFailureMessage
import com.myhobbyislearning.fibersocial.auth.describeAuthFailureForLog
import com.myhobbyislearning.fibersocial.auth.isAllowedLoginNavigation
import com.myhobbyislearning.fibersocial.auth.isAuthRedirect
import com.myhobbyislearning.fibersocial.auth.loginPageLoadDecision
import com.myhobbyislearning.fibersocial.auth.loginNavigationDecision
import com.myhobbyislearning.fibersocial.auth.parseAuthCallback
import com.myhobbyislearning.fibersocial.debug.DebugFlags
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.describeSessionCookie
import com.myhobbyislearning.fibersocial.debug.describeUrlForLog
import com.myhobbyislearning.fibersocial.debug.rememberShareText
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
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
import platform.WebKit.WKNavigationTypeBackForward
import platform.WebKit.WKNavigationTypeFormSubmitted
import platform.WebKit.WKNavigationTypeLinkActivated
import platform.WebKit.WKNavigationTypeReload
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
    buildAuthUrl: () -> String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    onAuthError: (message: String) -> Unit,
    onBack: () -> Unit,
) {
    // remember: WKWebView.navigationDelegate is weak; the composition must hold the
    // strong reference or the delegate is collected mid-login.
    val delegate = remember { LoginNavigationDelegate(buildAuthUrl, onAuthComplete, onAuthError) }
    // Held so the toolbar's Back can drive the web view's OWN history — UIKitView's
    // factory runs where the view is created, which the toolbar can't reach otherwise.
    // Plain remember, not state: nothing recomposes on it, it's read inside a click.
    val webViewHolder = remember { WebViewHolder() }
    Column(Modifier.fillMaxSize()) {
        LoginToolbar(
            onBack = {
                // Web history first, then leave the screen — the same rule Android's
                // BackHandler applies to the system back button (issue #308), so the
                // sign-up or password-reset detour taken from the login page backs out to
                // the login form rather than abandoning the login attempt outright.
                val webView = webViewHolder.webView
                if (webView != null && webView.canGoBack) {
                    webView.goBack()
                } else {
                    onBack()
                }
            },
        )
        LoginWebView(buildAuthUrl, delegate, webViewHolder)
    }
}

/** Strong-reference box for the live [WKWebView]; see its `remember` in the screen above. */
@OptIn(ExperimentalForeignApi::class)
private class WebViewHolder {
    var webView: WKWebView? = null
}

/**
 * Bar above the login web view, present in **release** builds too.
 *
 * iOS has no system back button, so without this the only way out of the web flow is the
 * edge-swipe gesture — which navigates web history when there is some and does nothing at
 * all when there isn't. That left two dead ends in release: a login stranded by a failure
 * that never commits a page (issue #449), and the sign-up / password-reset detours taken
 * from the login page, which are ordinary in-app navigations the user needs to come back
 * from. Back walks web history first and leaves the screen at the root, the same rule
 * Android's system back already follows (issue #308).
 *
 * Debug builds add the log export #427 introduced — the failures the exported trace
 * exists to capture happen *inside* this web view, so the share sheet has to be reachable
 * from here rather than only from the screens on either side of it.
 */
@Composable
private fun LoginToolbar(onBack: () -> Unit) {
    val shareText = rememberShareText()
    Surface {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal),
                ),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onBack) { Text("Back") }
            if (DebugFlags.debugToolsAvailable) {
                TextButton(onClick = { shareText(DebugLog.dump()) }) { Text("Share log") }
            }
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
@Composable
private fun LoginWebView(
    buildAuthUrl: () -> String,
    delegate: LoginNavigationDelegate,
    holder: WebViewHolder,
) {
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
                // history (issue #308), mirroring Android's system-back handling. It is
                // not an escape hatch: it is invisible, and it does nothing once history
                // runs out — which is exactly the state a failed login leaves behind.
                // The toolbar's Cancel (see LoginToolbar) covers that.
                allowsBackForwardNavigationGestures = true
                holder.webView = this
                val authUrl = buildAuthUrl()
                DebugLog.log("WebView loading ${describeUrlForLog(authUrl)}")
                loadRequest(NSURLRequest(uRL = NSURL(string = authUrl)!!))
            }
        },
        modifier = Modifier.fillMaxSize(),
    )
}

/** See `contentProcessReloads` in [LoginNavigationDelegate]. */
private const val MAX_CONTENT_PROCESS_RELOADS = 2

@OptIn(ExperimentalForeignApi::class)
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
        if (!isAuthRedirect(url)) {
            // Off-flow navigations: a user tap is a browse attempt and is silently
            // cancelled — the login WebView is not a Ravelry browser (issue #425;
            // Apple browsed it to the web messages composer and crashed the app from
            // its camera upload, the 2.1(a) rejection; bouncing taps out to Safari
            // was tried and felt broken mid-login). A SERVER-driven move off the flow
            // is different: the flow state behind the current page is dead (observed:
            // a stale authorize challenge bouncing through /account/login?prompt=1 to
            // the home page), so staying parked would strand the user — restart with
            // a fresh authorize URL instead, then give up loudly once the restart
            // budget is spent. Only main-frame navigations are policed; a null
            // targetFrame means a new-window attempt, which is treated as main-frame.
            // Subframe loads can't take the user anywhere, and cancelling them would
            // just break allowed pages.
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

    // Second line of defense, the twin of Android's onPageStarted (issue #447).
    // decidePolicyForNavigationAction does fire for POSTs on iOS, so this is belt and
    // braces rather than a known hole — but the failure it guards against is the one
    // that has actually been seen: the authorize challenge going stale and dumping the
    // user on the Ravelry home page inside the app (issue #434). didCommit is the last
    // moment before that page's content is on screen.
    // webView(_:didCommit:) and webView(_:didFinish:) below are distinct Objective-C
    // selectors, but both map to the Kotlin signature webView(WKWebView, WKNavigation?)
    // — parameter names do not distinguish overloads — so without this the two are a
    // compile error ("Conflicting overloads"). Only the macOS CI job catches it; Apple
    // targets cannot be compiled on the Linux dev box.
    @ObjCSignatureOverride
    override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
        val committed = webView.URL?.absoluteString ?: return
        if (isAllowedLoginNavigation(committed)) return
        DebugLog.log("WebView caught an off-flow page load: ${describeUrlForLog(committed)}")
        // stopLoading leaves whatever already rendered on screen, so blank it first.
        webView.stopLoading()
        webView.loadHTMLString("", baseURL = null)
        when (loginPageLoadDecision(committed, flowRestarts)) {
            LoginNavigationDecision.FAIL_LOGIN -> {
                DebugLog.log("login flow lost after $flowRestarts restarts — giving up")
                onAuthError(LOGIN_FLOW_LOST_MESSAGE)
            }
            else -> {
                flowRestarts++
                DebugLog.log("off-flow page load — restart #$flowRestarts")
                webView.loadRequest(NSURLRequest(uRL = NSURL(string = buildAuthUrl())!!))
            }
        }
    }

    // The iOS analog of Android's onPageFinished logging: with it, the exported trace
    // distinguishes "requested but never loaded" from "loaded and then went wrong".
    //
    // @ObjCSignatureOverride for the same reason didCommitNavigation above carries it,
    // and it has to be on BOTH: `didCommitNavigation:` and `didFinishNavigation:` are
    // distinct Objective-C selectors that map to the identical Kotlin signature
    // `(WKWebView, WKNavigation?)`, so with only one annotated the pair still reads as
    // conflicting overloads. Annotating just the newly added method is what left
    // `:composeApp:compileKotlinIosSimulatorArm64` red at this declaration.
    @ObjCSignatureOverride
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
