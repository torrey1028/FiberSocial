package com.myhobbyislearning.fibersocial.feed

/** A selectable reason option on Ravelry's "report a post" flag form. */
data class FlagReason(val id: String, val label: String)

/**
 * Everything needed to render and submit Ravelry's "report a post" flag form for a
 * single post (issue #409 — Apple Guideline 1.2 "flag objectionable content").
 *
 * PROTOCOL ASSUMPTION — unverified against a real logged-in session (login-walled; see
 * the PR's "Needs on-device verification" section): scraped fresh from
 * `www.ravelry.com/forum_posts/{postId}/flag`, mirroring the same
 * fetch-a-form-then-POST shape as
 * [com.myhobbyislearning.fibersocial.events.NewEventForm]/`NewEventFormParser`, and the
 * same nested-member-action URL convention already used for
 * [RavelryApiClient.joinGroup]/[RavelryApiClient.leaveGroup]
 * (`/groups/{permalink}/{action}`) and event RSVPs (`/events/{permalink}/attend`) —
 * i.e. a member action nested under the post resource, rather than a separate
 * top-level `/flags` resource.
 *
 * @property postId The post being reported.
 * @property authenticityToken Rails CSRF token from the flag form.
 * @property reasons The report-reason options Ravelry currently offers (e.g. "Off
 *   topic", "Spam", "Abusive"), scraped fresh rather than hardcoded — see
 *   [FlagPostFormParser] — so the app can't drift from whatever categories Ravelry
 *   currently offers (mirrors [com.myhobbyislearning.fibersocial.events.NewEventForm]'s
 *   dropdown scraping).
 * @property supportsEscalate Whether the form exposes an "Escalate to Ravelry staff"
 *   option (per the plan doc's investigation, every group's flag form does — kept as a
 *   flag rather than assumed so a form shape that omits it degrades gracefully instead
 *   of crashing).
 */
data class FlagPostForm(
    val postId: Long,
    val authenticityToken: String,
    val reasons: List<FlagReason>,
    val supportsEscalate: Boolean,
)

/** Result of submitting a [FlagPostForm] via [RavelryApiClient.flagPost]. */
sealed class FlagPostResult {
    /** Ravelry accepted the report. */
    data object Success : FlagPostResult()

    /** Ravelry rejected the report. @property errors Human-readable validation messages. */
    data class ValidationFailed(val errors: List<String>) : FlagPostResult()
}
