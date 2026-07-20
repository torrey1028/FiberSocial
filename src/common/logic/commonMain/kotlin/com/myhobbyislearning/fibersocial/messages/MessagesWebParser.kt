package com.myhobbyislearning.fibersocial.messages

import com.fleeksoft.ksoup.Ksoup
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.minus
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Parses Ravelry's *website* message box into [Message]s — the scrape half of the
 * private-message read fallback (issue #396).
 *
 * WHY THIS EXISTS: the JSON API gates every message read (`messages/list`, `messages/show`)
 * behind a `message-read` OAuth scope that Ravelry's authorization servers will not issue
 * to third-party apps — verified live against OAuth 2 (rejected at authorize, silently
 * stripped at token), OAuth 1.0a (silently not granted), and a token carrying every
 * documented scope at once. Writes (`message-write`) and mark-read/unread (no scope at
 * all) work fine, so only the read half comes through here. If Ravelry ever grants the
 * scope, [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.getMessages] resumes
 * using the JSON API automatically and this parser goes cold — do not delete the JSON
 * path in the meantime.
 *
 * WHAT THE WEB SERVES (captured live 2026-07-19, structures under
 * `div.message_row` / `#current_message`):
 *
 * - **Folder pages** (`/people/{username}/messages`, `…/sent`, `…/saved`) render the
 *   ENTIRE folder as `div.message_row` rows — no pagination markup was present even at
 *   36 rows, so [parseFolderPage] returns everything and the caller pages client-side.
 * - **Message bodies** come from the site's own XHR endpoint (`GET /messages/{id}`),
 *   which returns Prototype RJS — JavaScript whose `Element.update("message_container",
 *   "…")` argument is the message HTML as a JS string literal. [parseMessageFragment]
 *   unescapes and parses it. NOTE: Ravelry marks the message read server-side as part of
 *   serving this fragment, exactly like the website's own open action.
 * - **Unread counts** come from `GET /people/message_count.json`
 *   (`{"unread_messages_count":N}`), the same endpoint the site's navigation badge polls;
 *   [parseUnreadCount] reads it.
 *
 * WHAT THE WEB CANNOT PROVIDE: `parent_message_id`. The rows and the fragment carry no
 * numeric parent link (the fragment shows the parent *rendered* inside a `.reply_to`
 * block, but not its id), so every scraped [Message] has a null [Message.parentMessageId]
 * and conversation grouping must fall back to subject+counterpart merging — see
 * `groupIntoThreads`' `mergeBySubjectFallback`.
 *
 * TIMESTAMPS ARE APPROXIMATE: the web renders minute-precision times in the account's
 * own timezone with no offset ("Today 8:03 AM", "July 11, 2026 3:38 AM"). [Message.sentAt]
 * is synthesized in the API's `yyyy/MM/dd HH:mm:ss +0000` shape with `:00` seconds and a
 * zero offset, so absolute instants can be off by the account's UTC offset. Relative
 * ordering — all any caller uses it for — is preserved. "Today"/"Yesterday" resolve
 * against the [LocalDate] the caller passes, which should be the device's current date.
 */
object MessagesWebParser {

    /**
     * Parses one folder page into its rows, newest first (the page's own order).
     *
     * Rows that don't yield a numeric id are skipped rather than guessed at. Read state
     * comes from the `message_row--unread` class (its absence means read); replied state
     * from the `message_row__reply_status--replied` cell.
     *
     * @param html The folder page (`/people/{username}/messages[…]`).
     * @param currentUsername The signed-in user, for orienting sender/recipient: the
     *   row's username cell names the OTHER party — the sender in inbox/archived, the
     *   recipient in sent.
     * @param folder Which box [html] is, deciding that orientation.
     * @param today Device-local current date, for resolving "Today"/"Yesterday".
     */
    fun parseFolderPage(
        html: String,
        currentUsername: String,
        folder: MessageFolder,
        today: LocalDate,
    ): List<Message> {
        val me = RavelryUser(username = currentUsername)
        return Ksoup.parse(html).select("div.message_row").mapNotNull { row ->
            val id = row.id().removePrefix("message_row_").toLongOrNull() ?: return@mapNotNull null
            val other = row.selectFirst(".message_row__username")?.text()?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { username ->
                    RavelryUser(
                        username = username,
                        avatarUrl = row.selectFirst(".message_row___avatar__image")
                            ?.attr("data-delayed-image-src")?.takeIf { it.isNotEmpty() },
                    )
                }
            val outbound = folder == MessageFolder.SENT
            Message(
                id = id,
                subject = row.selectFirst(".message_row__subject a")?.text()?.trim().orEmpty(),
                sender = if (outbound) me else other,
                recipient = if (outbound) other else me,
                sentAt = parseWebTimestamp(row.selectFirst(".message_row__date")?.text(), today),
                readMessage = !row.classNames().contains("message_row--unread"),
                replied = row.selectFirst(".message_row__reply_status--replied") != null,
            )
        }
    }

    /**
     * Parses the RJS fragment served by `GET /messages/{id}` (with
     * `X-Requested-With: XMLHttpRequest`) into a full [Message] — the only scraped source
     * of [Message.contentHtml]. Returns `null` when the fragment doesn't contain the
     * expected `Element.update("message_container", …)` call (a session redirect, an
     * error page, or a markup change).
     *
     * The quoted parent that Ravelry renders inside the body's `.reply_to` block is
     * REMOVED from [Message.contentHtml]: the thread view already shows the parent as its
     * own bubble, and leaving the quote in would display every parent twice.
     *
     * [Message.readMessage] is `true` by construction — Ravelry marks the message read
     * server-side when serving this fragment.
     *
     * @param rjs The raw RJS response.
     * @param messageId The id the fragment was fetched for (the RJS does not repeat it).
     * @param currentUsername Signed-in user; the `.from` link names the sender, and
     *   whichever side isn't the sender is filled in as the other party.
     * @param today Device-local current date, for "Today"/"Yesterday" in the sent line.
     */
    fun parseMessageFragment(
        rjs: String,
        messageId: Long,
        currentUsername: String,
        today: LocalDate,
    ): Message? {
        val html = extractJsStringArgument(rjs, "Element.update(\"message_container\", \"")
            ?: return null
        val doc = Ksoup.parse(html)
        val current = doc.selectFirst("#current_message") ?: return null
        val senderName = current.selectFirst(".summary .from a[href*=/people/]")
            ?.attr("href")?.trimEnd('/')?.substringAfterLast('/')?.takeIf { it.isNotEmpty() }
        val me = RavelryUser(username = currentUsername)
        val sender = senderName?.let { RavelryUser(username = it) }
        val outbound = senderName != null && senderName.equals(currentUsername, ignoreCase = true)
        val content = current.selectFirst(".body .content")?.let { element ->
            val cloned = element.clone()
            cloned.select(".reply_to").remove()
            cloned.html().trim()
        }
        return Message(
            id = messageId,
            subject = current.selectFirst(".body > .subject")?.text()?.trim().orEmpty(),
            sender = sender,
            recipient = if (outbound) null else me,
            sentAt = parseWebTimestamp(
                current.selectFirst(".body > .sent")?.text()?.removePrefix("Sent at"),
                today,
            ),
            readMessage = true,
            contentHtml = content,
        )
    }

    /**
     * Reads `{"unread_messages_count":N}` from `/people/message_count.json`, or `null`
     * when the body isn't that shape (e.g. a login redirect served HTML).
     */
    fun parseUnreadCount(json: String): Int? =
        try {
            countJson.decodeFromString<MessageCountResponse>(json).unreadMessagesCount
        } catch (_: Exception) {
            null
        }

    @Serializable
    private data class MessageCountResponse(
        @SerialName("unread_messages_count") val unreadMessagesCount: Int? = null,
    )

    private val countJson = Json { ignoreUnknownKeys = true }

    // "January" .. "December"; matched by prefix so the abbreviated desktop cells
    // ("Jul 9, 2026") parse identically to the full mobile ones ("July 9, 2026").
    private val MONTH_PREFIXES = listOf(
        "jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec",
    )

    private val TIME_REGEX = Regex("""(\d{1,2}):(\d{2})\s*([AP]M)""", RegexOption.IGNORE_CASE)
    private val ABSOLUTE_DATE_REGEX = Regex("""([A-Za-z]+)\s+(\d{1,2}),\s+(\d{4})""")

    /**
     * Turns the web's date text — time and date tokens in either order, e.g.
     * "Today 8:03 AM" or "11:25 AM July 17, 2026" — into the API timestamp shape
     * (`yyyy/MM/dd HH:mm:00 +0000`), or `null` when nothing recognizable is present.
     * `null` is the honest answer for an unparseable date: `sentAt` is nullable and
     * threads with no parseable timestamp sink rather than sorting as 1970.
     */
    internal fun parseWebTimestamp(text: String?, today: LocalDate): String? {
        if (text == null) return null
        val time = TIME_REGEX.find(text) ?: return null
        var hour = time.groupValues[1].toInt() % 12
        if (time.groupValues[3].equals("PM", ignoreCase = true)) hour += 12
        val minute = time.groupValues[2].toInt()

        val date = when {
            text.contains("Today", ignoreCase = true) -> today
            text.contains("Yesterday", ignoreCase = true) -> today.minus(1, DateTimeUnit.DAY)
            else -> {
                val m = ABSOLUTE_DATE_REGEX.find(text) ?: return null
                val month = MONTH_PREFIXES.indexOfFirst {
                    m.groupValues[1].lowercase().startsWith(it)
                } + 1
                if (month == 0) return null
                try {
                    LocalDate(m.groupValues[3].toInt(), month, m.groupValues[2].toInt())
                } catch (_: IllegalArgumentException) {
                    return null
                }
            }
        }
        return "${date.year.pad(4)}/${date.monthNumber.pad(2)}/${date.dayOfMonth.pad(2)} " +
            "${hour.pad(2)}:${minute.pad(2)}:00 +0000"
    }

    private fun Int.pad(width: Int): String = toString().padStart(width, '0')

    /**
     * Extracts and unescapes the JS string literal that starts right after [marker],
     * scanning to its unescaped closing quote. Handles the escapes Rails' RJS emits:
     * `\"`, `\\`, `\/`, `\'`, `\n`, `\t`, `\r`, `\b`, `\f`, and `\uXXXX`.
     */
    internal fun extractJsStringArgument(js: String, marker: String): String? {
        val start = js.indexOf(marker).takeIf { it >= 0 }?.plus(marker.length) ?: return null
        val out = StringBuilder()
        var i = start
        while (i < js.length) {
            when (val c = js[i]) {
                '"' -> return out.toString()
                '\\' -> {
                    if (i + 1 >= js.length) return null
                    when (val esc = js[i + 1]) {
                        'n' -> out.append('\n')
                        't' -> out.append('\t')
                        'r' -> out.append('\r')
                        'b' -> out.append('\b')
                        'f' -> out.append('\u000C')
                        'u' -> {
                            val hex = js.getOrNull(i + 2)?.let { js.substring(i + 2, (i + 6).coerceAtMost(js.length)) }
                            val code = hex?.takeIf { it.length == 4 }?.toIntOrNull(16) ?: return null
                            out.append(code.toChar())
                            i += 4
                        }
                        else -> out.append(esc) // \" \\ \/ \' and anything else: literal
                    }
                    i += 2
                }
                else -> {
                    out.append(c)
                    i += 1
                }
            }
        }
        return null // ran off the end without a closing quote
    }
}
