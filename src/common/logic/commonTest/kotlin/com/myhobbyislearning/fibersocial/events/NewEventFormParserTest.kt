package com.myhobbyislearning.fibersocial.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NewEventFormParserTest {
    private val html = """
        <form id="new_event">
        <input name="authenticity_token" type="hidden" value="tok-123">
        <input id="event_creation_id" name="event[creation_id]" type="hidden" value="999999">
        <select id="event_online_event_type_id" name="event[online_event_type_id]">
        <option value="">Choose a category</option>
        <option value="20">Livestream</option>
        <option value="22">Make along</option>
        </select>
        <select id="event_in_person_event_type_id" name="event[in_person_event_type_id]">
        <option value="">Choose a category</option>
        <option value="11">Knitting/crochet group</option>
        </select>
        <select id="event_estimated_attendance" name="event[estimated_attendance]">
        <option value=""></option>
        <option value="0">I don't know</option>
        <option value="300">300</option>
        </select>
        <select id="event_country_id" name="event[country_id]">
        <option value="39">Canada</option>
        <option value="229">United States</option>
        <option value="39">Canada</option>
        <option value="229">United States</option>
        </select>
        <select id="event_start_timezone" name="event[start_timezone]">
        <option value="Pacific Time (US &amp; Canada)">Pacific Time (US &amp; Canada)</option>
        <option value="Alaska">Alaska</option>
        <option value="">not a real zone</option>
        </select>
        </form>
    """

    @Test
    fun `parses the csrf token and draft id`() {
        val form = NewEventFormParser.parse(html)
        assertEquals("tok-123", form?.authenticityToken)
        assertEquals("999999", form?.creationId)
    }

    @Test
    fun `parses each dropdown's options`() {
        val form = NewEventFormParser.parse(html)!!
        assertEquals(listOf(EventOption(20, "Livestream"), EventOption(22, "Make along")), form.onlineCategories)
        assertEquals(listOf(EventOption(11, "Knitting/crochet group")), form.inPersonCategories)
        assertEquals(listOf(EventOption(0, "I don't know"), EventOption(300, "300")), form.estimatedAttendanceOptions)
    }

    @Test
    fun `de-duplicates the country quick-pick list against the full list`() {
        val form = NewEventFormParser.parse(html)!!
        assertEquals(listOf(EventOption(39, "Canada"), EventOption(229, "United States")), form.countries)
    }

    @Test
    fun `blank timezone options are dropped`() {
        val form = NewEventFormParser.parse(html)!!
        assertEquals(listOf("Pacific Time (US & Canada)", "Alaska"), form.timezones)
    }

    @Test
    fun `returns null when the page has no new_event form`() {
        assertNull(NewEventFormParser.parse("<html><body>not a moderator</body></html>"))
    }

    @Test
    fun `returns null when the token or draft id is missing`() {
        val missingToken = """
            <form id="new_event">
            <input id="event_creation_id" name="event[creation_id]" type="hidden" value="1">
            </form>
        """
        assertNull(NewEventFormParser.parse(missingToken))

        val missingCreationId = """
            <form id="new_event">
            <input name="authenticity_token" type="hidden" value="tok">
            </form>
        """
        assertNull(NewEventFormParser.parse(missingCreationId))
    }

    @Test
    fun `missing dropdowns degrade to empty lists`() {
        val minimal = """
            <form id="new_event">
            <input name="authenticity_token" type="hidden" value="tok">
            <input id="event_creation_id" name="event[creation_id]" type="hidden" value="1">
            </form>
        """
        val form = NewEventFormParser.parse(minimal)!!
        assertTrue(form.countries.isEmpty())
        assertTrue(form.onlineCategories.isEmpty())
        assertTrue(form.inPersonCategories.isEmpty())
        assertTrue(form.estimatedAttendanceOptions.isEmpty())
        assertTrue(form.timezones.isEmpty())
    }
}
