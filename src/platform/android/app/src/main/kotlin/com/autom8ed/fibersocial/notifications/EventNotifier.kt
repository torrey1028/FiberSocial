package com.autom8ed.fibersocial.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.autom8ed.fibersocial.MainActivity
import com.autom8ed.fibersocial.R

/** Intent extra carrying an event permalink; MainActivity deep-links to its detail. */
const val EXTRA_EVENT_PERMALINK = "event_permalink"

private const val CHANNEL_REMINDERS = "event_reminders"
private const val CHANNEL_NEW_EVENTS = "new_events"

/**
 * Posts the two kinds of event notifications and owns their channels.
 *
 * Notification IDs are derived from the event permalink so a reminder replaces its
 * earlier sibling for the same event (the T-15m notification supersedes the T-24h one)
 * while different events stack.
 */
class EventNotifier(private val context: Context) {

    /** Creates the notification channels; safe to call repeatedly. */
    fun ensureChannels() {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDERS,
                "Event reminders",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply { description = "Reminders for events you've RSVP'd to" },
        )
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_NEW_EVENTS,
                "New group events",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "New events added to your groups" },
        )
    }

    /** Posts a reminder for an RSVP'd event. */
    fun showReminder(eventPermalink: String, eventTitle: String, kind: ReminderKind) {
        post(
            channel = CHANNEL_REMINDERS,
            id = EventNotificationContent.reminderNotificationId(eventPermalink),
            title = EventNotificationContent.reminderTitle(kind),
            text = eventTitle,
            eventPermalink = eventPermalink,
        )
    }

    /** Announces an event newly added to one of the user's groups. */
    fun showNewEvent(notification: NewEventNotification) {
        post(
            channel = CHANNEL_NEW_EVENTS,
            id = EventNotificationContent.newEventNotificationId(notification.eventPermalink),
            title = EventNotificationContent.newEventTitle(notification.groupName),
            text = EventNotificationContent.newEventText(notification.eventTitle, notification.whenText),
            eventPermalink = notification.eventPermalink,
        )
    }

    private fun post(channel: String, id: Int, title: String, text: String, eventPermalink: String) {
        if (!canNotify()) {
            println("FiberSocial: EventNotifier skipping \"$title\" — notifications not permitted")
            return
        }
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Distinct data URIs keep different events' PendingIntents distinct
            // (Intent.filterEquals) even if their requestCode hashes collide —
            // otherwise a collision would silently rewrite the shared intent's
            // extras and a tap could deep-link to the wrong event.
            data = Uri.parse("fibersocial://event/$eventPermalink")
            putExtra(EXTRA_EVENT_PERMALINK, eventPermalink)
        }
        val pending = PendingIntent.getActivity(
            context,
            id,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification_event)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        // The permalink tag scopes replacement to the same event: the SOON reminder
        // still replaces DAY_BEFORE (same tag + id), but an id hash collision between
        // two different events can no longer replace the wrong notification.
        NotificationManagerCompat.from(context).notify(eventPermalink, id, notification)
    }

    private fun canNotify(): Boolean =
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(context).areNotificationsEnabled()
        }

    companion object {
        /** Set on every new-event ID, cleared on every reminder ID — the two
         *  notification kinds can never collide. Internal for the invariant test. */
        internal const val NEW_EVENT_ID_MASK = EventNotificationContent.NEW_EVENT_ID_MASK
    }
}
