package com.myhobbyislearning.fibersocial.events

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupEventsParserGoldenTest {
    private val events = GroupEventsParser.parse(GROUP_PAGE_HTML)

    @Test
    fun `parses all events from the box in page order`() {
        assertEquals(26, events.size)
        assertEquals("sunday-circle-at-postdoc-brewing-10", events.first().permalink)
        assertEquals("wednesday-hh-at-chainline-51", events.last().permalink)
    }

    @Test
    fun `first event carries all fields`() {
        val first = events.first()
        assertEquals("Sunday Circle at Postdoc Brewing", first.title)
        assertEquals("July 5, 2026 @ 1:00 PM", first.whenText)
        assertEquals(LocalDateTime(2026, 7, 5, 13, 0), first.startsAt)
        assertEquals(1, first.attendeeCount)
    }

    @Test
    fun `zero-people events parse as zero attendees`() {
        val chainline = events.first { it.permalink == "wednesday-hh-at-chainline-39" }
        assertEquals(0, chainline.attendeeCount)
        assertEquals(LocalDateTime(2026, 7, 8, 17, 30), chainline.startsAt)
    }

    @Test
    fun `morning times parse as AM`() {
        val coffee = events.first { it.permalink == "saturday-morning-coffee-at-diva-espresso" }
        assertEquals(LocalDateTime(2026, 7, 11, 9, 30), coffee.startsAt)
    }

    @Test
    fun `event links outside the upcoming_events box are ignored`() {
        assertTrue(events.none { it.permalink == "some-unrelated-event" })
    }

    @Test
    fun `all golden events have parseable dates in 2026`() {
        assertTrue(events.all { it.startsAt != null && it.startsAt!!.year == 2026 })
    }
}

class GroupEventsParserLenienceTest {
    private fun event(what: String = "", `when`: String = "", who: String = "") = """
        <div id="upcoming_events"><div id="events"><div class="event">
        <div class="what">$what</div>
        <div class="when">$`when`</div>
        <div class="who">$who</div>
        </div></div></div>
    """

    @Test
    fun `event without a link is skipped`() {
        assertTrue(GroupEventsParser.parse(event(what = "no link here")).isEmpty())
    }

    @Test
    fun `href without an events path is skipped`() {
        val html = event(what = """<a href="https://www.ravelry.com/groups/foo">x</a>""")
        assertTrue(GroupEventsParser.parse(html).isEmpty())
    }

    @Test
    fun `unparseable when text keeps raw text with null startsAt`() {
        val html = event(
            what = """<a href="https://www.ravelry.com/events/fiber-fest">Fiber Fest</a>""",
            `when` = "October 3-5, 2026",
        )
        val e = GroupEventsParser.parse(html).single()
        assertNull(e.startsAt)
        assertEquals("October 3-5, 2026", e.whenText)
    }

    @Test
    fun `missing when and who default to empty and zero`() {
        val html = """
            <div id="upcoming_events"><div class="event">
            <div class="what"><a href="https://www.ravelry.com/events/minimal">Minimal</a></div>
            </div></div>
        """
        val e = GroupEventsParser.parse(html).single()
        assertEquals("", e.whenText)
        assertNull(e.startsAt)
        assertEquals(0, e.attendeeCount)
    }

    @Test
    fun `page without an events box yields no events`() {
        assertTrue(GroupEventsParser.parse("<html><body><p>quiet group</p></body></html>").isEmpty())
        assertTrue(GroupEventsParser.parse("").isEmpty())
    }
}

class GroupEventsParserModeratorTest {
    @Test
    fun `edit link in breadcrumbs tools marks the user as a moderator`() {
        val html = """
            <span class="breadcrumbs__tools">
              <a href="https://www.ravelry.com/groups/some-group/edit">edit</a>
            </span>
        """
        assertTrue(GroupEventsParser.parseIsModerator(html))
    }

    @Test
    fun `no breadcrumbs tools means not a moderator`() {
        assertFalse(GroupEventsParser.parseIsModerator("<html><body>just a group page</body></html>"))
    }

    @Test
    fun `breadcrumbs tools without an edit link means not a moderator`() {
        val html = """
            <span class="breadcrumbs__tools">
              <a href="https://www.ravelry.com/groups/some-group/leave">leave group</a>
            </span>
        """
        assertFalse(GroupEventsParser.parseIsModerator(html))
    }
}

class EventDateTimeParsingTest {
    @Test
    fun `noon and midnight follow 12-hour convention`() {
        assertEquals(
            LocalDateTime(2026, 8, 1, 12, 0),
            GroupEventsParser.parseEventDateTime("August 1, 2026 @ 12:00 PM"),
        )
        assertEquals(
            LocalDateTime(2026, 8, 1, 0, 15),
            GroupEventsParser.parseEventDateTime("August 1, 2026 @ 12:15 AM"),
        )
    }

    @Test
    fun `double spaces from the site markup are tolerated`() {
        assertEquals(
            LocalDateTime(2026, 7, 5, 13, 0),
            GroupEventsParser.parseEventDateTime("July  5, 2026 @  1:00 PM"),
        )
    }

    @Test
    fun `unknown month yields null`() {
        assertNull(GroupEventsParser.parseEventDateTime("Smarch 5, 2026 @ 1:00 PM"))
    }

    @Test
    fun `invalid day-of-month yields null`() {
        assertNull(GroupEventsParser.parseEventDateTime("February 31, 2026 @ 1:00 PM"))
    }

    @Test
    fun `attendee counts parse from person and people variants`() {
        assertEquals(1, GroupEventsParser.parseAttendeeCount("1 person"))
        assertEquals(12, GroupEventsParser.parseAttendeeCount("12 people"))
        assertEquals(0, GroupEventsParser.parseAttendeeCount("nobody yet"))
        assertEquals(0, GroupEventsParser.parseAttendeeCount(""))
        assertEquals(0, GroupEventsParser.parseAttendeeCount("99999999999 people"))
    }
}
