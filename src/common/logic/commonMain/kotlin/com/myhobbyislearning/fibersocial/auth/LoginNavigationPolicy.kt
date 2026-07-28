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
 * blocked destinations open in the external browser instead (so "forgot password" /
 * "sign up" detours from the login page still work, just outside the app — this
 * supersedes issue #308's in-WebView sign-up detour).
 *
 * The allowlist is empirical, taken from a logged navigation trace of a real
 * sign-out/sign-in on 2026-07-28 (issue #425): the OAuth entry point
 * (`/oauth2/auth`), the account-login form (`/account/login`), and the authorize
 * page (`/consent?consent=<uuid>`), plus the app's own OAuth redirect (which the
 * platform navigation handlers intercept before this policy runs, but is allowed
 * here too so correctness doesn't depend on their check ordering).
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
    return path.startsWith("/oauth2/") || path == "/account/login" || path == "/consent"
}
