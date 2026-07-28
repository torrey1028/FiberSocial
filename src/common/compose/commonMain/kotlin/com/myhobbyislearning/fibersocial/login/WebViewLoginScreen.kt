package com.myhobbyislearning.fibersocial.login

import androidx.compose.runtime.Composable

/**
 * Platform login web view for the Ravelry OAuth flow. Loads the authorize URL, watches
 * for the PKCE redirect, and reports the auth `code`, `state`, and the
 * `_ravelry_session` cookie (checking `www.ravelry.com` before `ravelry.com`) — the
 * cookie the scrapers need, which is why this is a real embedded web view and not a
 * browser tab or an `ASWebAuthenticationSession`-style API that hides its cookie jar.
 *
 * @param buildAuthUrl Mints an authorize URL (rotating the PKCE verifier and `state`).
 *   A supplier rather than a pre-built string because the screen may need more than
 *   one: when the server steers the flow off the auth pages without a user tap — the
 *   observed stale-challenge dead-end where accepting the authorize dialog bounces
 *   through `/account/login?prompt=1` onto the home page — the WebView recovers by
 *   loading a freshly minted URL instead of stranding the user
 *   (see `loginNavigationDecision` in the logic module's auth package).
 * @param onAuthError Called when the redirect carries an OAuth failure instead of a code
 *   (RFC 6749 §4.1.2.1 — `access_denied`, `invalid_scope`, `server_error`, …), is
 *   otherwise unreadable, or the flow collapsed `MAX_LOGIN_FLOW_RESTARTS` times.
 *   Callers must leave this screen and surface the message; before issue #394 nothing
 *   handled this case and the user was left on a frozen authorize page. The most common
 *   trigger is not an exotic failure but the user tapping Deny.
 * @param onBack Called to leave this screen entirely (dismissing back to the native
 *   login screen) once there's no further back history within the web flow itself —
 *   e.g. after backing out of a "sign up for an account" detour taken from the login
 *   page (issue #308).
 */
@Composable
expect fun WebViewLoginScreen(
    buildAuthUrl: () -> String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    onAuthError: (message: String) -> Unit,
    onBack: () -> Unit,
)
