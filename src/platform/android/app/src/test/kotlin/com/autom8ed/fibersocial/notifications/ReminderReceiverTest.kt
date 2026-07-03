package com.autom8ed.fibersocial.notifications

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderReceiverTest {

    private lateinit var app: Application
    private lateinit var manager: NotificationManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        manager = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    private fun intent(
        permalink: String? = "cozy-meetup",
        title: String? = "Cozy Meetup",
        kind: String? = "SOON",
    ) = Intent(app, ReminderReceiver::class.java).apply {
        permalink?.let { putExtra(EXTRA_EVENT_PERMALINK, it) }
        title?.let { putExtra(ReminderReceiver.EXTRA_EVENT_TITLE, it) }
        kind?.let { putExtra(ReminderReceiver.EXTRA_REMINDER_KIND, it) }
    }

    @Test
    fun `a well-formed alarm intent posts the reminder notification`() {
        ReminderReceiver().onReceive(app, intent())
        val posted = shadowOf(manager).allNotifications.single()
        assertEquals("Starting in 15 minutes", shadowOf(posted).contentTitle)
        assertEquals("Cozy Meetup", shadowOf(posted).contentText)
    }

    @Test
    fun `the day-before kind posts the tomorrow wording`() {
        ReminderReceiver().onReceive(app, intent(kind = "DAY_BEFORE"))
        assertEquals(
            "Tomorrow",
            shadowOf(shadowOf(manager).allNotifications.single()).contentTitle,
        )
    }

    @Test
    fun `intents missing extras are ignored`() {
        ReminderReceiver().onReceive(app, intent(permalink = null))
        ReminderReceiver().onReceive(app, intent(title = null))
        ReminderReceiver().onReceive(app, intent(kind = null))
        ReminderReceiver().onReceive(app, intent(kind = "NOT_A_KIND"))
        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
