package com.myhobbyislearning.fibersocial.events

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class EventCreateResponseParserTest {

    @Test
    fun `success extracts the permalink and the embedded venue form from the nested edit forms`() {
        // Mirrors docs/samples/event_create_response.html: Ravelry nests two
        // edit_event_{id} forms (a step=venue wrapper around the venue fields), each
        // repeating the same hidden _method/authenticity_token pair.
        val html = """
            <form action="/events/another-test-event" class="medium_form" id="edit_event_71975" method="post">
            <input name="_method" type="hidden" value="put" />
            <input name="authenticity_token" type="hidden" value="FAKE_TOKEN_abc123==" />
            <input id="step" name="step" type="hidden" value="venue" />
            <form action="/events/another-test-event" class="edit_event" id="edit_event_71975" method="post">
            <input name="_method" type="hidden" value="put" />
            <input name="authenticity_token" type="hidden" value="FAKE_TOKEN_abc123==" />
            <input id="event_venue_name" name="event[venue_name]" type="text" />
            </form>
            </form>
        """
        val result = assertIs<EventCreateResult.Success>(EventCreateResponseParser.parse(html))
        assertEquals("another-test-event", result.permalink)
        val venueForm = assertNotNull(result.venueForm)
        assertEquals("/events/another-test-event", venueForm.action)
        assertEquals(
            listOf(
                "_method" to "put",
                "authenticity_token" to "FAKE_TOKEN_abc123==",
                "step" to "venue",
            ),
            venueForm.hiddenFields,
        )
    }

    @Test
    fun `success without hidden fields yields a null venue form`() {
        val html = """
            <form id="edit_event_71975" action="/events/another-test-event" method="post">
            </form>
        """
        val result = assertIs<EventCreateResult.Success>(EventCreateResponseParser.parse(html))
        assertEquals("another-test-event", result.permalink)
        assertNull(result.venueForm)
    }

    @Test
    fun `validation failure re-renders the creation form with an error banner`() {
        val html = """
            <ul class="brief_error_messages pretty_error_messages">
            <strong>Please correct the following errors:</strong>
            <li>City is required</li>
            <li>Country is required</li>
            </ul>
        """
        val result = assertIs<EventCreateResult.ValidationFailed>(EventCreateResponseParser.parse(html))
        assertEquals(listOf("City is required", "Country is required"), result.errors)
    }

    @Test
    fun `unexpected response with neither shape yields a generic message`() {
        val result = assertIs<EventCreateResult.ValidationFailed>(
            EventCreateResponseParser.parse("<html><body>???</body></html>"),
        )
        assertEquals(1, result.errors.size)
    }

    @Test
    fun `parseErrors extracts banner messages and is empty without a banner`() {
        val html = """<ul class="brief_error_messages"><li>Address is required</li></ul>"""
        assertEquals(listOf("Address is required"), EventCreateResponseParser.parseErrors(html))
        assertEquals(emptyList(), EventCreateResponseParser.parseErrors("<html><body>ok</body></html>"))
    }
}
