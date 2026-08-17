package com.myhobbyislearning.fibersocial.auth

/**
 * Splits an RFC 6265 `Cookie` request header — `a=1; b=2` — into its name/value pairs.
 *
 * Exists so the in-app deletion web view can be seeded with the Ravelry session the app
 * already holds ([AuthToken.sessionCookie]), instead of asking the user to sign in to
 * Ravelry a second time to delete their account. On Android that never came up: the login
 * WebView and the deletion WebView share the app-global `CookieManager`, so the session is
 * simply already there. iOS has no such luck — the login `WKWebView` deliberately uses a
 * **non-persistent** data store so every login starts genuinely logged out, which means
 * the captured cookie string is the only copy that outlives it.
 *
 * Parsing lives here rather than in the iOS actual so it can be tested on the JVM, and so
 * both platforms can only ever disagree about *what they do* with the pairs, not about how
 * the header is read.
 *
 * Malformed segments are dropped rather than guessed at: a nameless or valueless pair
 * cannot be turned into a usable cookie, and inventing one would send Ravelry a header it
 * never issued. Values are left exactly as they arrived — they are already in the encoding
 * Ravelry set them with, and re-encoding is how a session silently stops matching.
 */
fun parseCookieHeader(header: String?): List<Pair<String, String>> =
    header.orEmpty()
        .split(';')
        .mapNotNull { segment ->
            val trimmed = segment.trim()
            // substringBefore would map a valueless "foo" to ("foo" -> "foo").
            val separator = trimmed.indexOf('=')
            if (separator <= 0) return@mapNotNull null
            val name = trimmed.substring(0, separator).trim()
            // Not trimmed: a cookie value may legitimately be empty, and only the leading
            // space after "; " is separator noise, which the segment trim already removed.
            val value = trimmed.substring(separator + 1)
            if (name.isEmpty()) null else name to value
        }
