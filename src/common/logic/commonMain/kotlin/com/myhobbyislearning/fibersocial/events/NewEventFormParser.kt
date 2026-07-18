package com.myhobbyislearning.fibersocial.events

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * Parses the "New Event" form (`www.ravelry.com/events/new?group={groupId}`, moderators
 * only) into a [NewEventForm].
 *
 * The scraped markup (see `docs/samples/new_event_form.html`) is a single
 * `<form id="new_event">` with `input[name=authenticity_token]`,
 * `input#event_creation_id`, and a `<select id="event_{field}">` per dropdown field.
 * Ravelry renders the country list as a short "quick pick" shortlist followed by the
 * same countries again inside the full alphabetical list — [parse] de-duplicates by ID.
 */
object NewEventFormParser {

    /** Parses the full HTML of the new-event form page, or null if the form isn't present (e.g. a non-moderator was redirected away). */
    fun parse(html: String): NewEventForm? {
        val form = Ksoup.parse(html).selectFirst("form#new_event") ?: return null
        val token = form.selectFirst("input[name=authenticity_token]")?.attr("value")
        val creationId = form.selectFirst("input#event_creation_id")?.attr("value")
        if (token.isNullOrEmpty() || creationId.isNullOrEmpty()) return null

        return NewEventForm(
            authenticityToken = token,
            creationId = creationId,
            countries = parseOptions(form, "event_country_id").distinctBy { it.id },
            onlineCategories = parseOptions(form, "event_online_event_type_id"),
            inPersonCategories = parseOptions(form, "event_in_person_event_type_id"),
            estimatedAttendanceOptions = parseOptions(form, "event_estimated_attendance"),
            timezones = parseTimezones(form),
        )
    }

    private fun parseOptions(form: Element, selectId: String): List<EventOption> =
        form.selectFirst("select#$selectId")
            ?.select("option")
            ?.mapNotNull { option ->
                val id = option.attr("value").toLongOrNull() ?: return@mapNotNull null
                EventOption(id, option.text().trim())
            }
            .orEmpty()

    private fun parseTimezones(form: Element): List<String> =
        form.selectFirst("select#event_start_timezone")
            ?.select("option")
            ?.mapNotNull { it.attr("value").trim().ifEmpty { null } }
            ?.distinct()
            .orEmpty()
}
