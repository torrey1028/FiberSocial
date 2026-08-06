package com.myhobbyislearning.fibersocial.feed

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * Parses Ravelry's "report a post" flag form out of a `/forum_posts/{id}/prepare_flag`
 * response (see [FlagPostForm]).
 *
 * The response is whatever Ravelry's own report link receives — an RJS payload that
 * injects the form's markup into the page — so the body is unescaped
 * ([unescapeRjsPayload]) before it's parsed as HTML.
 *
 * The parse is deliberately shape-agnostic: it takes the *form's own* action, hidden
 * inputs and control names rather than expecting particular Rails field names, because
 * the real markup is login-walled and can't be captured here. Guessing those names is
 * exactly what issue #467 was — the shipped code POSTed invented field names at an
 * invented URL. Anything the form doesn't offer (an escalate checkbox, a comment box)
 * comes back null rather than being assumed; if no form on the page has a
 * reason-shaped control at all, [parse] returns null so the caller can fall back to the
 * other reporting channels instead of silently sending nothing.
 */
object FlagPostFormParser {

    private val REASON_NAME_REGEX = Regex("reason|code|category|why", RegexOption.IGNORE_CASE)
    private val ESCALATE_NAME_REGEX = Regex("escalate|staff", RegexOption.IGNORE_CASE)
    private val STAFF_OPTION_REGEX = Regex("staff|ravelry|escalate", RegexOption.IGNORE_CASE)
    private val COMMENT_NAME_REGEX = Regex("comment|message|note|detail|body|text", RegexOption.IGNORE_CASE)

    /**
     * Parses the flag form out of [body], or null if it doesn't contain one.
     *
     * @param baseUrl Origin the form's `action` is resolved against (`https://www.ravelry.com`).
     */
    fun parse(postId: Long, baseUrl: String, body: String): FlagPostForm? {
        val doc = Ksoup.parse(unescapeRjsPayload(body))
        return doc.select("form").firstNotNullOfOrNull { parseForm(postId, baseUrl, it) }
    }

    private fun parseForm(postId: Long, baseUrl: String, form: Element): FlagPostForm? {
        val reason = reasonControl(form) ?: return null
        val action = form.attr("action").trim()
        if (action.isEmpty()) return null

        // Hidden inputs verbatim (authenticity_token, any _method verb tunnel, whatever
        // identifies the flagged object), plus a named submit button — Rails actions
        // sometimes branch on `commit`, and a browser would send it too.
        val fields = buildMap {
            form.select("input[type=hidden]").forEach { input ->
                val name = input.attr("name")
                if (name.isNotEmpty()) put(name, input.attr("value"))
            }
            form.selectFirst("input[type=submit][name], button[type=submit][name]")?.let { submit ->
                val name = submit.attr("name")
                val value = submit.attr("value")
                if (name.isNotEmpty() && value.isNotEmpty()) put(name, value)
            }
        }

        return FlagPostForm(
            postId = postId,
            submitUrl = resolveUrl(baseUrl, action),
            fields = fields,
            reasonFieldName = reason.first,
            reasons = reason.second,
            commentFieldName = commentFieldName(form),
            escalateField = escalateField(form),
        )
    }

    /** The reason picker's field name and its options, from a `<select>` or a radio group. */
    private fun reasonControl(form: Element): Pair<String, List<FlagReason>>? {
        val candidates = (selectControls(form) + radioControls(form))
            .filter { (name, options) -> options.isNotEmpty() && !ESCALATE_NAME_REGEX.containsMatchIn(name) }
        if (candidates.isEmpty()) return null
        // A name that reads like a reason wins outright; otherwise the richest control on
        // the form is the reason picker (the alternative — a lone yes/no toggle — has
        // already been excluded as an escalate control above).
        return candidates.firstOrNull { REASON_NAME_REGEX.containsMatchIn(it.first) }
            ?: candidates.maxByOrNull { it.second.size }
    }

    private fun selectControls(form: Element): List<Pair<String, List<FlagReason>>> =
        form.select("select[name]").mapNotNull { select ->
            val name = select.attr("name").ifEmpty { return@mapNotNull null }
            val options = select.select("option").mapNotNull { option ->
                val value = option.attr("value")
                if (value.isBlank()) null else FlagReason(value, option.text().trim().ifEmpty { value })
            }
            name to options
        }

    private fun radioControls(form: Element): List<Pair<String, List<FlagReason>>> =
        form.select("input[type=radio][name]")
            .groupBy { it.attr("name") }
            .filterKeys { it.isNotEmpty() }
            .map { (name, inputs) ->
                name to inputs.mapNotNull { input ->
                    val value = input.attr("value")
                    if (value.isBlank()) null else FlagReason(value, radioLabel(form, input) ?: value)
                }
            }

    /** The `<label>` text associated with radio [input], by `for`/id or by wrapping it. */
    private fun radioLabel(form: Element, input: Element): String? {
        val id = input.attr("id")
        if (id.isNotEmpty()) {
            form.selectFirst("label[for=$id]")?.text()?.trim()?.ifEmpty { null }?.let { return it }
        }
        return input.parent()
            ?.takeIf { it.tagName() == "label" }
            ?.text()
            ?.trim()
            ?.ifEmpty { null }
    }

    /** The free-text control's name — a textarea, or a comment-ish text input. */
    private fun commentFieldName(form: Element): String? =
        form.selectFirst("textarea[name]")?.attr("name")?.ifEmpty { null }
            ?: form.select("input[type=text][name]")
                .map { it.attr("name") }
                .firstOrNull { COMMENT_NAME_REGEX.containsMatchIn(it) }

    /**
     * The "escalate to Ravelry staff" control, when the form has one — a checkbox, or the
     * staff side of a moderators/staff radio pair (both shapes are plausible and the real
     * markup is login-walled, so neither is assumed).
     */
    private fun escalateField(form: Element): FlagEscalateField? {
        form.select("input[type=checkbox][name]")
            .firstOrNull { ESCALATE_NAME_REGEX.containsMatchIn(it.attr("name")) }
            ?.let { return FlagEscalateField(it.attr("name"), it.attr("value").ifEmpty { "1" }) }

        return form.select("input[type=radio][name]")
            .filter { ESCALATE_NAME_REGEX.containsMatchIn(it.attr("name")) }
            .firstOrNull { radio ->
                STAFF_OPTION_REGEX.containsMatchIn("${radio.attr("value")} ${radioLabel(form, radio).orEmpty()}")
            }
            ?.let { FlagEscalateField(it.attr("name"), it.attr("value")) }
    }

    /** Resolves a form `action` (absolute, root-relative or bare) against [baseUrl]. */
    private fun resolveUrl(baseUrl: String, action: String): String {
        val origin = baseUrl.trimEnd('/')
        return when {
            action.startsWith("http://") || action.startsWith("https://") -> action
            action.startsWith("/") -> "$origin$action"
            else -> "$origin/$action"
        }
    }
}
