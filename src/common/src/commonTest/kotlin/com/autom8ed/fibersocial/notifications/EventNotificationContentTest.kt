package com.autom8ed.fibersocial.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

class EventNotificationContentTest {
    @Test
    fun `reminder ids clear the mask bit and new-event ids set it`() {
        val trickyPermalink = generateSequence(0) { it + 1 }
            .map { "event-$it" }
            .first { it.hashCode() and EventNotificationContent.NEW_EVENT_ID_MASK != 0 }

        val reminderId = EventNotificationContent.reminderNotificationId(trickyPermalink)
        val newEventId = EventNotificationContent.newEventNotificationId("plain-event")

        assertEquals(0, reminderId and EventNotificationContent.NEW_EVENT_ID_MASK)
        assertNotEquals(0, newEventId and EventNotificationContent.NEW_EVENT_ID_MASK)
    }

    @Test
    fun `reminderNotificationId depends only on permalink so both reminder kinds share an id`() {
        // reminderNotificationId takes no ReminderKind — DAY_BEFORE and SOON reminders for
        // the same event necessarily land on the same id by construction, not by an
        // explicit kind check. This pins that determinism.
        val permalink = "cozy-meetup"
        assertEquals(
            EventNotificationContent.reminderNotificationId(permalink),
            EventNotificationContent.reminderNotificationId(permalink),
        )
    }

    @Test
    fun `reminder titles`() {
        assertEquals("Tomorrow", EventNotificationContent.reminderTitle(ReminderKind.DAY_BEFORE))
        assertEquals("Starting in 15 minutes", EventNotificationContent.reminderTitle(ReminderKind.SOON))
    }

    @Test
    fun `new-event title names the group`() {
        assertEquals(
            "New event in Kirkland Fiber Arts Circle",
            EventNotificationContent.newEventTitle("Kirkland Fiber Arts Circle"),
        )
    }

    @Test
    fun `new-event text joins title and when-text dropping blanks`() {
        assertEquals(
            "Cozy Meetup — July 10, 2026 @ 5:30 PM",
            EventNotificationContent.newEventText("Cozy Meetup", "July 10, 2026 @ 5:30 PM"),
        )
        assertEquals("Cozy Meetup", EventNotificationContent.newEventText("Cozy Meetup", ""))
    }
}
