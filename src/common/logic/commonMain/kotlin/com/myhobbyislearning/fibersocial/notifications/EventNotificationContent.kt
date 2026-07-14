package com.myhobbyislearning.fibersocial.notifications

/**
 * Pure notification-content logic shared by the platform notifier: ID derivation and
 * display copy for the two kinds of event notifications.
 *
 * Notification IDs are derived from the event permalink so a reminder replaces its
 * earlier sibling for the same event (the T-15m notification supersedes the T-24h one)
 * while different events stack. [NEW_EVENT_ID_MASK] is set on every new-event ID and
 * cleared on every reminder ID, so the two kinds can never collide.
 */
object EventNotificationContent {
    const val NEW_EVENT_ID_MASK = 0x40000000

    /** Both reminder kinds share this ID on purpose: the SOON reminder replaces DAY_BEFORE. */
    fun reminderNotificationId(eventPermalink: String): Int =
        eventPermalink.hashCode() and NEW_EVENT_ID_MASK.inv()

    fun newEventNotificationId(eventPermalink: String): Int =
        eventPermalink.hashCode() or NEW_EVENT_ID_MASK

    fun reminderTitle(kind: ReminderKind): String = when (kind) {
        ReminderKind.DAY_BEFORE -> "Tomorrow"
        ReminderKind.SOON -> "Starting in ${kind.offset.inWholeMinutes} minutes"
    }

    fun newEventTitle(groupName: String): String = "New event in $groupName"

    fun newEventText(eventTitle: String, whenText: String): String =
        listOf(eventTitle, whenText).filter { it.isNotBlank() }.joinToString(" — ")
}
