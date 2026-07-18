package com.myhobbyislearning.fibersocial.events

import com.fleeksoft.ksoup.Ksoup

/**
 * The follow-up "choose a venue" form embedded in a successful creation response —
 * everything needed to replay its submission verbatim rather than reconstructing the
 * request from guessed field names (see docs/samples/event_create_response.html).
 *
 * @property action The form's `action` path, e.g. `/events/another-test-event`.
 * @property hiddenFields The form's hidden inputs in document order (`_method=put`,
 *   `authenticity_token`, `step=venue`), deduplicated by name — Ravelry nests two
 *   same-named forms here and browsers flatten them, so the duplicates carry
 *   identical values.
 */
data class EventVenueForm(
    val action: String,
    val hiddenFields: List<Pair<String, String>>,
)

/**
 * Thrown when the event itself was created but Ravelry rejected the follow-up venue
 * step — the event exists as a loadable page but won't appear in its group's
 * "upcoming events" box until a venue is added (e.g. by editing it on ravelry.com).
 *
 * @property permalink The created event's slug, still valid despite the failure.
 */
class EventVenueException(val permalink: String, message: String) : Exception(message)

/** Result of submitting the "New Event" form (see [EventCreateResponseParser]). */
sealed class EventCreateResult {
    /**
     * @property permalink The new event's slug, e.g. `another-test-event`.
     * @property venueForm The embedded venue-step form, when the response contained
     *   one with hidden fields (in-person events).
     */
    data class Success(val permalink: String, val venueForm: EventVenueForm?) : EventCreateResult()

    /** @property errors Human-readable messages from Ravelry's own validation banner. */
    data class ValidationFailed(val errors: List<String>) : EventCreateResult()
}

/**
 * Parses the HTML Ravelry returns after POSTing to `www.ravelry.com/events`.
 *
 * Unlike most of this app's website writes, a successful creation renders inline (HTTP
 * 200, not a redirect): Ravelry immediately shows a follow-up "choose a venue" step for
 * the newly created event under a `<form id="edit_event_{id}" action="/events/{permalink}">`
 * (two of them, nested — a wrapper carrying `step=venue` around the venue fields). That
 * form's presence (and its `action`'s embedded permalink) is what distinguishes success
 * from failure — a rejected submission instead re-renders the original creation form
 * with a `ul.brief_error_messages` banner and no `edit_event` form, so the two shapes
 * never overlap in practice.
 */
object EventCreateResponseParser {

    fun parse(html: String): EventCreateResult {
        val doc = Ksoup.parse(html)
        val forms = doc.select("form[id^=edit_event]")
        val action = forms.firstOrNull()?.attr("action")
        val permalink = action?.let { eventPermalinkFromHref(it) }
        if (action != null && permalink != null) {
            // Union across both nested forms: browsers flatten the nesting on submit, and
            // depending on the HTML parser's nested-form recovery the hidden inputs (and
            // the wrapper's step=venue) can land in either element.
            val hiddenFields = forms
                .flatMap { it.select("input[type=hidden]") }
                .map { it.attr("name") to it.attr("value") }
                .distinctBy { it.first }
            val venueForm = hiddenFields.takeIf { it.isNotEmpty() }?.let { EventVenueForm(action, it) }
            return EventCreateResult.Success(permalink, venueForm)
        }

        val errors = parseErrors(html)
        return EventCreateResult.ValidationFailed(
            errors.ifEmpty {
                listOf("Unexpected response — check the group's events before retrying, the event may have been created.")
            },
        )
    }

    /**
     * Extracts the messages from Ravelry's `ul.brief_error_messages` validation banner —
     * the shape both a rejected creation and a rejected venue step re-render with. Empty
     * when the page has no banner.
     */
    fun parseErrors(html: String): List<String> =
        Ksoup.parse(html).select("ul.brief_error_messages li").map { it.text() }
}
