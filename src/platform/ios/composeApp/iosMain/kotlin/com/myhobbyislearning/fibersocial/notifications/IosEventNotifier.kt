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
 * userInfo key: the group that listed the deep-linked event, so its events list can sit
 * under the opened detail and back-back reaches the feed (issue #351). Absent on
 * reminders, which don't know a group — see `DeepLink.Event`.
 *
 * Stored as a decimal string, not a number: userInfo round-trips through `NSDictionary`,
 * where a Kotlin `Long` becomes an `NSNumber` whose Kotlin type on the way back depends
 * on its `objCType`. A string reads back as exactly what was written.
 */
const val NOTIFICATION_EVENT_GROUP_ID_KEY = "event_group_id"

/** userInfo key (presence = true): the tap handler opens the My Posts feed. */
const val NOTIFICATION_OPEN_MY_POSTS_KEY = "open_my_posts"

/**
 * userInfo key: the topic a reply notification is about; the tap handler opens that
 * thread over the My Posts feed. Stored as a decimal string for the same reason as
 * [NOTIFICATION_EVENT_GROUP_ID_KEY].
 */
const val NOTIFICATION_TOPIC_ID_KEY = "topic_id"

/**
 * userInfo key: the conversation a new-message notification is about (issue #375); the tap
 * handler opens the Messages destination. Stored as a decimal string for the same reason
 * as [NOTIFICATION_EVENT_GROUP_ID_KEY].
 */
const val NOTIFICATION_MESSAGE_THREAD_ID_KEY = "message_thread_id"

/**
 * userInfo key: the specific message that arrived, alongside its conversation — see
 * `DeepLink.Message` for why both travel. Decimal string, same convention.
 */
const val NOTIFICATION_MESSAGE_ID_KEY = "message_id"

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
            setUserInfo(
                mapOf(
                    NOTIFICATION_EVENT_PERMALINK_KEY to notification.eventPermalink,
                    NOTIFICATION_EVENT_GROUP_ID_KEY to notification.groupId.toString(),
                ),
            )
        }
        val identifier = "new-event/${notification.eventPermalink}"
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier,
            content = content,
            trigger = null, // deliver now
        )
        center.replaceDelivered(identifier)
        center.addNotificationRequest(request) { error ->
            error?.let { println("FiberSocial: showNewEvent(${notification.eventPermalink}) failed: ${it.localizedDescription}") }
        }
    }

    /**
     * Announces new replies in a topic the user posted in (posts immediately). The
     * identifier is per-topic, so a later batch for the same topic replaces the earlier
     * banner (with a fresh count) while different topics stack — mirroring Android's
     * per-topic tag.
     */
    fun showNewReplies(notification: NewReplyNotification) {
        val content = UNMutableNotificationContent().apply {
            setTitle(MyPostsNotificationContent.replyTitle(notification))
            setBody(MyPostsNotificationContent.replyText(notification))
            setSound(UNNotificationSound.defaultSound)
            // Opens the topic itself, over the My Posts feed so back lands there (#351).
            // iOS has no group-summary notification, so unlike Android there is no
            // spans-several-topics case that has to fall back to the bare feed.
            setUserInfo(mapOf(NOTIFICATION_TOPIC_ID_KEY to notification.topicId.toString()))
            // Coalesces all reply notifications into one Notification Center stack —
            // iOS's own grouping, so no explicit summary notification is needed here
            // (unlike Android's group-summary pattern).
            setThreadIdentifier("my-posts-replies")
        }
        val identifier = "topic-replies/${notification.topicId}"
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier,
            content = content,
            trigger = null, // deliver now
        )
        center.replaceDelivered(identifier)
        center.addNotificationRequest(request) { error ->
            error?.let { println("FiberSocial: showNewReplies(${notification.topicId}) failed: ${it.localizedDescription}") }
        }
    }

    /**
     * Announces new private messages (posts immediately), one banner per CONVERSATION.
     *
     * Takes the whole batch rather than one notification at a time (unlike [showNewReplies],
     * whose input is already one entry per topic): the planner emits one
     * [NewMessageNotification] per *message*, so several messages in one conversation would
     * otherwise become several banners for the same thread. Grouping them here keeps that
     * collapsing rule identical to Android's, in one place per platform.
     *
     * The identifier is per-conversation, so a later batch in the same conversation
     * replaces the earlier banner (with a fresh count) while different conversations stack.
     * `threadIdentifier` coalesces them into one Notification Center stack — iOS's own
     * grouping, so no explicit summary notification is needed (unlike Android's).
     */
    fun showNewMessages(batch: List<NewMessageNotification>) {
        batch.groupBy { it.threadRootId }.forEach { (rootId, messages) ->
            // Newest message is the banner's face; the count covers the rest. Message ids
            // are monotonic on Ravelry, so the max id really is the newest.
            showNewMessage(messages.maxBy { it.messageId }, messages.size, rootId)
        }
    }

    private fun showNewMessage(notification: NewMessageNotification, count: Int, rootId: Long) {
        val content = UNMutableNotificationContent().apply {
            setTitle(MessageNotificationContent.messageTitle(notification))
            setBody(MessageNotificationContent.messageText(notification, count))
            setSound(UNNotificationSound.defaultSound)
            setUserInfo(
                mapOf(
                    NOTIFICATION_MESSAGE_THREAD_ID_KEY to rootId.toString(),
                    NOTIFICATION_MESSAGE_ID_KEY to notification.messageId.toString(),
                ),
            )
            setThreadIdentifier("new-messages")
        }
        val identifier = "message-thread/$rootId"
        val request = UNNotificationRequest.requestWithIdentifier(
            identifier,
            content = content,
            trigger = null, // deliver now
        )
        center.replaceDelivered(identifier)
        center.addNotificationRequest(request) { error ->
            error?.let { println("FiberSocial: showNewMessages($rootId) failed: ${it.localizedDescription}") }
        }
    }

    /**
     * Clears an already-delivered banner with [identifier] so the request that follows
     * lands as a genuine replacement.
     *
     * `addNotificationRequest` only reliably replaces *pending* requests; for one already
     * sitting in Notification Center, identifier-keyed replacement is unreliable in
     * practice and the user can end up with two stacked banners for the same event or
     * topic (an "N new replies" followed by a stale "M new replies"). Removing first makes
     * the replace explicit. Harmless when nothing was delivered.
     */
    private fun UNUserNotificationCenter.replaceDelivered(identifier: String) {
        removeDeliveredNotificationsWithIdentifiers(listOf(identifier))
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
