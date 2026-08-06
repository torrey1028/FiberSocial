package com.myhobbyislearning.fibersocial.feed

/**
 * A selectable reason option on Ravelry's "report a post" flag form.
 *
 * @property group The heading of the section this option sits under, when the form groups
 *   its reasons. Ravelry's real form has two: "Report to group moderators" and "Escalate
 *   to Ravelry staff" — same radio field, different destinations, so the heading is the
 *   only thing telling the user who will read the report.
 */
data class FlagReason(val id: String, val label: String, val group: String? = null)

/** The form control that escalates a report beyond the group's own moderators. */
data class FlagEscalateField(val name: String, val value: String)

/**
 * Everything needed to render and submit Ravelry's "report a post" flag form for a
 * single post (issue #409 — Apple Guideline 1.2 "flag objectionable content").
 *
 * Fetched from `www.ravelry.com/forum_posts/{postId}/prepare_flag`, which is what
 * Ravelry's own "report" link calls — its JS bundle's
 * `R.forums.prepareFlag(id)` does `new Ajax.Request('/forum_posts/'+id+'/prepare_flag',
 * {method:'get'})`. (Issue #467: the original implementation guessed
 * `/forum_posts/{postId}/flag` instead, which is not a route — every report failed with
 * "returned 404 Not Found" on device.)
 *
 * Everything about the *submission* is read off the fetched form rather than assumed:
 * [submitUrl] is the form's own `action`, [fields] its own hidden inputs, and
 * [reasonFieldName]/[commentFieldName]/[escalateField] the names of its own controls.
 * Replaying the form as the browser would is the only shape that can't 404 or 422 on a
 * naming guess a second time — see [FlagPostFormParser] for the markup Ravelry actually
 * serves, which the parser matches without requiring.
 *
 * @property postId The post being reported.
 * @property submitUrl Absolute URL the form posts to (its resolved `action`).
 * @property fields The form's hidden inputs verbatim — the CSRF `authenticity_token`,
 *   any Rails `_method` verb tunnel, and whatever identifies the flagged object.
 * @property reasonFieldName Name of the reason control (a `<select>` or radio group).
 * @property reasons The report-reason options Ravelry currently offers, scraped fresh
 *   rather than hardcoded so the app can't drift from Ravelry's own categories.
 * @property commentFieldName Name of the free-text "what's wrong" control, when the form
 *   has one (`flagging[comment]` on the observed form), else null.
 * @property escalateField A separate "escalate to Ravelry staff" checkbox, when the form
 *   has one, else null. Ravelry's real form instead models escalation as reasons under an
 *   "Escalate to Ravelry staff" heading (see [FlagReason.group]), so this stays null there.
 * @property defaultReasonId The option the form itself pre-selects, when it marks one.
 */
data class FlagPostForm(
    val postId: Long,
    val submitUrl: String,
    val fields: Map<String, String>,
    val reasonFieldName: String,
    val reasons: List<FlagReason>,
    val commentFieldName: String? = null,
    val escalateField: FlagEscalateField? = null,
    val defaultReasonId: String? = null,
) {
    /** Rails CSRF token, when the form carried one (see [RavelryApiClient.getFlagPostForm]). */
    val authenticityToken: String? get() = fields["authenticity_token"]

    /** Whether the form exposes an "Escalate to Ravelry staff" option. */
    val supportsEscalate: Boolean get() = escalateField != null

    /** Whether the form takes a free-text comment alongside the reason. */
    val supportsComment: Boolean get() = commentFieldName != null

    /** Whether the form sorts its reasons into named sections (see [FlagReason.group]). */
    val hasGroupedReasons: Boolean get() = reasons.any { it.group != null }

    /** The option to select when the dialog opens: the form's own default, else the first. */
    val initialReasonId: String?
        get() = defaultReasonId?.takeIf { id -> reasons.any { it.id == id } } ?: reasons.firstOrNull()?.id
}

/** Result of submitting a [FlagPostForm] via [RavelryApiClient.flagPost]. */
sealed class FlagPostResult {
    /** Ravelry accepted the report. */
    data object Success : FlagPostResult()

    /** Ravelry rejected the report. @property errors Human-readable validation messages. */
    data class ValidationFailed(val errors: List<String>) : FlagPostResult()
}
