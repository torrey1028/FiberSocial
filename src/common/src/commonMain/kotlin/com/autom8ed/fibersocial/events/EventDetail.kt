package com.autom8ed.fibersocial.events

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
 */
data class EventDetail(
    val title: String,
    val eventType: String?,
    val startsAt: LocalDateTime?,
    val whenText: String,
    val venue: EventVenue?,
    val descriptionHtml: String,
    val discussions: List<EventDiscussion>,
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
