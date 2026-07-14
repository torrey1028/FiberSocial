package com.myhobbyislearning.fibersocial.events

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SavedEventsParserGoldenTest {
    private val events = SavedEventsParser.parse(SAVED_EVENTS_HTML)

    @Test
    fun `parses the saved event from the captured page`() {
        val event = events.single()
        assertEquals("sunday-circle-at-postdoc-brewing-10", event.permalink)
        assertEquals("Sunday Circle at Postdoc Brewing", event.title)
        assertEquals(LocalDate(2026, 7, 5), event.date)
        assertEquals("Knitting/crochet group", event.eventType)
    }
}

class SavedEventsParserStructureTest {
    private fun page(body: String) = """<div class="event_list" id="event_list">$body</div>"""

    private fun entry(slug: String, title: String, day: String) = """
        <div class="event event__search_result parent_event">
        <div class="date"><div class="day">$day</div><div class="dow">Sunday</div></div>
        <div class="details"><a href="https://www.ravelry.com/events/$slug" class="title">$title</a></div>
        </div>
    """

    @Test
    fun `month headers apply to the entries that follow them`() {
        val html = page(
            """<div class="month">July 2026</div>""" +
                entry("a", "A", "5th") +
                entry("b", "B", "21st") +
                """<div class="month">August 2026</div>""" +
                entry("c", "C", "2nd"),
        )
        val events = SavedEventsParser.parse(html)
        assertEquals(
            listOf(LocalDate(2026, 7, 5), LocalDate(2026, 7, 21), LocalDate(2026, 8, 2)),
            events.map { it.date },
        )
    }

    @Test
    fun `ordinal suffixes parse for all forms`() {
        val html = page(
            """<div class="month">July 2026</div>""" +
                entry("a", "A", "1st") + entry("b", "B", "2nd") +
                entry("c", "C", "3rd") + entry("d", "D", "24th"),
        )
        assertEquals(
            listOf(1, 2, 3, 24),
            SavedEventsParser.parse(html).map { it.date?.dayOfMonth },
        )
    }

    @Test
    fun `multi-day range takes the start day`() {
        val html = page("""<div class="month">July 2026</div>""" + entry("fest", "Festival", "10th – 12th"))
        assertEquals(LocalDate(2026, 7, 10), SavedEventsParser.parse(html).single().date)
    }

    @Test
    fun `stray numbers without an ordinal suffix are not a day`() {
        // "2026" must not parse as day 20.
        val html = page("""<div class="month">July 2026</div>""" + entry("x", "X", "2026"))
        assertNull(SavedEventsParser.parse(html).single().date)
    }

    @Test
    fun `recurring events keep one entry per occurrence`() {
        val html = page(
            """<div class="month">July 2026</div>""" +
                entry("weekly-circle", "Weekly Circle", "5th") +
                entry("weekly-circle", "Weekly Circle", "12th"),
        )
        val dates = SavedEventsParser.parse(html).map { it.permalink to it.date }
        assertEquals(
            listOf(
                "weekly-circle" to LocalDate(2026, 7, 5),
                "weekly-circle" to LocalDate(2026, 7, 12),
            ),
            dates,
        )
    }

    @Test
    fun `permalink strips query strings and fragments`() {
        val html = page(
            """<div class="month">July 2026</div>""" +
                """<div class="event event__search_result">
                <div class="date"><div class="day">5th</div></div>
                <div class="details"><a href="https://www.ravelry.com/events/slug?ref=saved#details" class="title">T</a></div>
                </div>""",
        )
        assertEquals("slug", SavedEventsParser.parse(html).single().permalink)
    }

    @Test
    fun `entry before any month header has a null date`() {
        val html = page(entry("orphan", "Orphan", "5th"))
        val event = SavedEventsParser.parse(html).single()
        assertNull(event.date)
        assertEquals("orphan", event.permalink)
    }

    @Test
    fun `unparseable day yields a null date but keeps the event`() {
        val html = page("""<div class="month">July 2026</div>""" + entry("x", "X", "??"))
        assertNull(SavedEventsParser.parse(html).single().date)
    }

    @Test
    fun `invalid day-of-month yields a null date`() {
        val html = page("""<div class="month">February 2026</div>""" + entry("x", "X", "31st"))
        assertNull(SavedEventsParser.parse(html).single().date)
    }

    @Test
    fun `unrecognized month header yields null dates for entries under it`() {
        val html = page("""<div class="month">Smarch 2026</div>""" + entry("x", "X", "5th"))
        assertNull(SavedEventsParser.parse(html).single().date)
    }

    @Test
    fun `entry without a date div has a null date`() {
        val html = page(
            """<div class="month">July 2026</div>
               <div class="event"><div class="details">
               <a href="https://www.ravelry.com/events/dateless" class="title">Dateless</a>
               </div></div>""",
        )
        assertNull(SavedEventsParser.parse(html).single().date)
    }

    @Test
    fun `title link without an events href is skipped`() {
        val html = page(
            """<div class="month">July 2026</div>
               <div class="event"><div class="details">
               <a href="https://www.ravelry.com/groups/not-an-event" class="title">Broken</a>
               </div></div>""",
        )
        assertTrue(SavedEventsParser.parse(html).isEmpty())
    }

    @Test
    fun `month header without a year yields null dates for entries under it`() {
        val html = page("""<div class="month">Coming up</div>""" + entry("x", "X", "5th"))
        assertNull(SavedEventsParser.parse(html).single().date)
    }

    @Test
    fun `empty type element yields a null event type`() {
        val html = page(
            """<div class="month">July 2026</div>
               <div class="event"><div class="date"><div class="day">5th</div></div>
               <div class="details"><a href="https://www.ravelry.com/events/x" class="title">X</a>
               <div class="event__search_result__type"> </div></div>
               </div>""",
        )
        assertNull(SavedEventsParser.parse(html).single().eventType)
    }

    @Test
    fun `entry without a title link is skipped`() {
        val html = page(
            """<div class="month">July 2026</div>
               <div class="event"><div class="details">no link</div></div>""",
        )
        assertTrue(SavedEventsParser.parse(html).isEmpty())
    }

    @Test
    fun `missing event type is null`() {
        val html = page("""<div class="month">July 2026</div>""" + entry("x", "X", "5th"))
        assertNull(SavedEventsParser.parse(html).single().eventType)
    }

    @Test
    fun `page without an event list yields no events`() {
        assertTrue(SavedEventsParser.parse("<html><body>My Saved Events</body></html>").isEmpty())
        assertTrue(SavedEventsParser.parse("").isEmpty())
    }

    @Test
    fun `empty saved list yields no events`() {
        assertTrue(SavedEventsParser.parse(page("")).isEmpty())
    }
}
