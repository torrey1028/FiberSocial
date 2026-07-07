package com.autom8ed.fibersocial.events

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * Parses an event page (`www.ravelry.com/events/{permalink}`) into an [EventDetail].
 *
 * The scraped markup (see `docs/samples/event_page.html`):
 * ```
 * <div class="page_title">…Event Title</div>
 * <div class="event__detail">
 *   <div class="event__type">Knitting/crochet group</div>
 *   <div class="event__dates">July  1, 2026 @  5:30 PM</div>
 *   <ul id="venue_summary"><li class="venue_name">…</li>…</ul>
 *   <div id="subsections"><div class="subsection"><div class="markdown">…</div></div>
 *   <table>…<a href="…/discuss/{group}/{topicId}">Topic</a>…</table>
 * ```
 * Lenient like the other scrapers: missing sections degrade to nulls/empties; only a
 * page without an `.event__detail` block at all yields null.
 */
object EventPageParser {

    /** Parses the full HTML of an event page, or null if it isn't one. */
    fun parse(eventPageHtml: String): EventDetail? {
        val doc = Ksoup.parse(eventPageHtml)
        val detail = doc.selectFirst("div.event__detail") ?: return null

        val whenText = detail.selectFirst("div.event__dates")?.text().orEmpty()
        val markdown = detail.selectFirst("#subsections div.markdown")
        return EventDetail(
            title = pageTitle(doc),
            eventType = detail.selectFirst("div.event__type")?.text(),
            startsAt = GroupEventsParser.parseEventDateTime(whenText),
            whenText = whenText,
            venue = parseVenue(detail),
            descriptionHtml = if (markdown == null) "" else markdown.html().trim(),
            discussions = parseDiscussions(detail),
            // The attend button label toggles between "save event" and "event saved".
            attending = doc.selectFirst("#attend_button")?.text().orEmpty().contains("saved"),
            csrfToken = doc.selectFirst("meta#authenticity-token")?.attr("content"),
        )
    }

    /**
     * The page title's own text — the `.page_title` div holds the event name as a bare
     * text node next to a supertitle child ("events"), so `ownText` skips the child.
     */
    private fun pageTitle(doc: Element): String {
        val pageTitle = doc.selectFirst("div.page_title") ?: return ""
        return pageTitle.ownText().trim()
    }

    /** Null when `#venue_summary` is absent or has no non-empty rows we recognize. */
    private fun parseVenue(detail: Element): EventVenue? {
        val summary = detail.selectFirst("#venue_summary") ?: return null
        fun row(cls: String): String? {
            val element = summary.selectFirst("li.$cls") ?: return null
            return element.text().trim().takeIf { it.isNotEmpty() }
        }
        val venue = EventVenue(
            name = row("venue_name"),
            address = row("address"),
            cityState = row("city_state"),
            country = row("country"),
        )
        return venue.takeIf {
            it.name != null || it.address != null || it.cityState != null || it.country != null
        }
    }

    private fun parseDiscussions(detail: Element): List<EventDiscussion> =
        detail.select("table tr").mapNotNull { parseDiscussionRow(it) }

    private fun parseDiscussionRow(tr: Element): EventDiscussion? {
        val link = tr.selectFirst("""td a[href*="/discuss/"]""") ?: return null
        // href shape: https://www.ravelry.com/discuss/{groupPermalink}/{topicId}
        val path = link.attr("href").substringAfter("/discuss/", missingDelimiterValue = "")
        val groupPermalink = path.substringBefore('/')
        val topicId = path.substringAfter('/', missingDelimiterValue = "").toLongOrNull()
        if (groupPermalink.isEmpty() || topicId == null) return null

        val cells = tr.children().filter { it.tagName() == "td" }
        val postsCount = cells.last().text().toIntOrNull() ?: 0
        return EventDiscussion(
            topicId = topicId,
            groupPermalink = groupPermalink,
            title = link.text().trim(),
            postsCount = postsCount,
        )
    }
}
