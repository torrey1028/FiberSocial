@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.login

import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.viewinterop.AndroidView
import com.myhobbyislearning.fibersocial.auth.AuthCallback
import com.myhobbyislearning.fibersocial.auth.MALFORMED_AUTH_CALLBACK_MESSAGE
import com.myhobbyislearning.fibersocial.auth.RavelryAuthManager
import com.myhobbyislearning.fibersocial.auth.LOGIN_FLOW_LOST_MESSAGE
import com.myhobbyislearning.fibersocial.auth.LoginNavigationDecision
import com.myhobbyislearning.fibersocial.auth.authFailureMessage
import com.myhobbyislearning.fibersocial.auth.describeAuthFailureForLog
import com.myhobbyislearning.fibersocial.auth.isAllowedLoginNavigation
import com.myhobbyislearning.fibersocial.auth.isAuthRedirect
import com.myhobbyislearning.fibersocial.auth.isSignUpEmailSentPage
import com.myhobbyislearning.fibersocial.auth.loginPageLoadDecision
import com.myhobbyislearning.fibersocial.auth.loginNavigationDecision
import com.myhobbyislearning.fibersocial.auth.parseAuthCallback
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.describeSessionCookie
import com.myhobbyislearning.fibersocial.debug.describeUrlForLog

@Composable
actual fun WebViewLoginScreen(
    buildAuthUrl: () -> String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    onAuthError: (message: String) -> Unit,
    onBack: () -> Unit,
) {
    // Holds the created WebView so BackHandler below can check/drive its own history —
    // AndroidView's factory runs once the underlying view exists, which BackHandler
    // (evaluated on every composition) can't reach any other way.
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    // Bumped to reset the flow after Ravelry emails a signup link. Recreating the WebView
    // is what actually clears the back stack — clearHistory() is easy to get wrong around
    // an in-flight load, and a stale entry here means swiping back lands on the dead-end
    // "check your email" page the reset exists to escape. A fresh view also re-runs the
    // cookie clear in the factory, so the restarted login starts genuinely logged out.
    var loginFlowKey by remember { mutableStateOf(0) }
    var showEmailSentNotice by rememberSaveable { mutableStateOf(false) }
    // System back navigates the WEB flow's own history first — e.g. backing out of the
    // sign-up or password-reset detour taken from the login page — and only leaves the
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
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
        if (showEmailSentNotice) {
            SignUpEmailSentBanner(onDismiss = { showEmailSentNotice = false })
        }
        key(loginFlowKey) {
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
                    // Restarts performed by this screen; caps the recovery loop.
                    private var flowRestarts = 0

                    override fun shouldOverrideUrlLoading(
                        view: WebView,
                        request: WebResourceRequest,
                    ): Boolean {
                        val url = request.url.toString()
                        DebugLog.log("WebView navigating to ${describeUrlForLog(url)}")
                        if (isAuthRedirect(url)) {
                            // Every branch below must call something. Returning true
                            // cancels the navigation, so a silent return strands the user
                            // on the authorize page with no way forward (issue #394).
                            val callback = parseAuthCallback(url)
                            if (callback is AuthCallback.Failure) {
                                DebugLog.log(describeAuthFailureForLog(callback))
                                onAuthError(authFailureMessage(callback))
                                return true
                            }
                            if (callback !is AuthCallback.Success) {
                                DebugLog.log("OAuth redirect carried neither code nor error")
                                onAuthError(MALFORMED_AUTH_CALLBACK_MESSAGE)
                                return true
                            }
                            val code = callback.code
                            val state = callback.state
                            val cm = CookieManager.getInstance()
                            val wwwCookie = cm.getCookie("https://www.ravelry.com") ?: ""
                            val rootCookie = cm.getCookie("https://ravelry.com") ?: ""
                            DebugLog.log("OAuth complete")
                            // Never interpolate a cookie directly — describeSessionCookie
                            // hides the value unless a debug build opted in (issue #395).
                            DebugLog.log("www.ravelry.com cookie ${describeSessionCookie(wwwCookie)}")
                            DebugLog.log("ravelry.com cookie ${describeSessionCookie(rootCookie)}")
                            val cookie = wwwCookie.ifEmpty { rootCookie }
                            onAuthComplete(code, state, cookie)
                            return true
                        }
                        // Off-flow navigations: a user tap is a browse attempt and is
                        // silently cancelled — the login WebView is not a Ravelry
                        // browser (issue #425; Apple browsed it to the web messages
                        // composer and crashed the app from its camera upload; bouncing
                        // taps out to the real browser was tried and felt broken
                        // mid-login). A SERVER-driven move off the flow is different:
                        // the flow state behind the current page is dead (observed:
                        // a stale authorize challenge bouncing to the home page), so
                        // staying parked would strand the user — restart with a fresh
                        // authorize URL instead, then give up loudly once the restart
                        // budget is spent. Only the main frame is policed: subframe
                        // loads can't take the user anywhere, and cancelling them
                        // would just break allowed pages.
                        if (!request.isForMainFrame) return false
                        val userInitiated = request.hasGesture() && !request.isRedirect
                        return when (loginNavigationDecision(url, userInitiated, flowRestarts)) {
                            LoginNavigationDecision.ALLOW -> false
                            LoginNavigationDecision.BLOCK -> {
                                DebugLog.log("WebView cancelled non-login navigation to ${describeUrlForLog(url)}")
                                true
                            }
                            LoginNavigationDecision.RESTART_FLOW -> {
                                flowRestarts++
                                DebugLog.log("login flow went off the rails (server redirect) — restart #$flowRestarts")
                                view.loadUrl(buildAuthUrl())
                                true
                            }
                            LoginNavigationDecision.FAIL_LOGIN -> {
                                DebugLog.log("login flow lost after $flowRestarts restarts — giving up")
                                onAuthError(LOGIN_FLOW_LOST_MESSAGE)
                                true
                            }
                        }
                    }

                    override fun onReceivedError(
                        view: WebView,
                        request: WebResourceRequest,
                        error: WebResourceError,
                    ) {
                        DebugLog.log(
                            "WebView error ${error.errorCode} ${error.description} " +
                                "url=${describeUrlForLog(request.url.toString())}",
                        )
                    }

                    // Second line of defense (issue #447). shouldOverrideUrlLoading is
                    // documented as NOT invoked for POST requests, so a form submission
                    // from an allowed page can move the main frame anywhere without the
                    // policy ever running — and the observed result is the whole Ravelry
                    // home page rendered inside the login WebView when the authorize
                    // challenge goes stale (issue #434). Re-check here, where every load
                    // passes, and stop anything off-flow before it can be shown.
                    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                        // The sign-up flow's end: Ravelry has emailed the signup link and
                        // this page can go no further. Detected as it LOADS, not as it
                        // navigates — the POST that lands here is what sends the email, so
                        // cancelling the navigation would mean no email at all.
                        if (isSignUpEmailSentPage(url)) {
                            DebugLog.log("sign-up link emailed — returning to the login form")
                            view.stopLoading()
                            showEmailSentNotice = true
                            loginFlowKey++
                            return
                        }
                        if (isAllowedLoginNavigation(url)) return
                        DebugLog.log("WebView caught an off-flow page load: ${describeUrlForLog(url)}")
                        // stopLoading alone would leave whatever already rendered on
                        // screen, so blank it before deciding what to do next.
                        view.stopLoading()
                        view.loadUrl("about:blank")
                        when (loginPageLoadDecision(url, flowRestarts)) {
                            LoginNavigationDecision.FAIL_LOGIN -> {
                                DebugLog.log("login flow lost after $flowRestarts restarts — giving up")
                                onAuthError(LOGIN_FLOW_LOST_MESSAGE)
                            }
                            else -> {
                                flowRestarts++
                                DebugLog.log("off-flow page load — restart #$flowRestarts")
                                view.loadUrl(buildAuthUrl())
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView, url: String) {
                        DebugLog.log("WebView page loaded: ${describeUrlForLog(url)}")
                    }
                }
                val authUrl = buildAuthUrl()
                DebugLog.log("WebView loading ${describeUrlForLog(authUrl)}")
                loadUrl(authUrl)
                webViewRef = this
            }
        },
            )
        }
    }
}
