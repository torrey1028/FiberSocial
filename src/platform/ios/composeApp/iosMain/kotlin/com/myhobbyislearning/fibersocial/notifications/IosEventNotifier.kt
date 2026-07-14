package com.myhobbyislearning.fibersocial.notifications

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitHour
import platform.Foundation.NSCalendarUnitMinute
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitSecond
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.dateWithTimeIntervalSince1970
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNAuthorizationOptionSound
import platform.UserNotifications.UNCalendarNotificationTrigger
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationSound
import platform.UserNotifications.UNUserNotificationCenter

/** userInfo key carrying an event permalink; the tap handler deep-links to its detail. */
const val NOTIFICATION_EVENT_PERMALINK_KEY = "event_permalink"

/**
 * Posts the two kinds of event notifications via `UNUserNotificationCenter` — the iOS
 * counterpart of Android's `EventNotifier` + `ReminderScheduler` in one: on iOS a
 * scheduled local notification IS the reminder, fired by the OS with no receiver or
 * boot-rescheduling machinery.
 *
 * Identifiers use the event permalink directly (`new-event/<permalink>`,
 * `reminder/<permalink>/<kind>/<fireAt>`), giving the same stack/replace semantics
 * Android derives from [EventNotificationContent]'s ID math, minus its hash-collision
 * caveats: different events always stack, kinds never collide. One divergence: a
 * delivered DAY_BEFORE banner isn't replaced when SOON fires (both reminders are
 * pre-registered with the OS, so they need distinct identifiers, and delivered-
 * notification replacement keys on the identifier) — the event's two reminders can
 * both sit in Notification Center. Display copy comes from the shared helper.
 */
class IosEventNotifier(
    private val center: UNUserNotificationCenter = UNUserNotificationCenter.currentNotificationCenter(),
) {

    /** Prompts for notification permission (no-op if already decided); logs refusal. */
    fun requestAuthorization() {
        center.requestAuthorizationWithOptions(
            UNAuthorizationOptionAlert or UNAuthorizationOptionSound or UNAuthorizationOptionBadge,
        ) { granted, error ->
            println("FiberSocial: notification authorization granted=$granted error=${error?.localizedDescription ?: "none"}")
        }
    }

    /** Announces an event newly added to one of the user's groups (posts immediately). */
    fun showNewEvent(notification: NewEventNotification) {
        val content = UNMutableNotificationContent().apply {
            setTitle(EventNotificationContent.newEventTitle(notification.groupName))
            setBody(EventNotificationContent.newEventText(notification.eventTitle, notification.whenText))
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(mapOf(NOTIFICATION_EVENT_PERMALINK_KEY to notification.eventPermalink))
        }
        val request = UNNotificationRequest.requestWithIdentifier(
            "new-event/${notification.eventPermalink}",
            content = content,
            trigger = null, // deliver now
        )
        center.addNotificationRequest(request) { error ->
            error?.let { println("FiberSocial: showNewEvent(${notification.eventPermalink}) failed: ${it.localizedDescription}") }
        }
    }

    /**
     * Registers a reminder to fire at [ScheduledReminder.fireAtEpochMs]. Idempotent:
     * re-adding the same identity replaces the identical pending request.
     */
    fun scheduleReminder(reminder: ScheduledReminder) {
        val content = UNMutableNotificationContent().apply {
            setTitle(EventNotificationContent.reminderTitle(reminder.kind))
            setBody(reminder.eventTitle)
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(mapOf(NOTIFICATION_EVENT_PERMALINK_KEY to reminder.eventPermalink))
        }
        val fireDate = NSDate.dateWithTimeIntervalSince1970(reminder.fireAtEpochMs / 1000.0)
        val components = NSCalendar.currentCalendar.components(
            NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay or
                NSCalendarUnitHour or NSCalendarUnitMinute or NSCalendarUnitSecond,
            fromDate = fireDate,
        )
        val request = UNNotificationRequest.requestWithIdentifier(
            reminder.identifier,
            content = content,
            trigger = UNCalendarNotificationTrigger.triggerWithDateMatchingComponents(components, repeats = false),
        )
        center.addNotificationRequest(request) { error ->
            error?.let { println("FiberSocial: scheduleReminder(${reminder.identifier}) failed: ${it.localizedDescription}") }
        }
    }

    /** Cancels a pending reminder; a no-op if it already fired or never existed. */
    fun cancelReminder(reminder: ScheduledReminder) {
        center.removePendingNotificationRequestsWithIdentifiers(listOf(reminder.identifier))
    }

    private val ScheduledReminder.identifier: String
        get() = "reminder/$eventPermalink/$kind/$fireAtEpochMs"
}
