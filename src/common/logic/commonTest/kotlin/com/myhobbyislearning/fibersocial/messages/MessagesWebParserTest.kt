package com.myhobbyislearning.fibersocial.messages

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the website message-box parser (issue #396). Fixtures mirror the structures
 * captured live from www.ravelry.com on 2026-07-19 — `div.message_row` rows on folder
 * pages, and the RJS fragment `GET /messages/{id}` serves — with all personal content
 * replaced. If Ravelry changes its markup these fixtures go stale together with the
 * parser; recapture before adjusting either.
 */
class MessagesWebParserTest {

    private val today = LocalDate(2026, 7, 19)

    // --- folder pages ---------------------------------------------------------------

    private val inboxHtml = """
        <html><body><div id="message_list">
        <div class="message_row message_row--unread resizable_table__row swipe_message" id="message_row_101">
          <div class="resizable_table__cell message_row__date rsp_only">
        Today
         8:03 AM
        </div>
          <div class="resizable_table__cell message_row___avatar">
            <img class="message_row___avatar__image rsp_only" data-delayed-image-src="https://avatars.example/wendy_medium.png" src="data:image/gif;base64,x" />
            <div class="message_row__username">WoolyWendy</div>
          </div>
          <div class="resizable_table__cell message_row__subject">
            <a href="/people/me/messages?open=101" onclick="return R.messages.open(101, true);">Yarn swap?</a>
          </div>
          <div class="message_row__reply_status reply_status resizable_table__cell" id="reply_status_101"></div>
        </div>
        <div class="message_row message_row--read resizable_table__row-stripe swipe_message" id="message_row_100">
          <div class="resizable_table__cell message_row__date rsp_only">
        July 11, 2026
         3:38 AM
        </div>
          <div class="resizable_table__cell message_row___avatar">
            <div class="message_row__username">LoopLise</div>
          </div>
          <div class="message_row__subject message_row__subject--read resizable_table__cell">
            <a href="/people/me/messages?open=100" onclick="return R.messages.open(100, true);">Pattern question</a>
          </div>
          <div class="message_row__reply_status message_row__reply_status--replied reply_status resizable_table__cell" id="reply_status_100">
            <img class="icon_16" src="x.svg" title="Replied 2:17 PM" />
          </div>
        </div>
        <div class="message_row message_row--read resizable_table__row swipe_message" id="message_row_">
          <div class="message_row__subject"><a href="#">No id</a></div>
        </div>
        </div></body></html>
    """.trimIndent()

    @Test
    fun `parseFolderPage maps an unread inbox row onto sender and read state`() {
        val messages = MessagesWebParser.parseFolderPage(inboxHtml, "me", MessageFolder.INBOX, today)

        val unread = messages.first { it.id == 101L }
        assertEquals("Yarn swap?", unread.subject)
        assertEquals("WoolyWendy", unread.sender?.username)
        assertEquals("https://avatars.example/wendy_medium.png", unread.sender?.avatarUrl)
        assertEquals("me", unread.recipient?.username)
        assertFalse(unread.readMessage)
        assertFalse(unread.replied)
        assertEquals("2026/07/19 08:03:00 +0000", unread.sentAt)
        assertNull(unread.parentMessageId)
    }

    @Test
    fun `parseFolderPage maps read and replied states and absolute dates`() {
        val messages = MessagesWebParser.parseFolderPage(inboxHtml, "me", MessageFolder.INBOX, today)

        val read = messages.first { it.id == 100L }
        assertTrue(read.readMessage)
        assertTrue(read.replied)
        assertEquals("LoopLise", read.sender?.username)
        assertNull(read.sender?.avatarUrl)
        assertEquals("2026/07/11 03:38:00 +0000", read.sentAt)
    }

    @Test
    fun `parseFolderPage skips rows without a numeric id`() {
        val messages = MessagesWebParser.parseFolderPage(inboxHtml, "me", MessageFolder.INBOX, today)

        assertEquals(listOf(101L, 100L), messages.map { it.id })
    }

    @Test
    fun `parseFolderPage orients sent rows with the current user as sender`() {
        val sentHtml = """
            <div class="message_row message_row--read resizable_table__row swipe_message" id="message_row_200">
              <div class="resizable_table__cell message_row__date rsp_only">Today 9:51 PM</div>
              <div class="resizable_table__cell message_row___avatar">
                <div class="message_row__username message_row__username--no_avatar">KnitPal</div>
              </div>
              <div class="message_row__subject message_row__subject--read resizable_table__cell">
                <a href="/people/me/messages/sent?open=200">Thanks!</a>
              </div>
            </div>
        """.trimIndent()

        val messages = MessagesWebParser.parseFolderPage(sentHtml, "me", MessageFolder.SENT, today)

        val sent = messages.single()
        assertEquals("me", sent.sender?.username)
        assertEquals("KnitPal", sent.recipient?.username)
        assertTrue(sent.readMessage)
    }

    @Test
    fun `parseFolderPage degrades missing cells field by field instead of dropping the row`() {
        // A row with an id is mail, whatever else its markup lost: dropping it would hide
        // a message. Each absent or empty cell degrades to null/empty independently.
        val degradedHtml = """
            <div class="message_row" id="message_row_300">
              <div class="message_row___avatar"><div class="message_row__username"></div></div>
            </div>
            <div class="message_row" id="message_row_301">
              <div class="message_row___avatar">
                <img class="message_row___avatar__image" data-delayed-image-src="" src="x" />
                <div class="message_row__username">BareAvatar</div>
              </div>
            </div>
            <div class="message_row" id="message_row_302"></div>
        """.trimIndent()

        val messages = MessagesWebParser.parseFolderPage(degradedHtml, "me", MessageFolder.INBOX, today)
        assertEquals(listOf(300L, 301L, 302L), messages.map { it.id })

        // An EMPTY username cell means the other party is unknown, not "".
        val emptyName = messages.first { it.id == 300L }
        assertNull(emptyName.sender)
        assertEquals("me", emptyName.recipient?.username)

        // An avatar img whose lazy-load attribute is empty yields no avatar URL.
        val bareAvatar = messages.first { it.id == 301L }
        assertEquals("BareAvatar", bareAvatar.sender?.username)
        assertNull(bareAvatar.sender?.avatarUrl)

        // No cells at all: subject empty, timestamp honestly unknown, still listed.
        val bare = messages.first { it.id == 302L }
        assertNull(bare.sender)
        assertEquals("", bare.subject)
        assertNull(bare.sentAt)
        // No message_row--unread class present means read.
        assertTrue(bare.readMessage)
    }

    // --- timestamps -----------------------------------------------------------------

    @Test
    fun `parseWebTimestamp resolves Today and Yesterday against the supplied date`() {
        assertEquals(
            "2026/07/19 08:03:00 +0000",
            MessagesWebParser.parseWebTimestamp("Today 8:03 AM", today),
        )
        assertEquals(
            "2026/07/18 16:00:00 +0000",
            MessagesWebParser.parseWebTimestamp("Yesterday 4:00 PM", today),
        )
    }

    @Test
    fun `parseWebTimestamp parses absolute dates in either token order`() {
        // Row cells put the date first; the fragment's "Sent at" line puts the time first.
        assertEquals(
            "2026/07/11 03:38:00 +0000",
            MessagesWebParser.parseWebTimestamp("July 11, 2026 3:38 AM", today),
        )
        assertEquals(
            "2026/07/17 11:25:00 +0000",
            MessagesWebParser.parseWebTimestamp("11:25 AM July 17, 2026", today),
        )
        // Abbreviated month names (the desktop date cells) parse identically.
        assertEquals(
            "2026/07/09 13:43:00 +0000",
            MessagesWebParser.parseWebTimestamp("Jul 9, 2026 1:43 PM", today),
        )
    }

    @Test
    fun `parseWebTimestamp maps the 12 hour edges to midnight and noon`() {
        assertEquals(
            "2026/07/19 00:15:00 +0000",
            MessagesWebParser.parseWebTimestamp("Today 12:15 AM", today),
        )
        assertEquals(
            "2026/07/19 12:30:00 +0000",
            MessagesWebParser.parseWebTimestamp("Today 12:30 PM", today),
        )
    }

    @Test
    fun `parseWebTimestamp returns null for unrecognizable text`() {
        assertNull(MessagesWebParser.parseWebTimestamp(null, today))
        assertNull(MessagesWebParser.parseWebTimestamp("", today))
        assertNull(MessagesWebParser.parseWebTimestamp("no time here", today))
        // A time with no recognizable date is unusable too.
        assertNull(MessagesWebParser.parseWebTimestamp("8:03 AM sometime", today))
        assertNull(MessagesWebParser.parseWebTimestamp("Frimaire 11, 2026 3:38 AM", today))
    }

    // --- the RJS fragment -----------------------------------------------------------

    /** JS-escapes [html] the way Rails' RJS responses do. */
    private fun jsEscape(html: String): String = html
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("<", "\\u003C")
        .replace(">", "\\u003E")
        .replace("\n", "\\n")

    private fun fragmentRjs(messageHtml: String): String = """
        removeMarkdownEditor('message_content');
        R.utils.positionContainer("message_container", "message_row_101");
        Element.update("unread_messages_count_v2", "${jsEscape("<span class=\"count\">0</span>")}");
        Element.update("message_container", "${jsEscape(messageHtml)}");
        R.messages.showMessageContainer();
        Element.removeClassName("message_row_101", "message_row--unread");
    """.trimIndent()

    private val inboundMessageHtml = """
        <div class="message" id="current_message">
        <div class="message_contents">
        <div class="summary">
        <div class="from"><strong>from</strong>
        <a href="https://www.ravelry.com/people/WoolyWendy">WoolyWendy</a></div>
        </div>
        <div class="body">
        <div class="subject">Yarn swap?</div>
        <div class="sent">Sent at 8:03 AM Today</div>
        <div class="content markdown message__content">
        <p>Hi "there"!</p>
        <div class="reply_to"><div class="message"><div class="body">
        <div class="subject">Yarn swap?</div>
        <div class="content markdown"><p>the quoted parent</p></div>
        </div></div></div>
        </div>
        </div>
        </div>
        </div>
    """.trimIndent()

    @Test
    fun `parseMessageFragment builds a full message and strips the quoted parent`() {
        val message = MessagesWebParser.parseMessageFragment(
            rjs = fragmentRjs(inboundMessageHtml),
            messageId = 101L,
            currentUsername = "me",
            today = today,
        )

        checkNotNull(message)
        assertEquals(101L, message.id)
        assertEquals("Yarn swap?", message.subject)
        assertEquals("WoolyWendy", message.sender?.username)
        assertEquals("me", message.recipient?.username)
        assertTrue(message.readMessage)
        assertEquals("2026/07/19 08:03:00 +0000", message.sentAt)
        val body = checkNotNull(message.contentHtml)
        assertTrue("""Hi "there"!""" in body)
        assertFalse("quoted parent" in body)
        assertFalse("reply_to" in body)
    }

    @Test
    fun `parseMessageFragment detects an outbound message from the from link`() {
        val outbound = inboundMessageHtml.replace("WoolyWendy", "ME")

        val message = MessagesWebParser.parseMessageFragment(
            rjs = fragmentRjs(outbound),
            messageId = 102L,
            currentUsername = "me",
            today = today,
        )

        checkNotNull(message)
        assertEquals("ME", message.sender?.username)
        // Outbound: we are the sender, and the fragment names no other party.
        assertNull(message.recipient)
    }

    @Test
    fun `parseMessageFragment fills unknowns when sender subject sent line and content are absent`() {
        // A fragment whose body lost its inner markup (a system notice or a markup drift)
        // still yields a renderable Message: unknown sender is NOT treated as outbound, so
        // we remain the recipient, and every unprovable field is null/empty — never a guess.
        val bareHtml = """
            <div class="message" id="current_message">
            <div class="message_contents">
            <div class="summary"><div class="from"><strong>from</strong></div></div>
            <div class="body"></div>
            </div>
            </div>
        """.trimIndent()

        val message = MessagesWebParser.parseMessageFragment(
            rjs = fragmentRjs(bareHtml),
            messageId = 55L,
            currentUsername = "me",
            today = today,
        )

        checkNotNull(message)
        assertEquals(55L, message.id)
        assertNull(message.sender)
        assertEquals("me", message.recipient?.username)
        assertEquals("", message.subject)
        assertNull(message.sentAt)
        assertNull(message.contentHtml)
        // Ravelry marked it read server-side by serving the fragment at all.
        assertTrue(message.readMessage)
    }

    @Test
    fun `parseMessageFragment returns null when the update carries no current message`() {
        // The Element.update call is present but its payload is an error page, not a
        // message — distinct from the missing-marker case, and equally unparseable.
        assertNull(
            MessagesWebParser.parseMessageFragment(
                rjs = fragmentRjs("""<div class="error">Something went wrong</div>"""),
                messageId = 1L,
                currentUsername = "me",
                today = today,
            ),
        )
    }

    @Test
    fun `parseMessageFragment returns null when the container update is missing`() {
        assertNull(
            MessagesWebParser.parseMessageFragment(
                rjs = "alert('login please');",
                messageId = 1L,
                currentUsername = "me",
                today = today,
            ),
        )
    }

    // --- the JS string literal decoder ----------------------------------------------

    @Test
    fun `extractJsStringArgument decodes JS escapes including unicode`() {
        val js = """before Element.update("message_container", "a<b> \"quoted\" back\\slash\nnewline tab\t end"); after"""

        assertEquals(
            "a<b> \"quoted\" back\\slash\nnewline tab\t end",
            MessagesWebParser.extractJsStringArgument(js, "Element.update(\"message_container\", \""),
        )
    }

    @Test
    fun `extractJsStringArgument decodes the whitespace escapes Rails emits`() {
        // \r \b \f are part of Rails' JS-escaping repertoire alongside \n and \t; a
        // decoder missing them would leave literal 'r'/'b'/'f' characters in the body.
        val js = """Element.update("message_container", "one\rtwo\bthree\ffour"); done"""

        assertEquals(
            // \u000C is the form feed; Kotlin string literals have no \f escape.
            "one\rtwo\bthree\u000Cfour",
            MessagesWebParser.extractJsStringArgument(js, "Element.update(\"message_container\", \""),
        )
    }

    @Test
    fun `extractJsStringArgument returns null on a missing marker or unterminated string`() {
        val marker = "Element.update(\"message_container\", \""
        assertNull(MessagesWebParser.extractJsStringArgument("nothing here", marker))
        assertNull(MessagesWebParser.extractJsStringArgument("${marker}never closes", marker))
        assertNull(MessagesWebParser.extractJsStringArgument("${marker}bad \\u00ZZ escape\"", marker))
    }

    @Test
    fun `extractJsStringArgument returns null on escapes cut off by the end of input`() {
        // A truncated response (connection dropped mid-fragment) must yield null, not a
        // crash or a silently mangled body. Three distinct truncation points:
        val marker = "Element.update(\"message_container\", \""
        // ... a lone trailing backslash,
        assertNull(MessagesWebParser.extractJsStringArgument("${marker}dangling\\", marker))
        // ... a \u with no hex digits at all,
        assertNull(MessagesWebParser.extractJsStringArgument("${marker}cut\\u", marker))
        // ... and a \u with fewer than four.
        assertNull(MessagesWebParser.extractJsStringArgument("${marker}cut\\u12", marker))
    }

    // --- the unread counter ---------------------------------------------------------

    @Test
    fun `parseUnreadCount reads the counter and rejects other bodies`() {
        assertEquals(0, MessagesWebParser.parseUnreadCount("""{"unread_messages_count":0}"""))
        assertEquals(3, MessagesWebParser.parseUnreadCount("""{"unread_messages_count":3}"""))
        assertNull(MessagesWebParser.parseUnreadCount("""{"something_else":1}"""))
        assertNull(MessagesWebParser.parseUnreadCount("<html>a login page</html>"))
    }
}
