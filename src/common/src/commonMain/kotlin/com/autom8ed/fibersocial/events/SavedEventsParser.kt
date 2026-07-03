package com.autom8ed.fibersocial.events

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element
import kotlinx.datetime.LocalDate

/**
 * An event the current user has saved (RSVP'd to), from the "My Saved Events" page.
 *
 * @property permalink Event slug; the event page is `www.ravelry.com/events/{permalink}`.
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
 */
object SavedEventsParser {

    private val MONTH_HEADER = Regex("""([A-Za-z]+)\s+(\d{4})""")
    private val DAY_NUMBER = Regex("""(\d{1,2})""")

    private val MONTHS = listOf(
        "january", "february", "march", "april", "may", "june",
        "july", "august", "september", "october", "november", "december",
    )

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
                        month = MONTHS.indexOf(match.groupValues[1].lowercase()) + 1
                        year = match.groupValues[2].toInt()
                    }
                }
                child.hasClass("event") -> {
                    val link = child.selectFirst("div.details a.title") ?: continue
                    val permalink = link.attr("href")
                        .substringAfter("/events/", missingDelimiterValue = "")
                        .substringBefore('/')
                    if (permalink.isEmpty()) continue
                    val typeElement = child.selectFirst(".event__search_result__type")
                    val typeText = if (typeElement == null) "" else typeElement.text()
                    events += SavedEvent(
                        permalink = permalink,
                        title = link.text(),
                        date = eventDate(year, month, child.selectFirst("div.date div.day")),
                        eventType = typeText.ifEmpty { null },
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
        return runCatching { LocalDate(year, month, dayMatch.value.toInt()) }.getOrNull()
    }
}
