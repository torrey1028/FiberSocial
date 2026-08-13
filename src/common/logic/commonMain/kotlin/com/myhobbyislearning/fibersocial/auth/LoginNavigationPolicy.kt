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
 * The login page's two auth-adjacent detours are here too, and load in place: password
 * reset (`/account/forgot`, query variants share the path) and Ravelry's invitation-based
 * sign-up (`/invitations` and its sub-steps). A dead "forgot your password?" link strands
 * the people who need it most, and App Review rejected 0.3.2 (3002) under guideline 4 for
 * sending sign-up out to the default browser instead — "please revise the app to enable
 * users to sign in or register for an account in the app" (issue #481). Those two pages
 * render the site's full footer, but its links are blocked like any other.
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
        (path == "/account/login" && !isOffFlowLoginPage(url)) ||
        path == "/account/forgot" ||
        path == "/consent" ||
        path == "/invitations" ||
        path.startsWith("/invitations/")
}

/**
 * Whether [url] is a Ravelry login page that would sign the user in **somewhere other
 * than this OAuth flow** — a login form whose `return_to` points away from it.
 *
 * The dead end this closes, from an on-device trace (2026-08-13): from the login page the
 * user taps "Sign Up", lands on `/invitations`, and taps the *"sign in"* link in that
 * page's own header. That link is `/account/login?return_to=/invitations`. Matching
 * `/account/login` on path alone let it through, so the user signed in, Ravelry honoured
 * `return_to`, and they were returned to the sign-up page — signed in to the website, but
 * with the OAuth flow abandoned. No consent page, no code, no way forward, and nothing
 * looked broken enough to explain why.
 *
 * The distinguishing signal is the query, and it is empirical: the real OAuth login page
 * carries **no** `return_to` (the consent id lives in the session, see the same trace),
 * while the stale-challenge bounce carries one pointing back into the flow
 * (`/account/login?prompt=1&return_to=/consent?...`, issue #434). So a `return_to` aimed
 * anywhere else means this login cannot finish the flow.
 *
 * Callers treat it as a restart rather than a block, because the user asking to sign in
 * is a reasonable thing to want — they should land on a login page that actually works,
 * not have the tap silently swallowed.
 */
fun isOffFlowLoginPage(url: String): Boolean {
    if (ravelrySafePath(url) != "/account/login") return false
    val parsed = runCatching { Url(url) }.getOrNull() ?: return false
    val returnTo = parsed.parameters["return_to"] ?: return false
    return !returnTo.pointsInsideAuthFlow()
}

/**
 * Whether this `return_to` value addresses the OAuth flow, matched on a delimiter
 * boundary rather than a bare prefix: `/consent-evil` starts with `/consent` but is a
 * different Rails route entirely, and treating it as in-flow would hand back exactly the
 * escape [isOffFlowLoginPage] exists to close. Same rule, same reason, as [isAuthRedirect].
 */
private fun String.pointsInsideAuthFlow(): Boolean {
    for (route in listOf("/consent", "/oauth2")) {
        if (!startsWith(route)) continue
        val rest = substring(route.length)
        if (rest.isEmpty() || rest[0] == '/' || rest[0] == '?' || rest[0] == '#') return true
    }
    return false
}

/**
 * The `encodedPath` of [url] if it is an https URL on Ravelry's own host whose path is
 * free of the escapes a prefix check could be walked through, else null. Shared by the
 * allowlist so a prefix check cannot be walked through a dot-segment or %-escape.
 */
internal fun ravelrySafePath(url: String): String? {
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
 * What to do about a page the login WebView has already *started loading* — the second
 * line of defense behind [loginNavigationDecision] (issue #447).
 *
 * The navigation hooks are not a complete filter. Android documents
 * `shouldOverrideUrlLoading` as **not invoked for POST requests**, so a form submission
 * from an allowed page can move the main frame anywhere with the policy never consulted,
 * and the docs list further non-invocation cases. The observed symptom is the one that
 * matters: take long enough over the login form and the authorize challenge goes stale,
 * Ravelry drops the `return_to`, and the user lands on the **Ravelry home page inside the
 * app** (issue #434) — the whole logged-in website, which is the escape class #425 exists
 * to close, and what App Review crashed the app through under 2.1(a).
 *
 * So every page load is re-checked as it begins, and anything off the flow is stopped
 * before it can be shown. [userInitiated] is deliberately not a parameter: by this point
 * the navigation is happening regardless of who started it, and "cancel and stay put"
 * (what a user tap gets at decide-time) is not available — the page is already loading,
 * so the only ways out are a fresh authorize URL or giving up.
 */
fun loginPageLoadDecision(url: String, restartsUsed: Int): LoginNavigationDecision =
    loginNavigationDecision(url, userInitiated = false, restartsUsed = restartsUsed)

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
    // Ahead of the userInitiated split: a login page pointed away from this flow is a
    // dead end whoever asked for it, and the user tapping "sign in" wants a login page
    // that works — not a silently swallowed tap. Restarting gives them exactly that.
    isOffFlowLoginPage(url) ->
        if (restartsUsed < MAX_LOGIN_FLOW_RESTARTS) {
            LoginNavigationDecision.RESTART_FLOW
        } else {
            LoginNavigationDecision.FAIL_LOGIN
        }
    userInitiated -> LoginNavigationDecision.BLOCK
    restartsUsed < MAX_LOGIN_FLOW_RESTARTS -> LoginNavigationDecision.RESTART_FLOW
    else -> LoginNavigationDecision.FAIL_LOGIN
}
