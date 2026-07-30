package com.myhobbyislearning.fibersocial.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagPostFormParserTest {

    @Test
    fun `parses a select-shaped reason picker with the escalate checkbox`() {
        val html = """
            <html><body>
            <form id="new_post_flag" action="/forum_posts/555/flag" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-1">
              <select id="post_flag_reason" name="post_flag[reason]">
                <option value="">Choose a reason</option>
                <option value="off_topic">Off topic</option>
                <option value="spam">Spam</option>
                <option value="abusive">Abusive or harassing</option>
              </select>
              <input id="post_flag_escalate" name="post_flag[escalate]" type="checkbox" value="1">
              <label for="post_flag_escalate">Escalate to Ravelry staff</label>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, html)

        assertEquals(555L, form?.postId)
        assertEquals("tok-flag-1", form?.authenticityToken)
        assertEquals(
            listOf(
                FlagReason("off_topic", "Off topic"),
                FlagReason("spam", "Spam"),
                FlagReason("abusive", "Abusive or harassing"),
            ),
            form?.reasons,
        )
        assertTrue(form?.supportsEscalate == true)
    }

    @Test
    fun `parses a radio-shaped reason picker using each radio's own label`() {
        val html = """
            <html><body>
            <form id="new_post_flag" action="/forum_posts/777/flag" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-2">
              <label><input type="radio" name="post_flag[reason]" value="off_topic"> Off topic</label>
              <label><input type="radio" name="post_flag[reason]" value="spam"> Spam</label>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(777L, html)

        assertEquals(
            listOf(FlagReason("off_topic", "Off topic"), FlagReason("spam", "Spam")),
            form?.reasons,
        )
        assertFalse(form?.supportsEscalate == true)
    }

    @Test
    fun `resolves a radio label by its for attribute when the input isn't wrapped`() {
        val html = """
            <html><body>
            <form id="new_post_flag" action="/forum_posts/888/flag" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-3">
              <input type="radio" id="reason_spam" name="post_flag[reason]" value="spam">
              <label for="reason_spam">Spam</label>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(888L, html)

        assertEquals(listOf(FlagReason("spam", "Spam")), form?.reasons)
    }

    @Test
    fun `falls back to the radio's own value when its id has no matching label and it isn't wrapped`() {
        val html = """
            <html><body>
            <form id="new_post_flag" action="/forum_posts/999/flag" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-6">
              <input type="radio" id="reason_orphan" name="post_flag[reason]" value="spam">
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(999L, html)

        assertEquals(listOf(FlagReason("spam", "spam")), form?.reasons)
    }

    @Test
    fun `falls back to the radio's own value when its parent is not a label`() {
        val html = """
            <html><body>
            <form id="new_post_flag" action="/forum_posts/999/flag" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-7">
              <div><input type="radio" name="post_flag[reason]" value="spam"></div>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(999L, html)

        assertEquals(listOf(FlagReason("spam", "spam")), form?.reasons)
    }

    @Test
    fun `returns null when the flag form is not present on the page`() {
        val html = "<html><body>You must be logged in.</body></html>"
        assertNull(FlagPostFormParser.parse(555L, html))
    }

    @Test
    fun `returns null when the form has no authenticity token`() {
        val html = """
            <html><body>
            <form id="new_post_flag" action="/forum_posts/555/flag" method="post">
              <select name="post_flag[reason]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()
        assertNull(FlagPostFormParser.parse(555L, html))
    }

    @Test
    fun `returns null when the form has no usable reason options`() {
        val html = """
            <html><body>
            <form id="new_post_flag" action="/forum_posts/555/flag" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-4">
            </form>
            </body></html>
        """.trimIndent()
        assertNull(FlagPostFormParser.parse(555L, html))
    }

    @Test
    fun `finds the form by its action attribute when it has no id`() {
        val html = """
            <html><body>
            <form action="/forum_posts/555/flag" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-5">
              <select name="post_flag[reason]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()
        val form = FlagPostFormParser.parse(555L, html)
        assertEquals("tok-flag-5", form?.authenticityToken)
    }
}
