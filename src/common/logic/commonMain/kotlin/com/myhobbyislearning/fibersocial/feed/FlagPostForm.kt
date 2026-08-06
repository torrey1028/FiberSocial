package com.myhobbyislearning.fibersocial.feed

/** A selectable reason option on Ravelry's "report a post" flag form. */
data class FlagReason(val id: String, val label: String)

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
 * [reasonFieldName]/[commentFieldName]/[escalateField] the names of its own controls. The
 * markup can't be captured without a logged-in session (the flag UI is login-walled), so
 * the parser deliberately holds no opinion about Rails' field naming — replaying the
 * form as the browser would is the only shape that can't 404 or 422 on a naming guess a
 * second time.
 *
 * @property postId The post being reported.
 * @property submitUrl Absolute URL the form posts to (its resolved `action`).
 * @property fields The form's hidden inputs verbatim — the CSRF `authenticity_token`,
 *   any Rails `_method` verb tunnel, and whatever identifies the flagged object.
 * @property reasonFieldName Name of the reason control (a `<select>` or radio group).
 * @property reasons The report-reason options Ravelry currently offers, scraped fresh
 *   rather than hardcoded so the app can't drift from Ravelry's own categories.
 * @property commentFieldName Name of the free-text "what's wrong" control, when the form
 *   has one (Ravelry's stylesheet shows a `#flag_comment` box), else null.
 * @property escalateField The "escalate to Ravelry staff" control, when the form offers
 *   one, else null.
 */
data class FlagPostForm(
    val postId: Long,
    val submitUrl: String,
    val fields: Map<String, String>,
    val reasonFieldName: String,
    val reasons: List<FlagReason>,
    val commentFieldName: String? = null,
    val escalateField: FlagEscalateField? = null,
) {
    /** Rails CSRF token, when the form carried one (see [RavelryApiClient.getFlagPostForm]). */
    val authenticityToken: String? get() = fields["authenticity_token"]

    /** Whether the form exposes an "Escalate to Ravelry staff" option. */
    val supportsEscalate: Boolean get() = escalateField != null

    /** Whether the form takes a free-text comment alongside the reason. */
    val supportsComment: Boolean get() = commentFieldName != null
}

/** Result of submitting a [FlagPostForm] via [RavelryApiClient.flagPost]. */
sealed class FlagPostResult {
    /** Ravelry accepted the report. */
    data object Success : FlagPostResult()

    /** Ravelry rejected the report. @property errors Human-readable validation messages. */
    data class ValidationFailed(val errors: List<String>) : FlagPostResult()
}
