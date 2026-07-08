package com.autom8ed.fibersocial.login

import androidx.compose.runtime.Composable

/**
 * Platform login web view for the Ravelry OAuth flow. Loads [authUrl], watches for the
 * PKCE redirect, and reports the auth `code`, `state`, and the `_ravelry_session`
 * cookie (checking `www.ravelry.com` before `ravelry.com`) — the cookie the scrapers
 * need, which is why this is a real embedded web view and not a browser tab or an
 * `ASWebAuthenticationSession`-style API that hides its cookie jar.
 */
@Composable
expect fun WebViewLoginScreen(
    authUrl: String,
    onAuthComplete: (code: String, state: String?, sessionCookie: String) -> Unit,
)
