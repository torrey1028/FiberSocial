package com.myhobbyislearning.fibersocial.events

import kotlinx.datetime.LocalDateTime

/**
 * An upcoming event as listed in a group page's "upcoming events" box.
 *
 * Ravelry has no events API; these are scraped from `www.ravelry.com/groups/{permalink}`
 * (see [GroupEventsParser]).
 *
 * @property permalink Event slug, e.g. `wednesday-hh-at-chainline-39`; the event page is
 *   `www.ravelry.com/events/{permalink}`.
 * @property title Event name as shown in the listing.
 * @property startsAt Parsed local start date/time (venue-local; Ravelry shows no zone).
 *   Null when the listing's date text doesn't match the expected format.
 * @property whenText The raw date text as displayed, e.g. `"July 8, 2026 @ 5:30 PM"`;
 *   kept for display fidelity and as a fallback when [startsAt] is null.
 * @property attendeeCount Number of people attending per the listing; 0 when absent.
 */
data class EventSummary(
    val permalink: String,
    val title: String,
    val startsAt: LocalDateTime?,
    val whenText: String,
    val attendeeCount: Int,
)
