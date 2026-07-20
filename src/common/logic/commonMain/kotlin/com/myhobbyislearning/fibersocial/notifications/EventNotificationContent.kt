package com.myhobbyislearning.fibersocial.notifications

/**
 * Pure notification-content logic shared by the platform notifier: ID derivation and
 * display copy for the two kinds of event notifications.
 *
 * Notification IDs are derived from the event permalink so a reminder replaces its
 * earlier sibling for the same event (the T-15m notification supersedes the T-24h one)
 * while different events stack.
 *
 * ## The kind bits
 *
 * Bits 30 and 29 of every notification ID this app posts form a two-bit *kind* field, so
 * IDs of different kinds can never collide however their hashes land:
 *
 * | kind | bit 30 ([NEW_EVENT_ID_MASK]) | bit 29 ([NEW_MESSAGE_ID_MASK]) |
 * |---|---|---|
 * | event reminder | 0 | 0 |
 * | new group event | 1 | 0 |
 * | new private message | 0 | 1 |
 *
 * It was a single bit until issue #375 added the messages leg — one bit only separates
 * two kinds, and a third had nowhere collision-free to sit. Widening it can shift an
 * existing ID by one bit, which at worst means a notification already sitting in the
 * shade when the app updates is not replaced by its successor; both platforms also scope
 * replacement by tag/identifier, so nothing can replace the *wrong* notification.
 */
object EventNotificationContent {
    const val NEW_EVENT_ID_MASK = 0x40000000

    /** Set on every new-message ID and cleared on the two event kinds. See the kind bits. */
    const val NEW_MESSAGE_ID_MASK = 0x20000000

    /** Both reminder kinds share this ID on purpose: the SOON reminder replaces DAY_BEFORE. */
    fun reminderNotificationId(eventPermalink: String): Int =
        eventPermalink.hashCode() and NEW_EVENT_ID_MASK.inv() and NEW_MESSAGE_ID_MASK.inv()

    fun newEventNotificationId(eventPermalink: String): Int =
        (eventPermalink.hashCode() and NEW_MESSAGE_ID_MASK.inv()) or NEW_EVENT_ID_MASK

    fun reminderTitle(kind: ReminderKind): String = when (kind) {
        ReminderKind.DAY_BEFORE -> "Tomorrow"
        ReminderKind.SOON -> "Starting in ${kind.offset.inWholeMinutes} minutes"
    }

    fun newEventTitle(groupName: String): String = "New event in $groupName"

    fun newEventText(eventTitle: String, whenText: String): String =
        listOf(eventTitle, whenText).filter { it.isNotBlank() }.joinToString(" — ")
}
