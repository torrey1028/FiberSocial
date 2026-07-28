package com.myhobbyislearning.fibersocial.feed

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * Parses Ravelry's "report a post" flag form (see [FlagPostForm] for the URL/shape this
 * assumes — unverified against a real session, flagged under the PR's "Needs on-device
 * verification" heading).
 *
 * Tries two plausible shapes for the reason picker, since the real markup can't be
 * captured without a logged-in session: a `<select>` (mirrors
 * [com.myhobbyislearning.fibersocial.events.NewEventFormParser]'s dropdown scraping) or
 * a radio-button group (a small fixed set of reasons — per the plan doc's investigation
 * — more plausibly renders as radios than a dropdown in Ravelry's own UI). Whichever
 * shape is present in the real form wins; if neither parses, [parse] returns `null` so
 * the caller fails loudly instead of silently reporting zero reasons.
 */
object FlagPostFormParser {

    /** Parses the full HTML of the flag form page/fragment, or null if it isn't present. */
    fun parse(postId: Long, html: String): FlagPostForm? {
        val doc = Ksoup.parse(html)
        val form = doc.selectFirst("form#new_post_flag, form[action*=flag]") ?: return null
        val token = form.selectFirst("input[name=authenticity_token]")?.attr("value")
        if (token.isNullOrEmpty()) return null

        val reasons = parseSelectReasons(form) ?: parseRadioReasons(form)
        if (reasons.isNullOrEmpty()) return null

        val supportsEscalate = form.selectFirst(
            "input[type=checkbox][name*=escalate], input[type=radio][value*=staff], " +
                "input[name*=escalate]",
        ) != null

        return FlagPostForm(postId, token, reasons, supportsEscalate)
    }

    private fun parseSelectReasons(form: Element): List<FlagReason>? {
        val select = form.selectFirst("select[name*=reason]") ?: return null
        val options = select.select("option").mapNotNull { option ->
            val value = option.attr("value")
            if (value.isBlank()) return@mapNotNull null
            FlagReason(value, option.text().trim())
        }
        return options.ifEmpty { null }
    }

    private fun parseRadioReasons(form: Element): List<FlagReason>? {
        val options = form.select("input[type=radio][name*=reason]").mapNotNull { input ->
            val value = input.attr("value")
            if (value.isBlank()) return@mapNotNull null
            FlagReason(value, radioLabel(form, input) ?: value)
        }
        return options.ifEmpty { null }
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
}
