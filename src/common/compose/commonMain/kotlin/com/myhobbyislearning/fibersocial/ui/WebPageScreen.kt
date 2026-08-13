@file:OptIn(ExperimentalMaterial3Api::class)

package com.myhobbyislearning.fibersocial.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * A Ravelry web page shown **inside** the app, with app chrome around it.
 *
 * Exists because some things Ravelry offers have no API behind them — account deletion is
 * the one this was built for (issue #478) — and App Review rejected 0.3.2 (3002) under
 * guideline 4 for sending the user to the default browser for them: "please revise the
 * app to enable users to sign in or register for an account in the app" (issue #481).
 * A plain platform web view rather than `SFSafariViewController` on iOS: Apple offers
 * that as an option ("you may also choose to"), not a requirement, and a web view keeps
 * the screen identical on both platforms, which is the whole point of this codebase.
 *
 * The host is shown under the title on purpose. Apple's stated reason for suggesting
 * `SFSafariViewController` is that it lets "customers verify the webpage URL and SSL
 * certificate to confirm they are entering their sign in credentials into a legitimate
 * page" — and the page this carries really does ask for a Ravelry password. Naming the
 * host in the app's own chrome gives the user that same check. It is honest, too:
 * [PlatformWebView] renders nothing outside [isAllowedNavigation], so the label cannot go
 * stale under the user's feet.
 *
 * @param url The page to open.
 * @param title App-chrome title describing why the user is here, not the page's own.
 * @param isAllowedNavigation The only pages this view may render. Passed in rather than
 *   hardcoded so the policy lives in commonMain next to the login one, and so each caller
 *   states its own flow — see `isAllowedAccountDeletionNavigation`.
 * @param onClose Close button, and Android's system back once the page's own history is
 *   exhausted.
 */
@Composable
fun WebPageScreen(
    url: String,
    title: String,
    isAllowedNavigation: (String) -> Boolean,
    onClose: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(
                            text = webPageHost(url),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                },
            )
        },
    ) { padding ->
        PlatformWebView(
            url = url,
            isAllowedNavigation = isAllowedNavigation,
            onBackExhausted = onClose,
            modifier = Modifier.fillMaxSize().padding(padding),
        )
    }
}

/**
 * Host of [url] for the chrome's subtitle — `www.ravelry.com`, not the full path, which
 * would be too long to read and would change under the user as they navigate.
 */
internal fun webPageHost(url: String): String =
    url.substringAfter("://", "").substringBefore('/').ifEmpty { url }

/**
 * The platform web view: Android `WebView`, iOS `WKWebView`.
 *
 * Navigation is confined to [isAllowedNavigation] on both platforms, and the guard is
 * applied twice: once when a navigation is proposed, and again when a page actually
 * begins loading. The second check is not redundant — Android's
 * `shouldOverrideUrlLoading` is documented as not firing for POST requests (issue #447),
 * so a form submission could otherwise put an unexpected page on screen with the policy
 * never consulted. An unconstrained in-app web view on ravelry.com is exactly what
 * produced the 2.1(a) crash rejection (issue #425): the reviewer browsed from a page we
 * had let them onto into the web messages composer, and its image upload killed the app.
 *
 * @param onBackExhausted Called when the user presses back with no page history left.
 *   Android only; iOS has no system back, and its edge-swipe gesture stops at the root.
 */
@Composable
expect fun PlatformWebView(
    url: String,
    isAllowedNavigation: (String) -> Boolean,
    onBackExhausted: () -> Unit,
    modifier: Modifier,
)
