package com.myhobbyislearning.fibersocial.feed

private val JS_UNICODE_ESCAPE_REGEX = Regex("""\\u([0-9a-fA-F]{4})""")

/**
 * Unescapes the HTML carried inside one of Ravelry's RJS (Rails-JavaScript) responses.
 *
 * Ravelry's own UI drives most in-page updates through Prototype.js
 * `Ajax.Request`, and Rails answers those with executable JavaScript rather than markup
 * — e.g. `Element.update("prepare_flag_contents", "<form ...>")`. The markup is
 * a JS *string literal*: angle brackets arrive as `\uXXXX` escapes, quotes as `\"`,
 * newlines as `\n`, slashes as `\/`. It has to be unescaped before it parses as HTML at
 * all (confirmed on-device for the states lookup, the first response of this shape this
 * app consumed).
 *
 * Plain HTML passes through unchanged in practice — the escape sequences this reverses
 * don't occur in Ravelry's server-rendered markup — so callers can hand a response body
 * here without first knowing which of the two shapes they got.
 */
internal fun unescapeRjsPayload(raw: String): String = raw
    .replace(JS_UNICODE_ESCAPE_REGEX) { it.groupValues[1].toInt(16).toChar().toString() }
    .replace("\\\"", "\"")
    .replace("\\'", "'")
    .replace("\\n", "\n")
    .replace("\\/", "/")
