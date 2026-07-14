package com.myhobbyislearning.fibersocial.login

import androidx.compose.runtime.Composable

/**
 * Platform login web view for the Ravelry OAuth flow. Loads [authUrl], watches for the
 * PKCE redirect, and reports the auth `code`, `state`, and the `_ravelry_session`
 * cookie (checking `www.ravelry.com` before `ravelry.com`) — the cookie the scrapers
 * need, which is why this is a real embedded web view and not a browser tab or an
 * `ASWebAuthenticationSession`-style API that hides its cookie jar.
 *
 * @param onBack Called to leave this screen entirely (dismissing back to the native
 *   login screen) once there's no further back history within the web flow itself —
 *   e.g. after backing out of a "sign up for an account" detour taken from the login
 *   page (issue #308).
 */
@Composable
expect fun WebViewLoginScreen(
    authUrl: String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
    onBack: () -> Unit,
)
