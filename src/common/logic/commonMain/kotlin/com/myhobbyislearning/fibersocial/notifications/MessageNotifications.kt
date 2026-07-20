package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.messages.Message
import com.myhobbyislearning.fibersocial.messages.groupIntoThreads
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.Serializable

/**
 * How long the message high-water mark survives without a sync refreshing it. Matches
 * `KNOWN_EVENT_RETENTION` / `KNOWN_TOPIC_RETENTION` (both 60 days) — see [KnownMessages]
 * for what "retention" means for a mark rather than a map.
 */
private val KNOWN_MESSAGE_RETENTION = 60.days

/**
 * What the previous messages sync had already accounted for.
 *
 * ## Why a high-water mark and not `knownMessages: Map<Long, Long>` (issue #375)
 *
 * The map shape ("every message id we've seen → when we saw it") is what the event and
 * topic legs use, so it was the obvious default. It is the wrong shape here, and the
 * reason is paging, not cost:
 *
 * - The messages leg polls **one page** of `folder=inbox&unread_only=1`. With a map, a
 *   message is "new" precisely when it is ABSENT from the map — so any unread message
 *   that was off page 1 when we last looked and later surfaces onto it (because newer
 *   ones got read or archived) reads as brand new and fires a notification, even though
 *   it has been sitting in the inbox for weeks. A user with a big unread backlog would
 *   get dribbles of that backlog announced indefinitely. Message ids are monotonically
 *   increasing, so a high-water mark cannot make that mistake: anything at or below the
 *   mark is by construction already accounted for, page 1 or not.
 * - The map also grows without bound in exactly the case that hurts (a large permanently
 *   unread inbox), and its retention window makes things worse rather than better —
 *   pruning a still-unread message means re-announcing it 60 days later.
 *
 * Read flags are what make the mark sufficient: we never need to remember *which*
 * messages were seen, only that everything up to [newestMessageId] was, because the
 * server's `unread_only` filter already removes the ones the user dealt with.
 *
 * ## What retention means for a mark
 *
 * There is nothing to prune from a single id, so the 60-day window applies to the mark
 * as a whole: a mark not refreshed within [KNOWN_MESSAGE_RETENTION] (the app went unused
 * for two months) is discarded and the next sync **re-seeds silently**. That is the
 * safe direction — the alternative, trusting a two-month-old mark, would announce every
 * message that arrived in the meantime in one burst. Note this is deliberately the
 * opposite of the events leg, where a forgotten event is re-announced ("after 60 days it
 * is news again"): an event you missed is still worth surfacing, a two-month backlog of
 * private messages is not.
 *
 * @property newestMessageId The largest [Message.id] any sync has observed in the inbox.
 *   `0` means "nothing seen yet" and seeds silently, same as a null [KnownMessages].
 * @property lastSeenMs Epoch millis this mark was last written, for the retention check.
 */
@Serializable
data class KnownMessages(
    val newestMessageId: Long = 0L,
    val lastSeenMs: Long = 0L,
)

/**
 * A "you have a new private message" notification to post.
 *
 * @property threadRootId The conversation this message belongs to, as reconstructed by
 *   [groupIntoThreads] over the polled page. **Best-effort by construction**: an unread-only
 *   inbox page rarely contains a reply's parent (the parent is usually read, or was sent by
 *   us and lives in the `sent` folder), so most messages root themselves and this equals
 *   [messageId]. It is carried anyway because it is the right key for per-thread muting
 *   (#377) and per-thread notification grouping, both of which only need *a* stable
 *   grouping key, not Ravelry's true root.
 * @property messageId The message itself. This — not [threadRootId] — is what the
 *   conversation-detail deep link should resolve against (#371): a screen that fetches
 *   inbox + sent and regroups can always find "the thread containing this message id",
 *   whereas a best-effort root can point at a message that regrouping demotes to a reply.
 * @property senderName The sender's Ravelry username, or empty when the message names no
 *   sender (system notices, deleted accounts — see `MessageThreads.kt`).
 * @property subject The message's own subject line.
 * @property preview First line of the body, or empty. Usually empty in practice: the
 *   list shape carries no body and the sync deliberately does not request the `full`
 *   shape (see [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.getMessages]'s
 *   unresolved `output_format` ambiguity). Display copy therefore falls back to the
 *   subject, and previews light up for free if that call ever starts returning bodies.
 */
data class NewMessageNotification(
    val threadRootId: Long,
    val messageId: Long,
    val senderName: String,
    val subject: String,
    val preview: String,
)

/** What the messages leg of a sync cycle produced. */
data class MessagesPlan(
    val notifications: List<NewMessageNotification>,
    val newKnownMessages: KnownMessages?,
)

/**
 * Pure planning logic for new-private-message notifications: given one page of the user's
 * unread inbox and the high-water mark the previous sync recorded, decides what to notify
 * and what to persist. No I/O, exactly like [MyPostsNotificationPlanner].
 *
 * Follows the same seeding rule as both existing planners: an absent, zeroed, or
 * retention-expired mark seeds silently. Without that, the first sync after upgrading
 * would announce the user's entire unread message history at once — the highest-risk bug
 * in issue #375.
 */
object MessageNotificationPlanner {

    /**
     * Plans the messages leg of one sync cycle.
     *
     * A message notifies only when ALL of these hold: its id is above the previous mark,
     * it is still unread, it travelled INBOUND (the poll asks for the inbox, but a
     * self-addressed message would otherwise announce itself), and its thread is not
     * muted.
     *
     * @param knownMessages Mark persisted by the previous sync; null before the first one.
     * @param inboxMessages One page of `folder=inbox&unread_only=1`, newest first.
     * @param currentUsername The signed-in user's username, for direction detection.
     * @param nowMs Current epoch millis (for the mark's stamp and the retention check).
     * @param mutedThreads Thread root ids the user muted (issue #377 owns the store; this
     *   parameter is how it will be wired, and defaults to muting nothing until then).
     *   A muted thread is skipped for notifying but its messages STILL advance the mark,
     *   so unmuting later measures from now rather than replaying the backlog that
     *   accrued while muted — the same rule [MyPostsNotificationPlanner] applies to muted
     *   topics.
     */
    fun plan(
        knownMessages: KnownMessages?,
        inboxMessages: List<Message>,
        currentUsername: String,
        nowMs: Long,
        mutedThreads: Set<Long> = emptySet(),
    ): MessagesPlan {
        val live = knownMessages?.takeIf { it.isLiveAt(nowMs) }
        val mark = live?.newestMessageId ?: 0L

        val notifications = if (mark == 0L) {
            // FIRST SYNC (or a discarded/expired mark): seed, never announce.
            emptyList()
        } else {
            val rootIds = threadRootIdsByMessageId(inboxMessages, currentUsername)
            inboxMessages
                .filter { it.id > mark && !it.readMessage && it.isInbound(currentUsername) }
                .sortedBy { it.id }
                .mapNotNull { message ->
                    val rootId = rootIds[message.id] ?: message.id
                    if (rootId in mutedThreads) return@mapNotNull null
                    NewMessageNotification(
                        threadRootId = rootId,
                        messageId = message.id,
                        senderName = message.sender?.username.orEmpty(),
                        subject = message.subject,
                        preview = previewOf(message),
                    )
                }
        }

        // The mark advances past everything this page showed us, muted threads included —
        // that is what stops an unmute from replaying a backlog. A page that came back
        // empty leaves a live mark exactly where it was rather than resetting it to 0,
        // which would turn "inbox all read" into a re-seed and then a storm.
        val highest = inboxMessages.maxOfOrNull { it.id } ?: 0L
        val newMark = maxOf(mark, highest)
        val newKnownMessages = if (newMark == 0L) {
            // Nothing seen and nothing remembered: stay unseeded rather than persisting a
            // zero mark, so the state shape says "never synced" as plainly as null does.
            null
        } else {
            KnownMessages(newestMessageId = newMark, lastSeenMs = nowMs)
        }
        return MessagesPlan(notifications = notifications, newKnownMessages = newKnownMessages)
    }

    private fun KnownMessages.isLiveAt(nowMs: Long): Boolean =
        newestMessageId > 0L && nowMs - lastSeenMs < KNOWN_MESSAGE_RETENTION.inWholeMilliseconds

    /**
     * Message id → the id of the thread it belongs to, over the supplied page only.
     * [groupIntoThreads] already does the parent-chain walk, cycle defence, and
     * orphan-roots-itself rule; re-deriving them here would be a second implementation of
     * the same construct that could drift from the one the Messages screens use.
     */
    private fun threadRootIdsByMessageId(
        messages: List<Message>,
        currentUsername: String,
    ): Map<Long, Long> = buildMap {
        for (thread in groupIntoThreads(messages, currentUsername)) {
            for (message in thread.messages) put(message.id, thread.rootId)
        }
    }

    /**
     * A message is ours to announce when we did not send it. A null sender is treated as
     * inbound: it arrived in our inbox, and staying quiet about it would be the worse
     * failure of the two.
     */
    private fun Message.isInbound(currentUsername: String): Boolean {
        val senderName = sender?.username ?: return true
        return !senderName.equals(currentUsername, ignoreCase = true)
    }
}

/**
 * Display copy and ID derivation for new-message notifications, shared by the platform
 * notifiers (like [EventNotificationContent] and [MyPostsNotificationContent]).
 *
 * The notification ID is the thread root folded to an Int and stamped with
 * [EventNotificationContent.NEW_MESSAGE_ID_MASK], so a later message in the same
 * conversation replaces that conversation's earlier notification instead of stacking,
 * while different conversations stack — and no message ID can ever equal a reminder or
 * new-event ID (see [EventNotificationContent] for the two-bit kind field).
 */
object MessageNotificationContent {

    fun messageNotificationId(threadRootId: Long): Int =
        (threadRootId.hashCode() and EventNotificationContent.NEW_EVENT_ID_MASK.inv()) or
            EventNotificationContent.NEW_MESSAGE_ID_MASK

    /** Title of a conversation's notification: who it is from. */
    fun messageTitle(notification: NewMessageNotification): String =
        notification.senderName.ifBlank { "New message" }

    /**
     * Body for a conversation's notification. [count] is how many unread messages of this
     * batch belong to the conversation — more than one collapses to a count rather than
     * showing only the newest and silently dropping the rest.
     */
    fun messageText(notification: NewMessageNotification, count: Int = 1): String {
        if (count > 1) return "$count new messages"
        return notification.preview.ifBlank { notification.subject }.ifBlank { "New message" }
    }

    /** Line for the stack's summary notification, totalled across the visible stack. */
    fun summaryText(totalMessages: Int, conversationCount: Int): String {
        val messages = if (totalMessages == 1) "1 new message" else "$totalMessages new messages"
        val conversations = if (conversationCount == 1) "1 conversation" else "$conversationCount conversations"
        return "$messages in $conversations"
    }
}

/** Max characters of body text carried into a notification preview. */
private const val PREVIEW_MAX_CHARS = 140

/**
 * A one-line plain-text preview of [message]'s body, or empty when it has none.
 *
 * Deliberately a crude tag-strip rather than a trip through the app's HTML parser: this
 * runs in a background sync where the body is usually absent anyway, and a notification
 * line needs no structure, links, or images — only readable text.
 */
private fun previewOf(message: Message): String {
    val body = message.content ?: message.contentHtml ?: return ""
    val text = body
        .replace(Regex("<br\\s*/?>|</p>|</div>", RegexOption.IGNORE_CASE), " ")
        .replace(Regex("<[^>]*>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace(Regex("\\s+"), " ")
        .trim()
    return if (text.length <= PREVIEW_MAX_CHARS) text else text.take(PREVIEW_MAX_CHARS - 1).trimEnd() + "…"
}
