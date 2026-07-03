package com.autom8ed.fibersocial.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EventPeopleParserGoldenTest {
    private val attendees = EventPeopleParser.parse(EVENT_PEOPLE_HTML)

    @Test
    fun `parses the attendee from the captured page`() {
        assertEquals(1, attendees.size)
        assertEquals("Megannnnn", attendees.single().username)
    }

    @Test
    fun `blank-skein placeholder maps to a null avatar`() {
        assertNull(attendees.single().avatarUrl)
    }
}

class EventPeopleParserLenienceTest {
    private fun card(avatar: String, details: String) = """
        <div class="event__user_cards"><div class="user_card">
        <div class="avatar c-avatar avatar_bubble">$avatar</div>
        <div class="details">$details</div>
        </div></div>
    """

    @Test
    fun `absolute avatar urls pass through`() {
        val html = card(
            avatar = """<a href="/people/knitwit"><img src="https://cdn.ravelrycache.com/avatars/knitwit.jpg" class="avatar__image"></a>""",
            details = """<a href="https://www.ravelry.com/people/knitwit" class="login">knitwit</a>""",
        )
        assertEquals(
            listOf(EventAttendee(username = "knitwit", avatarUrl = "https://cdn.ravelrycache.com/avatars/knitwit.jpg")),
            EventPeopleParser.parse(html),
        )
    }

    @Test
    fun `site-relative avatar urls get the ravelry origin`() {
        val html = card(
            avatar = """<a href="/people/x"><img src="/avatars/x.jpg" class="avatar__image"></a>""",
            details = """<a href="/people/x" class="login">x</a>""",
        )
        assertEquals("https://www.ravelry.com/avatars/x.jpg", EventPeopleParser.parse(html).single().avatarUrl)
    }

    @Test
    fun `protocol-relative avatar urls get the https scheme, not the ravelry origin`() {
        val html = card(
            avatar = """<a href="/people/x"><img src="//images4.ravelrycache.com/avatars/x.jpg" class="avatar__image"></a>""",
            details = """<a href="/people/x" class="login">x</a>""",
        )
        assertEquals(
            "https://images4.ravelrycache.com/avatars/x.jpg",
            EventPeopleParser.parse(html).single().avatarUrl,
        )
    }

    @Test
    fun `duplicated cards for the same username are deduped`() {
        // The UI keys rows by username, so a page shape that repeats a card must
        // degrade instead of crashing the screen with a duplicate LazyColumn key.
        val one = """<div class="user_card"><div class="details"><a class="login" href="/people/x">x</a></div></div>"""
        val html = """<div class="event__user_cards">$one$one</div>"""
        assertEquals(listOf("x"), EventPeopleParser.parse(html).map { it.username })
    }

    @Test
    fun `card without a username link is skipped`() {
        val html = card(
            avatar = """<img src="/avatars/x.jpg">""",
            details = "no link",
        )
        assertTrue(EventPeopleParser.parse(html).isEmpty())
    }

    @Test
    fun `card without an avatar img yields a null avatar`() {
        val html = card(
            avatar = "",
            details = """<a href="/people/y" class="login">y</a>""",
        )
        assertNull(EventPeopleParser.parse(html).single().avatarUrl)
    }

    @Test
    fun `multiple cards parse in page order`() {
        val html = """
            <div class="event__user_cards">
            <div class="user_card"><div class="details"><a class="login" href="/people/a">a</a></div></div>
            <div class="user_card"><div class="details"><a class="login" href="/people/b">b</a></div></div>
            </div>
        """
        assertEquals(listOf("a", "b"), EventPeopleParser.parse(html).map { it.username })
    }

    @Test
    fun `page without a cards container yields no attendees`() {
        assertTrue(EventPeopleParser.parse("<html><body>nobody yet</body></html>").isEmpty())
        assertTrue(EventPeopleParser.parse("").isEmpty())
    }

    @Test
    fun `user cards outside the events container are ignored`() {
        val html = """
            <div class="sidebar"><div class="user_card">
            <div class="details"><a class="login" href="/people/decoy">decoy</a></div>
            </div></div>
        """
        assertTrue(EventPeopleParser.parse(html).isEmpty())
    }
}
