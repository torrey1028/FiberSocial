package com.myhobbyislearning.fibersocial.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitView
import com.myhobbyislearning.fibersocial.debug.DebugLog
import com.myhobbyislearning.fibersocial.debug.describeUrlForLog
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSURL
import platform.Foundation.NSURLRequest
import platform.WebKit.WKNavigation
import platform.WebKit.WKNavigationAction
import platform.WebKit.WKNavigationActionPolicy
import platform.WebKit.WKNavigationDelegateProtocol
import platform.WebKit.WKWebView
import platform.WebKit.WKWebViewConfiguration
import platform.darwin.NSObject

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun PlatformWebView(
    url: String,
    isAllowedNavigation: (String) -> Boolean,
    onBackExhausted: () -> Unit,
    modifier: Modifier,
) {
    // remember: WKWebView.navigationDelegate is weak, so the composition has to hold the
    // strong reference or the delegate is collected out from under the page.
    val delegate = remember { ConfinedNavigationDelegate(isAllowedNavigation) }
    UIKitView(
        factory = {
            // Default (persistent) data store, unlike the login web view's deliberately
            // non-persistent one: this screen wants whatever Ravelry session already
            // exists, so a return visit does not start from a logged-out page.
            WKWebView(
                frame = CGRectMake(0.0, 0.0, 0.0, 0.0),
                configuration = WKWebViewConfiguration().apply {
                    // Ravelry's login page carries an autoplaying video that would
                    // otherwise seize the screen in the fullscreen player (same reason as
                    // the login web view).
                    allowsInlineMediaPlayback = true
                },
            ).apply {
                navigationDelegate = delegate
                // iOS has no system back button, so the edge-swipe gesture is the only
                // in-page back there is. It stops at the root, which is why the app
                // chrome's Close button is what actually leaves the screen.
                allowsBackForwardNavigationGestures = true
                loadRequest(NSURLRequest(uRL = NSURL(string = url)!!))
            }
        },
        modifier = modifier,
    )
}

private class ConfinedNavigationDelegate(
    private val isAllowedNavigation: (String) -> Boolean,
) : NSObject(), WKNavigationDelegateProtocol {

    override fun webView(
        webView: WKWebView,
        decidePolicyForNavigationAction: WKNavigationAction,
        decisionHandler: (WKNavigationActionPolicy) -> Unit,
    ) {
        // Subframes can't take the user anywhere, and cancelling them breaks pages that
        // legitimately embed third-party content (Ravelry's forms embed a reCAPTCHA).
        val isMainFrame = decidePolicyForNavigationAction.targetFrame?.mainFrame ?: true
        val target = decidePolicyForNavigationAction.request.URL?.absoluteString ?: ""
        if (!isMainFrame || isAllowedNavigation(target)) {
            decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyAllow)
            return
        }
        DebugLog.log("WebPage blocked navigation to ${describeUrlForLog(target)}")
        decisionHandler(WKNavigationActionPolicy.WKNavigationActionPolicyCancel)
    }

    // Second line of defense, the twin of the login view's. Belt and braces here rather
    // than a known hole — decidePolicyForNavigationAction does fire for POSTs on iOS —
    // but this screen renders a login form and a deletion form, so "an unexpected page
    // can never be displayed" is worth enforcing at the last moment before it would be.
    override fun webView(webView: WKWebView, didCommitNavigation: WKNavigation?) {
        val committed = webView.URL?.absoluteString ?: return
        if (isAllowedNavigation(committed)) return
        DebugLog.log("WebPage caught an off-flow page load: ${describeUrlForLog(committed)}")
        webView.stopLoading()
        // Blank it rather than leaving whatever already rendered on screen.
        webView.loadHTMLString("", baseURL = null)
    }
}
