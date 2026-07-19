package com.myhobbyislearning.fibersocial.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EventNotifierTest {

    private lateinit var app: Application
    private lateinit var notifier: EventNotifier
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifier = EventNotifier(app)
        notifier.ensureChannels()
        manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    @Test
    fun `ensureChannels creates both channels and is idempotent`() {
        notifier.ensureChannels()
        val ids = manager.notificationChannels.map { it.id }
        assertTrue("event_reminders" in ids)
        assertTrue("new_events" in ids)
        assertEquals(
            NotificationManager.IMPORTANCE_HIGH,
            manager.getNotificationChannel("event_reminders").importance,
        )
    }

    @Test
    fun `reminder notifications carry the event title and deep-link extra`() {
        notifier.showReminder("cozy-meetup", "Cozy Meetup", ReminderKind.SOON)

        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Starting in 15 minutes", shadowOf(posted).contentTitle)
        assertEquals("Cozy Meetup", shadowOf(posted).contentText)
        val tapIntent = shadowOf(posted.contentIntent).savedIntent
        assertEquals("cozy-meetup", tapIntent.getStringExtra(EXTRA_EVENT_PERMALINK))
        // Reminders are planned from the RSVP'd-events scrape, which records no group,
        // so back from the opened event goes straight to the feed (issue #351).
        assertFalse(tapIntent.hasExtra(EXTRA_EVENT_GROUP_ID))
    }

    @Test
    fun `new-event notifications carry the group so back lands on its events list`() {
        notifier.showNewEvent(
            NewEventNotification(
                eventPermalink = "cozy-meetup",
                eventTitle = "Cozy Meetup",
                groupName = "Kirkland Fiber Arts Circle",
                groupId = 4242L,
                whenText = "July 10, 2026 @ 5:30 PM",
            ),
        )

        val tapIntent = shadowOf(shadowOf(manager).allNotifications.single().contentIntent).savedIntent
        assertEquals("cozy-meetup", tapIntent.getStringExtra(EXTRA_EVENT_PERMALINK))
        assertEquals(4242L, tapIntent.getLongExtra(EXTRA_EVENT_GROUP_ID, 0L))
    }

    @Test
    fun `the soon reminder replaces the day-before reminder for the same event`() {
        notifier.showReminder("cozy-meetup", "Cozy Meetup", ReminderKind.DAY_BEFORE)
        notifier.showReminder("cozy-meetup", "Cozy Meetup", ReminderKind.SOON)
        assertEquals(1, shadowOf(manager).allNotifications.size)
        assertEquals("Starting in 15 minutes", shadowOf(shadowOf(manager).allNotifications.single()).contentTitle)
    }

    @Test
    fun `new-event notifications name the group and do not collide with reminders`() {
        notifier.showReminder("cozy-meetup", "Cozy Meetup", ReminderKind.SOON)
        notifier.showNewEvent(
            NewEventNotification(
                eventPermalink = "cozy-meetup",
                eventTitle = "Cozy Meetup",
                groupName = "Kirkland Fiber Arts Circle",
                groupId = 4242L,
                whenText = "July 10, 2026 @ 5:30 PM",
            ),
        )

        val all = shadowOf(manager).allNotifications
        assertEquals(2, all.size)
        val titles = all.map { shadowOf(it).contentTitle.toString() }
        assertTrue("New event in Kirkland Fiber Arts Circle" in titles)
        assertNotEquals(titles[0], titles[1])
    }

    @Test
    fun `reminder ids clear the mask bit and new-event ids set it`() {
        // The old xor scheme only flipped the bit, so a reminder for one event could
        // collide with the new-event card of another. The invariant that prevents any
        // cross-type collision: reminders always clear the bit, new events always set it.
        val trickyPermalink = generateSequence(0) { it + 1 }
            .map { "event-$it" }
            .first { it.hashCode() and EventNotifier.NEW_EVENT_ID_MASK != 0 }
        notifier.showReminder(trickyPermalink, "T", ReminderKind.SOON)
        notifier.showNewEvent(
            NewEventNotification("plain-event", "T", "Group", 1L, "July 10"),
        )

        val ids = manager.activeNotifications.map { it.id }
        assertEquals(2, ids.size)
        assertEquals(1, ids.count { it and EventNotifier.NEW_EVENT_ID_MASK == 0 })
        assertEquals(1, ids.count { it and EventNotifier.NEW_EVENT_ID_MASK != 0 })
    }

    @Test
    fun `nothing is posted without the notification permission`() {
        shadowOf(app).denyPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        notifier.showReminder("cozy-meetup", "Cozy Meetup", ReminderKind.SOON)
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    private fun reply(topicId: Long, count: Int, title: String = "Topic $topicId") =
        NewReplyNotification(topicId = topicId, topicTitle = title, groupName = "KAL Hub", newReplyCount = count)

    @Test
    fun `a reply batch posts one child per topic plus a totalling summary`() {
        notifier.showNewReplies(listOf(reply(1L, 2), reply(2L, 3)))

        val active = manager.activeNotifications
        assertEquals(3, active.size)
        val summary = active.single { it.tag == MY_POSTS_SUMMARY_TAG }
        assertTrue(summary.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY != 0)
        assertEquals(
            "5 new replies in 2 topics",
            shadowOf(summary.notification).contentText.toString(),
        )
        // Children and summary share the group so Android stacks them together.
        assertTrue(active.all { it.notification.group == summary.notification.group })
    }

    @Test
    fun `a later batch re-totals the summary across still-visible earlier topics`() {
        notifier.showNewReplies(listOf(reply(1L, 2)))
        // Next sync: a different topic. Topic 1's child is still in the shade, so the
        // summary must count both, not just the new batch.
        notifier.showNewReplies(listOf(reply(2L, 3)))

        val summary = manager.activeNotifications.single { it.tag == MY_POSTS_SUMMARY_TAG }
        assertEquals(
            "5 new replies in 2 topics",
            shadowOf(summary.notification).contentText.toString(),
        )
    }

    @Test
    fun `a re-notified topic replaces its child and overrides its stale count`() {
        notifier.showNewReplies(listOf(reply(1L, 2)))
        // The same topic grows again: its child is replaced (same tag), and the
        // summary uses the fresh count — not 2 + 5.
        notifier.showNewReplies(listOf(reply(1L, 5)))

        val active = manager.activeNotifications
        assertEquals(2, active.size) // one child + the summary
        val summary = active.single { it.tag == MY_POSTS_SUMMARY_TAG }
        assertEquals(
            "5 new replies in 1 topic",
            shadowOf(summary.notification).contentText.toString(),
        )
    }

    @Test
    fun `an empty reply batch posts nothing, not even a summary`() {
        notifier.showNewReplies(emptyList())
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }

    @Test
    fun `a reply child deep-links to its own topic, not just the feed`() {
        notifier.showNewReplies(listOf(reply(7L, 2)))

        val child = manager.activeNotifications.single { it.tag == "topic-7" }
        val tapIntent = shadowOf(child.notification.contentIntent).savedIntent
        assertEquals(7L, tapIntent.getLongExtra(EXTRA_TOPIC_ID, 0L))
        // The child names a topic, so it must NOT also ask for the bare feed — the
        // host reads the extras most-specific-first and topic wins, but carrying both
        // would make the two notification kinds indistinguishable.
        assertFalse(tapIntent.getBooleanExtra(EXTRA_OPEN_MY_POSTS, false))
    }

    @Test
    fun `the group summary still opens the My Posts feed`() {
        // A summary spans several topics, so there is no single right one to open.
        notifier.showNewReplies(listOf(reply(1L, 2), reply(2L, 3)))

        val summary = manager.activeNotifications.single { it.tag == MY_POSTS_SUMMARY_TAG }
        val tapIntent = shadowOf(summary.notification.contentIntent).savedIntent
        assertTrue(tapIntent.getBooleanExtra(EXTRA_OPEN_MY_POSTS, false))
        assertFalse(tapIntent.hasExtra(EXTRA_TOPIC_ID))
    }

    @Test
    fun `two topics' children get distinct PendingIntents`() {
        // PendingIntents are matched by Intent.filterEquals, which ignores extras: if
        // both children shared a data URI they would collapse onto one PendingIntent and
        // FLAG_UPDATE_CURRENT would rewrite the first one's topic id, so tapping either
        // notification would open the same (wrong) topic.
        notifier.showNewReplies(listOf(reply(1L, 2), reply(2L, 3)))

        val first = manager.activeNotifications.single { it.tag == "topic-1" }.notification.contentIntent
        val second = manager.activeNotifications.single { it.tag == "topic-2" }.notification.contentIntent
        assertNotEquals(first, second)
        assertNotEquals(
            shadowOf(first).savedIntent.data,
            shadowOf(second).savedIntent.data,
        )
    }
}
