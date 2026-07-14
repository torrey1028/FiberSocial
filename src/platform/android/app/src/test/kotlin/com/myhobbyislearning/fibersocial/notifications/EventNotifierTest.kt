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
            NewEventNotification("plain-event", "T", "Group", "July 10"),
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
}
