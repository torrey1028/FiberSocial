package com.myhobbyislearning.fibersocial.debug

/**
 * Opt-in switches for diagnostics that are too sensitive to leave on (issue #395).
 *
 * The only flag today is session-cookie logging. The Ravelry session cookie is a LIVE
 * CREDENTIAL — it carries `ravelrys_pocketses` (user id, session id, CSRF token) plus
 * `rsigned`/`raccount`, which together are enough to impersonate the signed-in user
 * against ravelry.com, including the CSRF token every web-protocol write in this app
 * depends on. It does not auto-refresh (issue #61), so a captured value stays usable
 * until it expires or the user logs out.
 *
 * Logging it was previously unconditional, which meant every login wrote a working
 * credential into logcat where an `adb` session, a pasted bug report, a screen recording,
 * or an assistant asked to "check logcat" would pick it up.
 *
 * ## Two independent gates
 *
 * A value is logged only when BOTH hold:
 * 1. [initDebugBuild] was called with `true` — i.e. this is a debug binary; and
 * 2. the developer explicitly turned the flag on in the debug panel this session.
 *
 * Both default to `false`, so **a release build can never log a cookie value regardless
 * of what else happens**, and neither can a debug build the developer hasn't opted in on.
 * Failing closed is deliberate: if [initDebugBuild] is never called (wrong startup order,
 * a new entry point that forgets), the answer is "don't log", not "log".
 *
 * ## Why the opt-in is in memory and not persisted
 *
 * It resets to off on every launch. A persisted switch is one a developer turns on once
 * to debug a login problem and then leaves on for months, which reintroduces exactly the
 * exposure this fixes. Re-enabling costs one tap, and the flag survives a sign-out /
 * sign-in cycle within a session, which is the whole workflow it exists for.
 */
object DebugFlags {

    private var debugBuild = false
    private var sessionCookieLogging = false

    /**
     * Records whether this is a debug binary. Call once at startup from the platform's own
     * debug signal — `BuildConfig.DEBUG` on Android, `Platform.isDebugBinary` on iOS — the
     * same source that already gates the debug panel.
     */
    fun initDebugBuild(isDebug: Boolean) {
        debugBuild = isDebug
        if (!isDebug) sessionCookieLogging = false
    }

    /** Whether the debug panel should offer the session-cookie switch at all. */
    val debugToolsAvailable: Boolean
        get() = debugBuild

    /** Whether a cookie VALUE may be written to the log right now. */
    val sessionCookieLoggingEnabled: Boolean
        get() = debugBuild && sessionCookieLogging

    /** Turns cookie-value logging on or off. Ignored outside a debug build. */
    fun setSessionCookieLogging(enabled: Boolean) {
        sessionCookieLogging = debugBuild && enabled
    }

    /** Test-only reset so cases can't leak state into each other. */
    internal fun resetForTest() {
        debugBuild = false
        sessionCookieLogging = false
    }
}

/**
 * Renders a session cookie for logging: the real value only when explicitly opted in,
 * otherwise a description that answers the question these log lines actually exist to
 * answer — "did the WebView hand us a session at all?" — without publishing the credential.
 *
 * Callers should always log through this rather than interpolating a cookie directly, so
 * there is exactly one place where a cookie can reach a log.
 */
fun describeSessionCookie(cookie: String): String =
    if (DebugFlags.sessionCookieLoggingEnabled) {
        cookie
    } else {
        "present=${cookie.isNotBlank()} (${cookie.length} chars, value hidden — enable in Debug Panel)"
    }
