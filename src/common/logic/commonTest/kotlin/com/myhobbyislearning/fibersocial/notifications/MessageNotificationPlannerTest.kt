package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.messages.Message
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json

private const val NOW_MS = 1_800_000_000_000L
private const val ME = "yarnie"
private const val DAY_MS = 24L * 60 * 60 * 1000

private fun message(
    id: Long,
    from: String? = "wooliam",
    subject: String = "Subject $id",
    read: Boolean = false,
    parentMessageId: Long? = null,
    contentHtml: String? = null,
) = Message(
    id = id,
    subject = subject,
    sender = from?.let { RavelryUser(username = it) },
    recipient = RavelryUser(username = ME),
    readMessage = read,
    parentMessageId = parentMessageId,
    contentHtml = contentHtml,
)

/** A mark that is inside the retention window, so it counts as seeded. */
private fun seeded(newestMessageId: Long, lastSeenMs: Long = NOW_MS - 1) =
    KnownMessages(newestMessageId = newestMessageId, lastSeenMs = lastSeenMs)

class MessageNotificationPlannerTest {

    // --- Seeding: the highest-risk behaviour in issue #375 ---

    @Test
    fun `the first sync seeds silently instead of announcing the whole inbox`() {
        // THE bug this guards: without the null-mark branch every unread message a user
        // has ever received fires as a notification on the first sync after upgrading.
        val plan = MessageNotificationPlanner.plan(
            knownMessages = null,
            inboxMessages = listOf(message(10L), message(11L), message(12L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        // ...but the mark is seeded to the newest id, so the NEXT sync has a baseline.
        assertEquals(KnownMessages(12L, NOW_MS), plan.newKnownMessages)
    }

    @Test
    fun `a zeroed mark seeds silently too`() {
        // One glitchy sync that persisted a zero mark must not turn the next healthy one
        // into a notification storm (same rule as the event and topic planners).
        val plan = MessageNotificationPlanner.plan(
            knownMessages = KnownMessages(newestMessageId = 0L, lastSeenMs = NOW_MS - 1),
            inboxMessages = listOf(message(10L), message(11L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        assertEquals(11L, plan.newKnownMessages?.newestMessageId)
    }

    @Test
    fun `an empty first sync persists no mark at all`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = null,
            inboxMessages = emptyList(),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        assertNull(plan.newKnownMessages)
    }

    // --- Detection ---

    @Test
    fun `a message above the mark notifies`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L, from = "wooliam", subject = "Yarn swap?")),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        val n = plan.notifications.single()
        assertEquals(11L, n.messageId)
        assertEquals("wooliam", n.senderName)
        assertEquals("Yarn swap?", n.subject)
        assertEquals(11L, plan.newKnownMessages?.newestMessageId)
    }

    @Test
    fun `an already-seen message does not notify`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(11L),
            inboxMessages = listOf(message(10L), message(11L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        assertEquals(11L, plan.newKnownMessages?.newestMessageId)
    }

    @Test
    fun `an unread message that resurfaces from an older page stays quiet`() {
        // The paging case a knownMessages MAP would get wrong: id 5 was never notified
        // (it was off page 1 when we seeded at 10) but it is below the mark, so it is
        // accounted for by construction and must not be announced now.
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(5L), message(12L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals(listOf(12L), plan.notifications.map { it.messageId })
    }

    @Test
    fun `a message the user already read does not notify`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L, read = true)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        // The mark still advances so the next arrival is measured from here.
        assertEquals(11L, plan.newKnownMessages?.newestMessageId)
    }

    @Test
    fun `a message the user sent to themselves does not notify`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L, from = ME)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
    }

    @Test
    fun `a senderless message still notifies with an empty sender name`() {
        // System notices and deleted accounts arrive with no sender; staying silent about
        // a real inbox arrival is the worse of the two failures.
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L, from = null)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals("", plan.notifications.single().senderName)
    }

    @Test
    fun `several new messages each notify oldest first`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(13L), message(11L), message(12L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals(listOf(11L, 12L, 13L), plan.notifications.map { it.messageId })
    }

    @Test
    fun `an empty page leaves a live mark exactly where it was`() {
        // Everything got read: resetting to 0 here would re-seed and then storm.
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = emptyList(),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals(KnownMessages(10L, NOW_MS), plan.newKnownMessages)
    }

    // --- Threading ---

    @Test
    fun `a reply is attributed to the root its page can see`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L), message(12L, parentMessageId = 11L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals(listOf(11L, 11L), plan.notifications.map { it.threadRootId })
    }

    @Test
    fun `an orphaned reply roots itself rather than being dropped`() {
        // The parent is read (so absent from an unread-only page) — the common case.
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(12L, parentMessageId = 99L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals(12L, plan.notifications.single().threadRootId)
    }

    // --- Mutes (the store itself is issue #377) ---

    @Test
    fun `a muted thread is skipped but still advances the mark`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L), message(12L, parentMessageId = 11L)),
            currentUsername = ME,
            nowMs = NOW_MS,
            mutedThreads = setOf(11L),
        )
        assertTrue(plan.notifications.isEmpty())
        // Advancing is what makes an unmute measure from now instead of replaying these.
        assertEquals(12L, plan.newKnownMessages?.newestMessageId)
    }

    @Test
    fun `muting one thread leaves others notifying`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L), message(12L)),
            currentUsername = ME,
            nowMs = NOW_MS,
            mutedThreads = setOf(11L),
        )
        assertEquals(listOf(12L), plan.notifications.map { it.messageId })
    }

    // --- Retention ---

    @Test
    fun `a mark older than the retention window is discarded and re-seeds silently`() {
        // Two months of not opening the app: trusting the stale mark would announce every
        // message that arrived in the meantime in one burst.
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L, lastSeenMs = NOW_MS - 61 * DAY_MS),
            inboxMessages = listOf(message(500L), message(501L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        assertEquals(KnownMessages(501L, NOW_MS), plan.newKnownMessages)
    }

    @Test
    fun `a mark inside the retention window still notifies`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L, lastSeenMs = NOW_MS - 59 * DAY_MS),
            inboxMessages = listOf(message(11L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals(1, plan.notifications.size)
    }

    @Test
    fun `every sync refreshes the retention stamp`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L, lastSeenMs = NOW_MS - 59 * DAY_MS),
            inboxMessages = emptyList(),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals(NOW_MS, plan.newKnownMessages?.lastSeenMs)
    }

    // --- Preview ---

    @Test
    fun `a body-less list entry yields an empty preview`() {
        // The sync doesn't request the full shape, so this is the normal case.
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L)),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals("", plan.notifications.single().preview)
    }

    @Test
    fun `an HTML body is flattened into a plain-text preview`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(
                message(11L, contentHtml = "<p>Hi   there!</p><p>Fancy a <b>swap</b> &amp; a chat?</p>"),
            ),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        assertEquals("Hi there! Fancy a swap & a chat?", plan.notifications.single().preview)
    }

    @Test
    fun `a long body is truncated with an ellipsis`() {
        val plan = MessageNotificationPlanner.plan(
            knownMessages = seeded(10L),
            inboxMessages = listOf(message(11L, contentHtml = "w".repeat(400))),
            currentUsername = ME,
            nowMs = NOW_MS,
        )
        val preview = plan.notifications.single().preview
        assertEquals(140, preview.length)
        assertTrue(preview.endsWith("…"))
    }
}

class NotificationStateMessagesTest {

    @Test
    fun `state saved before the messages leg existed deserializes with no mark`() {
        // A pre-#375 install's stored state has no knownMessages key. It must read back as
        // "never synced" (null) so the first messages sync seeds silently, rather than
        // failing to decode and wiping the other legs' state.
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<NotificationState>(
            """{"knownEvents":{"sunday-circle":1},"scheduledReminders":[],"knownTopics":{}}""",
        )
        assertNull(legacy.knownMessages)
        assertEquals(setOf("sunday-circle"), legacy.knownEvents.keys)
    }

    @Test
    fun `the mark survives a JSON round trip`() {
        val json = Json
        val state = NotificationState(knownMessages = KnownMessages(newestMessageId = 42L, lastSeenMs = NOW_MS))
        assertEquals(state, json.decodeFromString(json.encodeToString(state)))
    }
}

class MessageNotificationContentTest {

    private fun notification(
        senderName: String = "wooliam",
        subject: String = "Yarn swap?",
        preview: String = "",
        threadRootId: Long = 11L,
    ) = NewMessageNotification(
        threadRootId = threadRootId,
        messageId = 11L,
        senderName = senderName,
        subject = subject,
        preview = preview,
    )

    @Test
    fun `the title is the sender`() {
        assertEquals("wooliam", MessageNotificationContent.messageTitle(notification()))
    }

    @Test
    fun `a senderless message falls back to a generic title`() {
        assertEquals("New message", MessageNotificationContent.messageTitle(notification(senderName = "")))
    }

    @Test
    fun `the body prefers the preview and falls back to the subject`() {
        assertEquals("Got any sock yarn?", MessageNotificationContent.messageText(notification(preview = "Got any sock yarn?")))
        assertEquals("Yarn swap?", MessageNotificationContent.messageText(notification()))
        assertEquals("New message", MessageNotificationContent.messageText(notification(subject = "", preview = "")))
    }

    @Test
    fun `several messages in one conversation collapse to a count`() {
        assertEquals("3 new messages", MessageNotificationContent.messageText(notification(), count = 3))
    }

    @Test
    fun `summary text handles singular and plural on both axes`() {
        assertEquals("1 new message in 1 conversation", MessageNotificationContent.summaryText(1, 1))
        assertEquals("5 new messages in 1 conversation", MessageNotificationContent.summaryText(5, 1))
        assertEquals("9 new messages in 4 conversations", MessageNotificationContent.summaryText(9, 4))
    }

    @Test
    fun `message ids can never collide with reminder or new-event ids`() {
        // The two-bit kind field: reminders are 00, new events 10, messages 01. Probe a
        // wide spread of inputs rather than one lucky pair.
        val messageIds = (1L..500L).map { MessageNotificationContent.messageNotificationId(it) }
        val reminderIds = (1..500).map { EventNotificationContent.reminderNotificationId("event-$it") }
        val newEventIds = (1..500).map { EventNotificationContent.newEventNotificationId("event-$it") }

        assertTrue(messageIds.none { it in reminderIds })
        assertTrue(messageIds.none { it in newEventIds })
        assertTrue(
            messageIds.all {
                it and EventNotificationContent.NEW_MESSAGE_ID_MASK != 0 &&
                    it and EventNotificationContent.NEW_EVENT_ID_MASK == 0
            },
        )
    }

    @Test
    fun `the same conversation always derives the same id`() {
        assertEquals(
            MessageNotificationContent.messageNotificationId(42L),
            MessageNotificationContent.messageNotificationId(42L),
        )
    }
}
