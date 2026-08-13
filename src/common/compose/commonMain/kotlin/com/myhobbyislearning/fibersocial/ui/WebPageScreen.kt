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
 * [PlatformWebView] cannot leave Ravelry ([isRavelryWebUrl]), so the label cannot go
 * stale under the user's feet.
 *
 * @param url The page to open. Must be a Ravelry https URL; anything else renders blank
 *   because the platform navigation guards reject it.
 * @param title App-chrome title describing why the user is here, not the page's own.
 * @param onClose Close button, and Android's system back once the page's own history is
 *   exhausted.
 */
@Composable
fun WebPageScreen(
    url: String,
    title: String,
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
 * Navigation is confined to Ravelry's own host on both platforms ([isRavelryWebUrl]).
 * That is not decoration — an unconstrained in-app web view on ravelry.com is exactly
 * what produced the 2.1(a) crash rejection (issue #425): the reviewer browsed from a
 * Ravelry page into the web messages composer and killed the app from its image upload.
 * Here the user genuinely must be free to roam Ravelry (sign in, then walk their profile
 * editor), so the guard is host-level rather than the login view's path allowlist — but
 * off-site navigation still cannot happen.
 *
 * @param onBackExhausted Called when the user presses back with no page history left.
 *   Android only; iOS has no system back, and its edge-swipe gesture stops at the root.
 */
@Composable
expect fun PlatformWebView(
    url: String,
    onBackExhausted: () -> Unit,
    modifier: Modifier,
)
