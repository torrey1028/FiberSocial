package com.myhobbyislearning.fibersocial.ui

import androidx.compose.runtime.Composable

/**
 * Returns a lambda that opens a web page in the most self-contained browser the platform
 * offers, and reports whether it opened.
 *
 * **iOS presents `SFSafariViewController` — it must not send the user to Safari.** App
 * Review rejected 0.3.2 (3002) under **Guideline 4 (Design)**: "the user is taken to the
 * default web browser to sign in or register for an account, which provides a poor user
 * experience", with the fix spelled out — "you may also choose to implement the Safari
 * View Controller API to display web content within the app". That is the whole reason
 * this abstraction exists rather than call sites reaching for `LocalUriHandler` directly.
 * `SFSafariViewController` renders inside the app with a Done button, and shows the URL
 * and certificate so the user can confirm they are typing credentials into a real
 * Ravelry page — Apple's stated rationale.
 *
 * Android keeps handing off to the system browser: no store guideline forces the change,
 * and unlike iOS it has a system back button, so the round trip is already cheap. Chrome
 * Custom Tabs is the closer analogue if that ever changes, and this seam is where it
 * would go — one actual, no call-site churn.
 *
 * **Not for the OAuth login flow.** That runs in a `WKWebView`/`WebView` the app owns,
 * because it has to police every navigation ([isAllowedLoginNavigation]) and read the
 * `_ravelry_session` cookie at the redirect. `SFSafariViewController` is a black box on
 * both counts — no navigation callbacks, no cookie access — which is also why
 * `ASWebAuthenticationSession` was rejected for it. This is for the hand-offs where the
 * app genuinely does not care what happens next: sign-up, password reset, and the
 * account-deletion page.
 *
 * @return true if the page was opened. **Never throws**, whatever the URL or the device.
 *   The guard lives here rather than at each call site because two of the three callers
 *   are places a throw is fatal: a Compose click handler, and a `WebViewClient` /
 *   `WKNavigationDelegate` callback — an exception escaping the latter terminates a
 *   Kotlin/Native app outright. Android's `openUri` really does throw on a device with no
 *   app able to handle an https `ACTION_VIEW` (`AndroidUriHandler` rethrows
 *   `ActivityNotFoundException` as `IllegalArgumentException`), which is not theoretical:
 *   it crashed the exact flow App Review is instructed to walk for guideline 5.1.1(v).
 *   Callers that must know — account deletion, which signs out only on a real hand-off —
 *   read the result; the login detours ignore it, since a failure just leaves the user on
 *   the login form.
 */
@Composable
expect fun rememberOpenWebPage(): (url: String) -> Boolean
