package com.myhobbyislearning.fibersocial.events

import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventPageParserGoldenTest {
    private val detail = assertNotNull(EventPageParser.parse(EVENT_PAGE_HTML))

    @Test
    fun `parses title from the page title skipping the supertitle`() {
        assertEquals("Wednesday HH at Chainline", detail.title)
    }

    @Test
    fun `parses event type and start time`() {
        assertEquals("Knitting/crochet group", detail.eventType)
        assertEquals("July 1, 2026 @ 5:30 PM", detail.whenText)
        assertEquals(LocalDateTime(2026, 7, 1, 17, 30), detail.startsAt)
    }

    @Test
    fun `parses the full venue block`() {
        assertEquals(
            EventVenue(
                name = "Chainline Brewing",
                address = "500 Uptown Ct Ste 210, Kirkland, WA 98033",
                cityState = "Kirkland, Washington",
                country = "United States",
            ),
            detail.venue,
        )
    }

    @Test
    fun `description is the markdown-rendered html`() {
        assertEquals(
            "<p>Bring your latest project and join us for our weekly happy hour! (no drinking required)</p>",
            detail.descriptionHtml,
        )
    }

    @Test
    fun `parses all linked discussions in page order`() {
        assertEquals(10, detail.discussions.size)
        assertEquals(
            EventDiscussion(
                topicId = 4410262,
                groupPermalink = "kirkland-fiber-arts-circle-2",
                title = "Felted bowl making oarty",
                postsCount = 3,
            ),
            detail.discussions.first(),
        )
        assertEquals(4381854, detail.discussions.last().topicId)
        assertEquals(23, detail.discussions.last().postsCount)
    }

    @Test
    fun `discussion titles are whitespace-normalized`() {
        // The capture has a trailing space inside this link's text.
        assertEquals("Works in Progress", detail.discussions.last().title)
    }

    @Test
    fun `save event button means not attending`() {
        assertEquals(false, detail.attending)
    }

    @Test
    fun `authenticity token is extracted from the page meta`() {
        assertEquals("JEN38Bicg515OwvzGE7jaHa9qYjMQgYwmIZsRpzPYzU=", detail.csrfToken)
    }
}

class EventPageParserAttendanceTest {
    @Test
    fun `event saved button means attending`() {
        val html = """
            <a class="button" id="attend_button"><span>event saved</span></a>
            <div class="event__detail"></div>
        """
        assertEquals(true, assertNotNull(EventPageParser.parse(html)).attending)
    }

    @Test
    fun `missing attend button means not attending with no token`() {
        val detail = assertNotNull(EventPageParser.parse("""<div class="event__detail"></div>"""))
        assertEquals(false, detail.attending)
        assertNull(detail.csrfToken)
    }
}

class EventPageParserLenienceTest {
    @Test
    fun `page without an event detail block yields null`() {
        assertNull(EventPageParser.parse("<html><body><h1>404</h1></body></html>"))
        assertNull(EventPageParser.parse(""))
    }

    @Test
    fun `minimal event page degrades to empty fields`() {
        val detail = assertNotNull(EventPageParser.parse("""<div class="event__detail"></div>"""))
        assertEquals("", detail.title)
        assertNull(detail.eventType)
        assertNull(detail.startsAt)
        assertEquals("", detail.whenText)
        assertNull(detail.venue)
        assertEquals("", detail.descriptionHtml)
        assertTrue(detail.discussions.isEmpty())
    }

    @Test
    fun `venue with only a name leaves other rows null`() {
        val html = """
            <div class="event__detail">
            <ul id="venue_summary"><li class="venue_name">Cozy Yarns</li></ul>
            </div>
        """
        val venue = assertNotNull(EventPageParser.parse(html)).venue
        assertEquals(EventVenue(name = "Cozy Yarns"), venue)
    }

    @Test
    fun `venue with only an address leaves other rows null`() {
        val html = """
            <div class="event__detail">
            <ul id="venue_summary"><li class="address">500 Uptown Ct Ste 210</li></ul>
            </div>
        """
        val venue = assertNotNull(EventPageParser.parse(html)).venue
        assertEquals(EventVenue(address = "500 Uptown Ct Ste 210"), venue)
    }

    @Test
    fun `venue with only a city and state leaves other rows null`() {
        val html = """
            <div class="event__detail">
            <ul id="venue_summary"><li class="city_state">Kirkland, Washington</li></ul>
            </div>
        """
        val venue = assertNotNull(EventPageParser.parse(html)).venue
        assertEquals(EventVenue(cityState = "Kirkland, Washington"), venue)
    }

    @Test
    fun `venue with only a country leaves other rows null`() {
        val html = """
            <div class="event__detail">
            <ul id="venue_summary"><li class="country">United States</li></ul>
            </div>
        """
        val venue = assertNotNull(EventPageParser.parse(html)).venue
        assertEquals(EventVenue(country = "United States"), venue)
    }

    @Test
    fun `venue summary with no recognizable rows yields a null venue`() {
        val html = """
            <div class="event__detail">
            <ul id="venue_summary"><li class="venue_name">   </li><li class="unrelated">x</li></ul>
            </div>
        """
        assertNull(assertNotNull(EventPageParser.parse(html)).venue)
    }

    @Test
    fun `blank venue rows are treated as absent`() {
        val html = """
            <div class="event__detail">
            <ul id="venue_summary"><li class="venue_name">Cozy Yarns</li><li class="address">   </li></ul>
            </div>
        """
        val venue = assertNotNull(EventPageParser.parse(html)).venue
        assertEquals(EventVenue(name = "Cozy Yarns"), venue)
    }

    @Test
    fun `multi-day date ranges keep raw text with null startsAt`() {
        val html = """
            <div class="event__detail">
            <div class="event__dates">October 3-5, 2026</div>
            </div>
        """
        val detail = assertNotNull(EventPageParser.parse(html))
        assertNull(detail.startsAt)
        assertEquals("October 3-5, 2026", detail.whenText)
    }

    @Test
    fun `table rows without a discussion link are skipped`() {
        val html = """
            <div class="event__detail"><table>
            <tr><th>Group</th><th>Posts</th></tr>
            <tr><td>no link</td><td>5</td></tr>
            <tr><td><a href="https://www.ravelry.com/discuss/some-group/123">Topic</a></td><td>5</td></tr>
            </table></div>
        """
        val detail = assertNotNull(EventPageParser.parse(html))
        assertEquals(listOf(123L), detail.discussions.map { it.topicId })
    }

    @Test
    fun `discussion links with malformed hrefs are skipped`() {
        val html = """
            <div class="event__detail"><table>
            <tr><td><a href="https://www.ravelry.com/discuss/only-group-no-id">Broken</a></td><td>5</td></tr>
            <tr><td><a href="https://www.ravelry.com/discuss/g/not-a-number">Broken too</a></td><td>5</td></tr>
            <tr><td><a href="https://www.ravelry.com/discuss/">Bare discuss root</a></td><td>5</td></tr>
            </table></div>
        """
        val detail = assertNotNull(EventPageParser.parse(html))
        assertTrue(detail.discussions.isEmpty())
    }

    @Test
    fun `discussion row without a numeric posts cell defaults to zero`() {
        val html = """
            <div class="event__detail"><table>
            <tr><td><a href="https://www.ravelry.com/discuss/g/42">Topic</a></td></tr>
            </table></div>
        """
        val detail = assertNotNull(EventPageParser.parse(html))
        assertEquals(0, detail.discussions.single().postsCount)
    }
}
