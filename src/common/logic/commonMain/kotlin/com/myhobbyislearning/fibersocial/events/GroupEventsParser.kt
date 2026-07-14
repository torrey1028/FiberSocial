package com.myhobbyislearning.fibersocial.events

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.LocalDateTime

/**
 * Parses a group page's "upcoming events" box into [EventSummary]s.
 *
 * The scraped markup (see `docs/samples/group_page_events.html`) is:
 * ```
 * <div class="box" id="upcoming_events"> … <div id="events">
 *   <div class="event">
 *     <div class="what"><a href="https://www.ravelry.com/events/{slug}">Title</a></div>
 *     <div class="when">July  8, 2026 @ 5:30 PM</div>
 *     <div class="who"><a href="…/{slug}/people">2 people</a></div>
 *   </div> …
 * ```
 * Lenient by design: an event without a `.what` link is skipped; missing or unparseable
 * `.when`/`.who` degrade to a null [EventSummary.startsAt] / zero attendees.
 */
object GroupEventsParser {

    /** Parses the full HTML of a group page; returns events in page (chronological) order. */
    fun parse(groupPageHtml: String): List<EventSummary> =
        Ksoup.parse(groupPageHtml)
            .select("#upcoming_events div.event")
            .mapNotNull { parseEvent(it) }

    private fun parseEvent(event: Element): EventSummary? {
        val link = event.selectFirst("div.what a") ?: return null
        val permalink = eventPermalinkFromHref(link.attr("href")) ?: return null

        val whenText = event.selectFirst("div.when")?.text().orEmpty()
        return EventSummary(
            permalink = permalink,
            title = link.text(),
            startsAt = parseEventDateTime(whenText),
            whenText = whenText,
            attendeeCount = parseAttendeeCount(event.selectFirst("div.who")?.text().orEmpty()),
        )
    }

    private val WHEN_REGEX = Regex(
        """([A-Za-z]+)\s+(\d{1,2}),\s*(\d{4})\s*@\s*(\d{1,2}):(\d{2})\s*(AM|PM)""",
        RegexOption.IGNORE_CASE,
    )

    private val ATTENDEES_REGEX = Regex("""(\d+)\s+(?:person|people)""")

    /**
     * Parses Ravelry's listing date format (`"July 8, 2026 @ 5:30 PM"`) into a venue-local
     * [LocalDateTime]. Returns null for anything else (e.g. multi-day festival ranges).
     */
    internal fun parseEventDateTime(whenText: String): LocalDateTime? {
        val match = WHEN_REGEX.find(whenText) ?: return null
        val (monthName, day, year, hour12, minute, amPm) = match.destructured
        val month = monthNumber(monthName)
        if (month == 0) return null
        val hour = when {
            amPm.uppercase() == "AM" -> if (hour12.toInt() == 12) 0 else hour12.toInt()
            hour12.toInt() == 12 -> 12
            else -> hour12.toInt() + 12
        }
        return runCatching {
            LocalDateTime(year.toInt(), month, day.toInt(), hour, minute.toInt())
        }.getOrNull()
    }

    internal fun parseAttendeeCount(whoText: String): Int {
        val match = ATTENDEES_REGEX.find(whoText) ?: return 0
        return match.groupValues[1].toIntOrNull() ?: 0
    }
}
