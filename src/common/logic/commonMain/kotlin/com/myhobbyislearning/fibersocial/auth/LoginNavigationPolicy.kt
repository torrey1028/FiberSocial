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
 * here too so correctness doesn't depend on their check ordering). The login
 * page's two auth-adjacent detours also stay usable in-app — password reset
 * (`/account/forgot`, query variants share the path) and Ravelry's
 * invitation-based sign-up (`/invitations` and its sub-steps) — because a dead
 * "forgot your password?" link strands people who need it most. Those detour
 * pages render the site's full footer, but its links are blocked like any other.
 *
 * Host and path are compared on a parsed URL, not by string prefix, so lookalike
 * hosts (`www.ravelry.com.evil.com`) don't pass.
 */
fun isAllowedLoginNavigation(url: String): Boolean {
    if (url.startsWith(RavelryAuthManager.REDIRECT_URI)) return true
    // WebViews use about:blank for internal empty documents (e.g. before the first
    // real load); blocking it can only break the flow, never open the site.
    if (url == "about:blank") return true
    val parsed = try {
        Url(url)
    } catch (e: Exception) {
        println("FiberSocial: isAllowedLoginNavigation could not parse ${url.take(120)}")
        return false
    }
    if (parsed.protocol != URLProtocol.HTTPS) return false
    if (parsed.host != "www.ravelry.com" && parsed.host != "ravelry.com") return false
    val path = parsed.encodedPath
    return path.startsWith("/oauth2/") ||
        path == "/account/login" ||
        path == "/account/forgot" ||
        path == "/consent" ||
        path == "/invitations" ||
        path.startsWith("/invitations/")
}

/** What the login WebView should do with a proposed main-frame navigation. */
enum class LoginNavigationDecision {
    /** On the auth flow — load it. */
    ALLOW,

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
    userInitiated -> LoginNavigationDecision.BLOCK
    restartsUsed < MAX_LOGIN_FLOW_RESTARTS -> LoginNavigationDecision.RESTART_FLOW
    else -> LoginNavigationDecision.FAIL_LOGIN
}
