package com.myhobbyislearning.fibersocial.auth

import io.ktor.http.Url

/**
 * What the OAuth redirect actually carried (issue #394).
 *
 * RFC 6749 §4.1.2 gives the authorization server two ways to come back: a `code` on
 * success, or an `error` (plus optional `error_description`) on failure. The login web
 * view previously only looked for `code` and silently swallowed the navigation when it
 * was absent, so every server-side failure left the user staring at a frozen authorize
 * page with no way forward.
 */
sealed interface AuthCallback {

    /** The authorization server issued a code. */
    data class Success(val code: String, val state: String?) : AuthCallback

    /**
     * The authorization server refused. [error] is the RFC 6749 §4.1.2.1 code
     * (`access_denied`, `invalid_scope`, `server_error`, …); [description] is the
     * server's optional human-readable elaboration.
     */
    data class Failure(val error: String, val description: String?) : AuthCallback

    /**
     * A redirect carrying neither — malformed, or truncated. Treated as a failure by
     * callers rather than ignored, since ignoring it is the bug this type exists to fix.
     */
    data object Malformed : AuthCallback
}

/**
 * Parses an OAuth redirect. Shared rather than done per-platform because the previous
 * per-platform handling drifted into the same bug twice: Android returned early on a
 * missing `code`, iOS did the same, and neither looked at `error`.
 *
 * Only call this once the URL is known to be the redirect URI.
 */
fun parseAuthCallback(url: String): AuthCallback {
    val params = runCatching { Url(url).parameters }.getOrNull()
        ?: return AuthCallback.Malformed

    // Error first: a server that reports a failure must never be mistaken for success
    // because some other parameter happened to be present.
    params["error"]?.takeIf { it.isNotBlank() }?.let { error ->
        return AuthCallback.Failure(
            error = error,
            description = params["error_description"]?.takeIf { it.isNotBlank() },
        )
    }
    params["code"]?.takeIf { it.isNotBlank() }?.let { code ->
        return AuthCallback.Success(code = code, state = params["state"])
    }
    return AuthCallback.Malformed
}

/**
 * User-facing copy for a failed authorization.
 *
 * `access_denied` is deliberately worded as a neutral outcome rather than an error: it
 * means the user tapped Deny, which is a choice they made, not a fault. Everything else
 * prefers the server's own `error_description`, which is usually more specific than
 * anything we could infer from the code alone, and falls back to the raw code so a
 * failure is never reported as an empty or generic message.
 */
fun authFailureMessage(failure: AuthCallback.Failure): String = when (failure.error) {
    "access_denied" -> "Sign-in was cancelled."
    else -> failure.description
        ?: "Ravelry couldn't complete sign-in (${failure.error})."
}

/** Copy for a redirect that carried neither a code nor an error. */
const val MALFORMED_AUTH_CALLBACK_MESSAGE: String =
    "Ravelry's sign-in response couldn't be read. Please try again."
