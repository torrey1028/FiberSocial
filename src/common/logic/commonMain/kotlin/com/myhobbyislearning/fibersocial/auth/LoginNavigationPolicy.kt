package com.myhobbyislearning.fibersocial.auth

import io.ktor.http.URLProtocol
import io.ktor.http.Url

/**
 * Whether the login WebView may render [url] in its main frame (issue #425).
 *
 * The login WebView exists for exactly one job: walking the OAuth flow. Ravelry's
 * login and authorize pages render the site's full navigation header, and with an
 * allow-everything policy one tap on it escapes into the complete, logged-in Ravelry
 * website hosted inside the app — which is precisely what Apple's reviewer did
 * (rejection 2.1(a)): they browsed to the web messages inbox, opened the composer's
 * image upload, and the file-input sheet's "Take Photo or Video" option crashed the
 * app. Confining the WebView to the auth flow removes that entire class of problem;
 * blocked navigations are silently cancelled by the platform handlers (bouncing
 * them to the external browser was tried first and felt broken mid-login).
 *
 * The allowlist is empirical, taken from a logged navigation trace of a real
 * sign-out/sign-in on 2026-07-28 (issue #425): the OAuth entry point
 * (`/oauth2/auth`), the account-login form (`/account/login`), and the authorize
 * page (`/consent?consent=<uuid>`), plus the app's own OAuth redirect (which the
 * platform navigation handlers intercept before this policy runs, but is allowed
 * here too so correctness doesn't depend on their check ordering).
 *
 * That is the whole OAuth flow and nothing else. The login page's two detour links —
 * "Sign Up" and "I forgot" — are deliberately NOT here; see [isExternalLoginDetour].
 *
 * Host and path are compared on a parsed URL, not by string prefix, so lookalike
 * hosts (`www.ravelry.com.evil.com`) don't pass. The host compares case-insensitively
 * (hosts are; a server redirect carrying an uppercase host must not read as off-flow),
 * and paths still containing dot-segments or %-escapes are rejected outright: Ktor's
 * `Url` leaves both untouched in `encodedPath`, so `/oauth2/..%2fmessages` would
 * otherwise ride the `/oauth2/` prefix into whatever Ravelry's Rails router unescapes
 * it to. No allowlisted path needs an escape, so rejecting them costs nothing.
 */
fun isAllowedLoginNavigation(url: String): Boolean {
    if (isAuthRedirect(url)) return true
    // WebViews use about:blank for internal empty documents (e.g. before the first
    // real load); blocking it can only break the flow, never open the site.
    if (url == "about:blank") return true
    val path = ravelrySafePath(url) ?: return false
    return path.startsWith("/oauth2/") ||
        path == "/account/login" ||
        path == "/consent"
}

/**
 * Whether [url] is one of the login page's two detour links — Ravelry's invitation-based
 * sign-up (`/invitations` and its sub-steps, the "Sign Up" button) or password reset
 * (`/account/forgot`, the "I forgot" link; query variants share the path).
 *
 * These get handed to the system browser rather than loaded in place, because **neither
 * can finish inside the app**. Both end the same way: Ravelry emails a link, and that
 * link opens in the real browser. `/invitations` even says so — it is "Step 1 of 2 — get
 * a signup link". Running step 1 in the login WebView splits one flow across two cookie
 * jars — the WebView's is deliberately non-persistent, a fresh jar per login so the
 * captured `_ravelry_session` is the only copy that survives — and parks the user on a
 * "check your email" page inside a screen whose only purpose is logging in. Opening them
 * in the browser puts both steps in the same place with the same session, and leaves the
 * login screen sitting on the login form for when the user comes back with an account or
 * a new password.
 *
 * This is not a reversal of the #425 lockdown: an off-flow *browse* attempt is still
 * cancelled in place (bouncing those to the browser was tried and felt broken mid-login).
 * These two are the navigations a user takes knowing they are leaving the login attempt
 * behind, which is exactly when an external hand-off reads as intended rather than as
 * being thrown out of the app.
 */
fun isExternalLoginDetour(url: String): Boolean {
    val path = ravelrySafePath(url) ?: return false
    return path == "/invitations" ||
        path.startsWith("/invitations/") ||
        path == "/account/forgot"
}

/**
 * The `encodedPath` of [url] if it is an https URL on Ravelry's own host whose path is
 * free of the escapes a prefix check could be walked through, else null. Shared by the
 * allowlist and [isExternalLoginDetour] so neither can drift into a weaker host check.
 */
private fun ravelrySafePath(url: String): String? {
    val parsed = runCatching { Url(url) }.getOrElse {
        println("FiberSocial: login navigation policy could not parse ${url.take(120)}")
        return null
    }
    if (parsed.protocol != URLProtocol.HTTPS) return null
    val host = parsed.host.lowercase()
    if (host != "www.ravelry.com" && host != "ravelry.com") return null
    val path = parsed.encodedPath
    if (path.contains("..") || path.contains('%') || path.contains("//") || path.contains('\\')) {
        return null
    }
    return path
}

/**
 * Whether [url] is the app's own OAuth redirect ([RavelryAuthManager.REDIRECT_URI]),
 * with a delimiter boundary: the URI itself, or the URI followed by a query or
 * fragment. A bare `startsWith` would also match `fibersocial://auth/callbackevil`,
 * handing a page-controlled lookalike URL to the auth-code capture path — the `state`
 * check would still reject it (issue #149), but the prefix match shouldn't be the
 * only thing standing in front of that defense. Shared by this policy and both
 * platform navigation handlers so the boundary rule can't drift per-platform.
 */
fun isAuthRedirect(url: String): Boolean {
    if (!url.startsWith(RavelryAuthManager.REDIRECT_URI)) return false
    val rest = url.substring(RavelryAuthManager.REDIRECT_URI.length)
    return rest.isEmpty() || rest[0] == '?' || rest[0] == '#'
}

/** What the login WebView should do with a proposed main-frame navigation. */
enum class LoginNavigationDecision {
    /** On the auth flow — load it. */
    ALLOW,

    /** A sign-up / password-reset detour — cancel it and hand the URL to the browser. */
    OPEN_EXTERNALLY,

    /** Off the flow by the user's own tap — cancel it and stay put. */
    BLOCK,

    /** Off the flow by a server redirect — cancel it and load a fresh authorize URL. */
    RESTART_FLOW,

    /** Off the flow by a server redirect, but the restart budget is spent — give up. */
    FAIL_LOGIN,
}

/**
 * Restarts are capped so a server that keeps breaking the flow produces one clear
 * failure instead of an invisible redirect-restart loop. Two is deliberate: restart #1
 * is the fix for the observed staleness dead-end (below) and should succeed — the
 * session cookie from the broken attempt is already in the jar, so the restarted flow
 * goes straight to a fresh consent page; #2 is margin for a second stale bounce.
 */
const val MAX_LOGIN_FLOW_RESTARTS = 2

/** Shown when the flow keeps collapsing after [MAX_LOGIN_FLOW_RESTARTS] restarts. */
const val LOGIN_FLOW_LOST_MESSAGE = "Ravelry lost track of the sign-in. Please try again."

/**
 * Decides what to do with a main-frame navigation the login WebView is about to make.
 *
 * The reason off-flow navigations split on [userInitiated]: a user tap on the site
 * header is a browse attempt — cancel it and stay parked (issue #425). A navigation the
 * user did NOT initiate is the server steering the flow somewhere it can't recover
 * from, and staying parked would strand the user on a page whose flow state is dead.
 * The observed case (on-device trace, 2026-07-28): accepting the authorize dialog after
 * the login step took ~5½ minutes bounced to `/account/login?prompt=1&return_to=
 * /consent?...` — the challenge had gone stale — and Ravelry's login page, seeing an
 * already-active session, dropped the `return_to` and redirected to the home page.
 * Restarting with a freshly built authorize URL recovers in seconds: the session cookie
 * survives, so the restarted flow lands directly on a fresh consent page.
 *
 * @param restartsUsed How many restarts this login screen has already performed.
 */
fun loginNavigationDecision(
    url: String,
    userInitiated: Boolean,
    restartsUsed: Int,
): LoginNavigationDecision = when {
    isAllowedLoginNavigation(url) -> LoginNavigationDecision.ALLOW
    // Ahead of the userInitiated split on purpose: a detour is a hand-off whichever way
    // it was reached. Ravelry moves `/invitations` → `/invitations/ask` itself after the
    // form POST, and reading that as an off-flow server redirect would restart the login
    // flow underneath someone in the middle of creating an account.
    isExternalLoginDetour(url) -> LoginNavigationDecision.OPEN_EXTERNALLY
    userInitiated -> LoginNavigationDecision.BLOCK
    restartsUsed < MAX_LOGIN_FLOW_RESTARTS -> LoginNavigationDecision.RESTART_FLOW
    else -> LoginNavigationDecision.FAIL_LOGIN
}
