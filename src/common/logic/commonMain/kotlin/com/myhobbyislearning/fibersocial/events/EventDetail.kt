package com.myhobbyislearning.fibersocial.events

import io.ktor.http.encodeURLParameter
import kotlinx.datetime.LocalDateTime

/**
 * Full details of an event, scraped from `www.ravelry.com/events/{permalink}`
 * (see [EventPageParser]).
 *
 * @property title Event name from the page title.
 * @property eventType Ravelry's event category, e.g. `"Knitting/crochet group"`;
 *   null when the page shows none.
 * @property startsAt Parsed venue-local start; null for unparseable formats
 *   (e.g. multi-day festival ranges) — fall back to [whenText].
 * @property whenText Raw date text as displayed, e.g. `"July 1, 2026 @ 5:30 PM"`.
 * @property venue Venue block; null when the event lists no venue.
 * @property descriptionHtml Rendered-Markdown HTML of the event description (same shape
 *   as forum post `body_html` — display with `HtmlPostParser` + `PostBody`). Empty when
 *   the event has no description.
 * @property discussions Forum topics linked to this event, in page order.
 * @property attending Whether the current user has saved this event ("event saved" on
 *   the page's attend button — the site calls RSVPing "saving" an event).
 * @property csrfToken Rails authenticity token from the page's
 *   `meta#authenticity-token`, required by the attend/unattend endpoints. Null when the
 *   page carried none (RSVP is then unavailable).
 */
data class EventDetail(
    val title: String,
    val eventType: String?,
    val startsAt: LocalDateTime?,
    val whenText: String,
    val venue: EventVenue?,
    val descriptionHtml: String,
    val discussions: List<EventDiscussion>,
    val attending: Boolean = false,
    val csrfToken: String? = null,
)

/**
 * An event's venue, from the page's venue summary list. All fields are as displayed;
 * any may be null when the row is absent.
 */
data class EventVenue(
    val name: String? = null,
    val address: String? = null,
    val cityState: String? = null,
    val country: String? = null,
)

/**
 * A directions URL to this venue in the platform's native maps app, or null when every
 * field is absent/blank. The query itself is built once here; [mapsAppUrl] supplies the
 * platform-specific URL template (issue #281 wanted directions straight to the venue,
 * not a disambiguation search-results list — that's still true per-platform).
 */
fun EventVenue.mapsUrl(): String? {
    val query = listOfNotNull(name, address, cityState, country)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString(", ")
    if (query.isEmpty()) return null
    return mapsAppUrl(query.encodeURLParameter())
}

/**
 * Platform-specific directions-URL template for [encodedQuery] (already URL-encoded).
 * Android uses the universal `google.com/maps/dir` link — no assumption that Google Maps
 * is installed, since it falls back to the Maps website in a browser when it isn't. iOS
 * uses the `maps.apple.com` universal link instead of assuming Google Maps is installed
 * there too: Apple Maps is guaranteed present on every iOS device, so that link always
 * opens the native app rather than falling back to a browser.
 */
expect fun mapsAppUrl(encodedQuery: String): String

/**
 * A forum topic associated with an event.
 *
 * @property topicId Ravelry topic ID — navigable with the existing topic APIs.
 * @property groupPermalink Permalink of the group hosting the discussion.
 * @property title Topic title.
 * @property postsCount Number of posts per the listing.
 */
data class EventDiscussion(
    val topicId: Long,
    val groupPermalink: String,
    val title: String,
    val postsCount: Int,
)
