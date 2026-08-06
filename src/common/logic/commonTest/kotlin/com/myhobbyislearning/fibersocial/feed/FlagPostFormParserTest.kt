package com.myhobbyislearning.fibersocial.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagPostFormParserTest {

    private val base = "https://www.ravelry.com"

    @Test
    fun `takes the form's own action, hidden fields and control names`() {
        // Issue #467: nothing here is a guess about Rails' naming — whatever the form
        // says is what gets replayed, so a Ravelry-side rename can't 404/422 the report.
        val html = """
            <html><body>
            <form id="new_flag" action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-1">
              <input name="flag[flaggable_type]" type="hidden" value="ForumPost">
              <input name="flag[flaggable_id]" type="hidden" value="555">
              <select id="flag_code" name="flag[code]">
                <option value="">Choose a reason</option>
                <option value="off_topic">Off topic</option>
                <option value="spam">Spam</option>
                <option value="abusive">Abusive or harassing</option>
              </select>
              <textarea id="flag_comment" name="flag[comment]"></textarea>
              <input id="flag_escalate" name="flag[escalate]" type="checkbox" value="1">
              <label for="flag_escalate">Escalate to Ravelry staff</label>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, base, html)

        assertEquals(555L, form?.postId)
        assertEquals("https://www.ravelry.com/flaggings", form?.submitUrl)
        assertEquals("tok-flag-1", form?.authenticityToken)
        assertEquals("ForumPost", form?.fields?.get("flag[flaggable_type]"))
        assertEquals("555", form?.fields?.get("flag[flaggable_id]"))
        assertEquals("flag[code]", form?.reasonFieldName)
        assertEquals(
            listOf(
                FlagReason("off_topic", "Off topic"),
                FlagReason("spam", "Spam"),
                FlagReason("abusive", "Abusive or harassing"),
            ),
            form?.reasons,
        )
        assertEquals("flag[comment]", form?.commentFieldName)
        assertEquals(FlagEscalateField("flag[escalate]", "1"), form?.escalateField)
    }

    @Test
    fun `unescapes an RJS payload before parsing it as HTML`() {
        // What the prepare_flag route actually returns: markup inside a JS string literal.
        val rjs = """Element.update("prepare_flag_contents", """ +
            """"<form action=\"/flaggings\" method=\"post\">""" +
            """<input type=\"hidden\" name=\"authenticity_token\" value=\"tok-rjs\">""" +
            """<select name=\"flag[code]\">""" +
            """<option value=\"spam\">Spam<\/option>""" +
            """<\/select><\/form>");"""

        val form = FlagPostFormParser.parse(555L, base, rjs)

        assertEquals("https://www.ravelry.com/flaggings", form?.submitUrl)
        assertEquals("tok-rjs", form?.authenticityToken)
        assertEquals(listOf(FlagReason("spam", "Spam")), form?.reasons)
    }

    @Test
    fun `parses a radio-shaped reason picker using each radio's own label`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-2">
              <label><input type="radio" name="flag[code]" value="off_topic"> Off topic</label>
              <label><input type="radio" name="flag[code]" value="spam"> Spam</label>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(777L, base, html)

        assertEquals("flag[code]", form?.reasonFieldName)
        assertEquals(
            listOf(FlagReason("off_topic", "Off topic"), FlagReason("spam", "Spam")),
            form?.reasons,
        )
        assertFalse(form?.supportsEscalate == true)
        assertFalse(form?.supportsComment == true)
    }

    @Test
    fun `resolves a radio label by its for attribute when the input isn't wrapped`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-3">
              <input type="radio" id="reason_spam" name="flag[code]" value="spam">
              <label for="reason_spam">Spam</label>
            </form>
            </body></html>
        """.trimIndent()

        assertEquals(listOf(FlagReason("spam", "Spam")), FlagPostFormParser.parse(888L, base, html)?.reasons)
    }

    @Test
    fun `falls back to the radio's own value when its id has no matching label and it isn't wrapped`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-6">
              <input type="radio" id="reason_orphan" name="flag[code]" value="spam">
            </form>
            </body></html>
        """.trimIndent()

        assertEquals(listOf(FlagReason("spam", "spam")), FlagPostFormParser.parse(999L, base, html)?.reasons)
    }

    @Test
    fun `falls back to the radio's own value when its parent is not a label`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-7">
              <div><input type="radio" name="flag[code]" value="spam"></div>
            </form>
            </body></html>
        """.trimIndent()

        assertEquals(listOf(FlagReason("spam", "spam")), FlagPostFormParser.parse(999L, base, html)?.reasons)
    }

    @Test
    fun `does not mistake an escalate toggle for the reason picker`() {
        // A yes/no "who should see this" radio pair sits next to the reason list on
        // Ravelry's own form; picking it as the reasons would report the wrong thing.
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-8">
              <label><input type="radio" name="flag[escalate]" value="0"> Group moderators</label>
              <label><input type="radio" name="flag[escalate]" value="1"> Ravelry staff</label>
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, base, html)

        assertEquals("flag[code]", form?.reasonFieldName)
        assertEquals(listOf(FlagReason("spam", "Spam")), form?.reasons)
        // …and the staff side of that pair is what escalation submits.
        assertEquals(FlagEscalateField("flag[escalate]", "1"), form?.escalateField)
    }

    @Test
    fun `picks the richest control when no name reads like a reason`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-9">
              <select name="flag[kind]">
                <option value="off_topic">Off topic</option>
                <option value="spam">Spam</option>
              </select>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, base, html)

        assertEquals("flag[kind]", form?.reasonFieldName)
        assertEquals(listOf("off_topic", "spam"), form?.reasons?.map { it.id })
    }

    @Test
    fun `carries a named submit button along, as a browser would`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-10">
              <select name="flag[code]"><option value="spam">Spam</option></select>
              <input type="submit" name="commit" value="Report this post">
            </form>
            </body></html>
        """.trimIndent()

        assertEquals("Report this post", FlagPostFormParser.parse(555L, base, html)?.fields?.get("commit"))
    }

    @Test
    fun `keeps an absolute action as-is and resolves a bare one against the base URL`() {
        fun formWith(action: String) = """
            <form action="$action" method="post">
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
        """.trimIndent()

        assertEquals(
            "https://www.ravelry.com/flaggings",
            FlagPostFormParser.parse(555L, base, formWith("https://www.ravelry.com/flaggings"))?.submitUrl,
        )
        assertEquals(
            "https://www.ravelry.com/flaggings",
            FlagPostFormParser.parse(555L, "$base/", formWith("flaggings"))?.submitUrl,
        )
    }

    @Test
    fun `skips a form that has no reason-shaped control and takes the flag form instead`() {
        val html = """
            <html><body>
            <form action="/search" method="get"><input type="text" name="query"></form>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-11">
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()

        assertEquals("https://www.ravelry.com/flaggings", FlagPostFormParser.parse(555L, base, html)?.submitUrl)
    }

    @Test
    fun `takes a comment-ish text input as the comment field, ignoring unrelated ones`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-12">
              <input type="text" name="flag[title]">
              <input type="text" name="flag[comment]">
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()

        assertEquals("flag[comment]", FlagPostFormParser.parse(555L, base, html)?.commentFieldName)
    }

    @Test
    fun `ignores nameless hidden inputs and a valueless submit button`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input type="hidden" value="orphan">
              <input name="authenticity_token" type="hidden" value="tok-flag-13">
              <select name="flag[code]"><option value="spam">Spam</option></select>
              <input type="submit" name="commit">
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, base, html)

        assertEquals(mapOf("authenticity_token" to "tok-flag-13"), form?.fields)
    }

    @Test
    fun `ignores a select whose options are all placeholders and uses the radios instead`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-14">
              <select name="flag[group]"><option value="">Pick one</option></select>
              <label><input type="radio" name="flag[code]" value="spam"> Spam</label>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, base, html)

        assertEquals("flag[code]", form?.reasonFieldName)
        assertEquals(listOf(FlagReason("spam", "Spam")), form?.reasons)
    }

    @Test
    fun `labels an unlabelled option with its own value`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-15">
              <select name="flag[code]"><option value="spam"></option></select>
            </form>
            </body></html>
        """.trimIndent()

        assertEquals(listOf(FlagReason("spam", "spam")), FlagPostFormParser.parse(555L, base, html)?.reasons)
    }

    @Test
    fun `returns null when the flag form is not present on the page`() {
        assertNull(FlagPostFormParser.parse(555L, base, "<html><body>You must be logged in.</body></html>"))
    }

    @Test
    fun `returns null when the form has no usable reason options`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-4">
            </form>
            </body></html>
        """.trimIndent()
        assertNull(FlagPostFormParser.parse(555L, base, html))
    }

    @Test
    fun `returns null when the form has no action to submit to`() {
        // Nothing to POST at — better to fall back to the other reporting channels than
        // to invent a URL, which is what issue #467 was.
        val html = """
            <html><body>
            <form method="post">
              <input name="authenticity_token" type="hidden" value="tok-flag-5">
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()
        assertNull(FlagPostFormParser.parse(555L, base, html))
    }

    @Test
    fun `a token-less form still parses — the client fills the page token in`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, base, html)

        assertTrue(form != null)
        assertNull(form?.authenticityToken)
    }
}
