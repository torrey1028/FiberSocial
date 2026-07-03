package com.autom8ed.fibersocial.events

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.LocalDate

/**
 * An event the current user has saved (RSVP'd to), from the "My Saved Events" page.
 *
 * @property permalink Event slug; the event page is `www.ravelry.com/events/{permalink}`.
 *   Recurring events list one row per occurrence, so the same permalink can appear
 *   several times with different dates.
 * @property title Event name as listed.
 * @property date Calendar date of the event, from the list's month header plus the
 *   entry's day-of-month. The listing carries no time of day — fetch the event page
 *   ([EventPageParser]) when the exact start time is needed.
 * @property eventType Ravelry's event category, e.g. `"Knitting/crochet group"`;
 *   null when the entry shows none.
 */
data class SavedEvent(
    val permalink: String,
    val title: String,
    val date: LocalDate?,
    val eventType: String?,
)

/**
 * Parses the "My Saved Events" page (`www.ravelry.com/events/saved`) — the user's RSVP
 * list, used to schedule event reminders.
 *
 * The scraped markup (see `docs/samples/saved_events.html`):
 * ```
 * <div class="event_list" id="event_list">
 *   <div class="month">July 2026</div>
 *   <div class="event event__search_result parent_event">
 *     <div class="date"><div class="day">5th</div><div class="dow">Sunday</div></div>
 *     <div class="details">
 *       <a href="…/events/{slug}" class="title">Title</a>
 *       <div class="event__search_result__type">Knitting/crochet group</div>
 *     …
 * ```
 * Month headers apply to the entries that follow them. Lenient like the other scrapers:
 * entries without a title link are skipped; an unparseable month/day yields a null date.
 *
 * The list is returned as Ravelry renders it: recurring events appear once per
 * occurrence, and the page may include past events the user never un-saved —
 * filtering (and de-duplicating, if keying by permalink) is the consumer's job.
 */
object SavedEventsParser {

    private val MONTH_HEADER = Regex("""([A-Za-z]+)\s+(\d{4})""")

    // Anchored to the ordinal suffix so stray numbers in the cell (a year, a
    // range's second month) can't be mistaken for the day. A range like
    // "31st – 2nd" yields its first day — the event's start.
    private val DAY_NUMBER = Regex("""(\d{1,2})(?:st|nd|rd|th)""", RegexOption.IGNORE_CASE)

    /** Parses the full HTML of the saved-events page; entries in page order. */
    fun parse(savedEventsPageHtml: String): List<SavedEvent> {
        val list = Ksoup.parse(savedEventsPageHtml).selectFirst("div.event_list")
            ?: return emptyList()

        val events = mutableListOf<SavedEvent>()
        // A month of 0 means "no valid header seen yet"; year is only read alongside it.
        var month = 0
        var year = 0
        for (child in list.children()) {
            when {
                child.hasClass("month") -> {
                    val match = MONTH_HEADER.find(child.text())
                    if (match == null) {
                        month = 0
                    } else {
                        month = monthNumber(match.groupValues[1])
                        year = match.groupValues[2].toInt()
                    }
                }
                child.hasClass("event") -> {
                    val link = child.selectFirst("div.details a.title") ?: continue
                    val permalink = eventPermalinkFromHref(link.attr("href")) ?: continue
                    events += SavedEvent(
                        permalink = permalink,
                        title = link.text(),
                        date = eventDate(year, month, child.selectFirst("div.date div.day")),
                        eventType = child.selectFirst(".event__search_result__type")
                            ?.text()?.ifEmpty { null },
                    )
                }
            }
        }
        return events
    }

    /** Combines the running month header with an ordinal day element ("5th") into a date. */
    private fun eventDate(year: Int, month: Int, dayElement: Element?): LocalDate? {
        if (month == 0 || dayElement == null) return null
        val dayMatch = DAY_NUMBER.find(dayElement.text()) ?: return null
        return runCatching { LocalDate(year, month, dayMatch.groupValues[1].toInt()) }.getOrNull()
    }
}
