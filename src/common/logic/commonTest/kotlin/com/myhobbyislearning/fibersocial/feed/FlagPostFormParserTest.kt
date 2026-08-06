package com.myhobbyislearning.fibersocial.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagPostFormParserTest {

    private val base = "https://www.ravelry.com"

    @Test
    fun `takes the form's own action and its own hidden fields and control names`() {
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
    fun `parses the real prepare_flag form captured on device for issue 467`() {
        // Verbatim from a device trace of GET /forum_posts/241683005/prepare_flag, with
        // only style/onsubmit attributes stripped. This is the shape the shipped guess
        // got wrong on every count: the form posts to /forum_posts/{id}/flag (POST-only —
        // the GET the app used 404s), the reason field is `flagging[flag_id]` carrying
        // numeric IDs, escalation is a second section of that same radio group rather
        // than a checkbox, and the comment box is `flagging[comment]`.
        val html = """
            <form action="https://www.ravelry.com/forum_posts/241683005/flag" id="flagging_form" method="post">
            <div><input name="authenticity_token" type="hidden" value="tok-real" /></div>
            <fieldset>
            <strong>Report to group moderators</strong>
            <label for="flag_15"><input id="flag_15" name="flagging[flag_id]" type="radio" value="15" /><span>Group rule violation</span></label>
            <label for="flag_12"><input id="flag_12" name="flagging[flag_id]" type="radio" value="12" /><span>Spam</span></label>
            <label for="flag_19"><input checked="checked" id="flag_19" name="flagging[flag_id]" type="radio" value="19" /><span>Other</span></label>
            </fieldset>
            <fieldset>
            <strong>Escalate to Ravelry staff</strong>
            <label for="flag_54"><input id="flag_54" name="flagging[flag_id]" type="radio" value="54" /><span>Abusive or harmful</span></label>
            <label for="flag_60"><input id="flag_60" name="flagging[flag_id]" type="radio" value="60" /><span>Misinformation, hoax</span></label>
            <label for="flag_53"><input id="flag_53" name="flagging[flag_id]" type="radio" value="53" /><span>Suspicious or spam</span></label>
            </fieldset>
            <fieldset>
            <div class="field">Comments (optional)</div>
            <div class="field"><textarea cols="40" id="flagging_comment" name="flagging[comment]" rows="20"></textarea></div>
            </fieldset>
            <fieldset class="rsp_hidden">
            <a class="form_submit__cancel" href="#">cancel</a>
            <button class="clicker_v2" type="submit">save changes</button>
            </fieldset>
            </form>
        """.trimIndent()

        val form = FlagPostFormParser.parse(241683005L, base, html)

        assertEquals("https://www.ravelry.com/forum_posts/241683005/flag", form?.submitUrl)
        assertEquals("tok-real", form?.authenticityToken)
        assertEquals("flagging[flag_id]", form?.reasonFieldName)
        assertEquals("flagging[comment]", form?.commentFieldName)
        assertEquals(
            listOf(
                FlagReason("15", "Group rule violation", "Report to group moderators"),
                FlagReason("12", "Spam", "Report to group moderators"),
                FlagReason("19", "Other", "Report to group moderators"),
                FlagReason("54", "Abusive or harmful", "Escalate to Ravelry staff"),
                FlagReason("60", "Misinformation, hoax", "Escalate to Ravelry staff"),
                FlagReason("53", "Suspicious or spam", "Escalate to Ravelry staff"),
            ),
            form?.reasons,
        )
        // Escalation is a reason section here, not a separate toggle…
        assertNull(form?.escalateField)
        assertTrue(form?.hasGroupedReasons == true)
        // …and the form's own pre-checked option wins over "just take the first".
        assertEquals("19", form?.defaultReasonId)
        assertEquals("19", form?.initialReasonId)
        // The unnamed submit button contributes nothing to the POST, as in a browser.
        assertEquals(mapOf("authenticity_token" to "tok-real"), form?.fields)
    }

    @Test
    fun `falls back to the first option when the form marks no default`() {
        val html = """
            <form action="/flaggings" method="post">
              <select name="flag[code]">
                <option value="spam">Spam</option>
                <option value="other" selected="selected">Other</option>
              </select>
            </form>
        """.trimIndent()
        assertEquals("other", FlagPostFormParser.parse(555L, base, html)?.initialReasonId)

        val noDefault = html.replace(""" selected="selected"""", "")
        assertEquals("spam", FlagPostFormParser.parse(555L, base, noDefault)?.initialReasonId)
    }

    @Test
    fun `groups select options by their optgroup label`() {
        val html = """
            <form action="/flaggings" method="post">
              <select name="flag[code]">
                <optgroup label="Report to group moderators"><option value="spam">Spam</option></optgroup>
                <optgroup label="Escalate to Ravelry staff"><option value="abuse">Abusive</option></optgroup>
              </select>
            </form>
        """.trimIndent()

        assertEquals(
            listOf(
                FlagReason("spam", "Spam", "Report to group moderators"),
                FlagReason("abuse", "Abusive", "Escalate to Ravelry staff"),
            ),
            FlagPostFormParser.parse(555L, base, html)?.reasons,
        )
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
        // …the staff side of that pair is what escalation submits, and the moderators side
        // is carried alongside it: a browser always submits one value of a radio group, so
        // a non-escalated report has to send "0" rather than omitting the field.
        assertEquals(FlagEscalateField("flag[escalate]", "1", offValue = "0"), form?.escalateField)
    }

    @Test
    fun `an escalate checkbox has no off-value — a browser omits an unchecked box`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input id="esc" name="flag[escalate]" type="checkbox" value="1">
              <label for="esc">Escalate to Ravelry staff</label>
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()

        assertEquals(
            FlagEscalateField("flag[escalate]", "1", offValue = null),
            FlagPostFormParser.parse(555L, base, html)?.escalateField,
        )
    }

    @Test
    fun `prefers a comment-shaped textarea name over an earlier unrelated textarea`() {
        // The user's free-text account of the abuse is the one field that must not land
        // somewhere it wasn't meant to, so the name wins over document order.
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <textarea name="flagging[preview]"></textarea>
              <textarea name="flagging[comment]"></textarea>
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()

        assertEquals("flagging[comment]", FlagPostFormParser.parse(555L, base, html)?.commentFieldName)
    }

    @Test
    fun `still takes a lone textarea whose name reads like nothing in particular`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <textarea name="flagging[blurb]"></textarea>
              <select name="flag[code]"><option value="spam">Spam</option></select>
            </form>
            </body></html>
        """.trimIndent()

        assertEquals("flagging[blurb]", FlagPostFormParser.parse(555L, base, html)?.commentFieldName)
    }

    @Test
    fun `a blank pre-checked option is no default at all`() {
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <label><input type="radio" name="flag[code]" value="" checked> Choose one</label>
              <label><input type="radio" name="flag[code]" value="spam"> Spam</label>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, base, html)

        assertNull(form?.defaultReasonId)
        assertEquals("spam", form?.initialReasonId)
    }

    @Test
    fun `falls back to the first option when the marked default isn't one of them`() {
        // The default and the option list come from separate walks of the form, so they
        // can disagree — here a stray checked radio shares the select's name. Preselecting
        // an id no radio button carries would leave the dialog with nothing selected and
        // the Report button dead, so the mismatch has to fall back rather than propagate.
        val html = """
            <html><body>
            <form action="/flaggings" method="post">
              <input type="radio" name="flag[code]" value="stale" checked>
              <select name="flag[code]">
                <option value="spam">Spam</option>
                <option value="other">Other</option>
              </select>
            </form>
            </body></html>
        """.trimIndent()

        val form = FlagPostFormParser.parse(555L, base, html)

        assertEquals(listOf("spam", "other"), form?.reasons?.map { it.id })
        assertEquals("stale", form?.defaultReasonId)
        assertEquals("spam", form?.initialReasonId)
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
    fun `carries a named submit button along as a browser would`() {
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
    fun `takes a comment-ish text input as the comment field and ignores unrelated ones`() {
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
