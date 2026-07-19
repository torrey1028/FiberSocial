package com.myhobbyislearning.fibersocial.notifications

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.myhobbyislearning.fibersocial.MainActivity
import com.myhobbyislearning.fibersocial.R

/** Intent extra carrying an event permalink; MainActivity deep-links to its detail. */
const val EXTRA_EVENT_PERMALINK = "event_permalink"

/**
 * Intent extra: the group that listed the deep-linked event, so its events list can sit
 * under the opened detail and back-back reaches the feed (issue #351). Absent on reminder
 * notifications, which don't know a group — see `DeepLink.Event`.
 */
const val EXTRA_EVENT_GROUP_ID = "event_group_id"

/** Intent extra: a tapped reply notification opens the cross-group My Posts feed. */
const val EXTRA_OPEN_MY_POSTS = "open_my_posts"

/**
 * Intent extra carrying the topic a reply-notification child is about; MainActivity
 * deep-links straight into that thread. The group summary spans topics and so carries
 * [EXTRA_OPEN_MY_POSTS] instead.
 */
const val EXTRA_TOPIC_ID = "topic_id"

private const val CHANNEL_REMINDERS = "event_reminders"
private const val CHANNEL_NEW_EVENTS = "new_events"
private const val CHANNEL_MY_POSTS = "my_posts_replies"

/** Notification group collecting the per-topic reply children under one summary. */
private const val GROUP_MY_POSTS_REPLIES = "my_posts_replies_group"

/** Tag of the single group-summary notification (its id is a constant 0). */
internal const val MY_POSTS_SUMMARY_TAG = "my-posts-summary"

/** Child-notification extras key: its unread reply count, read back when summing. */
internal const val EXTRA_REPLY_COUNT = "reply_count"

/** PendingIntent request code for the summary's tap (children use their own ids). */
private const val SUMMARY_REQUEST_CODE = -1

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
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_MY_POSTS,
                "Replies to your topics",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply { description = "New replies in topics you've posted in" },
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
            // Reminders are planned from the RSVP'd-events scrape, which records no
            // group — so back from the opened event goes straight to the feed.
            eventGroupId = null,
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
            eventGroupId = notification.groupId,
        )
    }

    /**
     * Announces new replies in the topics the user posted in, as a notification group:
     * one child per topic (tag = the topic id, so a later batch for the same topic
     * replaces its earlier child with a fresh count while different topics stack) under
     * a summary notification ("9 new replies in 4 topics") that Android renders as an
     * expandable stack. Tapping a child opens that topic's thread over the My Posts feed
     * (so back lands there); tapping the summary opens the My Posts feed itself, since a
     * summary spans topics and has no single right one to open (issue #351).
     */
    fun showNewReplies(batch: List<NewReplyNotification>) {
        if (batch.isEmpty()) return
        if (!canNotify()) {
            println("FiberSocial: EventNotifier skipping ${batch.size} reply notifications — not permitted")
            return
        }
        batch.forEach { showNewReply(it) }
        postReplySummary(batch)
    }

    private fun showNewReply(notification: NewReplyNotification) {
        val id = MyPostsNotificationContent.replyNotificationId(notification.topicId)
        val built = NotificationCompat.Builder(context, CHANNEL_MY_POSTS)
            .setSmallIcon(R.drawable.ic_notification_reply)
            .setContentTitle(MyPostsNotificationContent.replyTitle(notification))
            .setContentText(MyPostsNotificationContent.replyText(notification))
            .setContentIntent(topicTapIntent(id, notification.topicId))
            .setAutoCancel(true)
            .setGroup(GROUP_MY_POSTS_REPLIES)
            // Carried so the summary can total the WHOLE visible stack later, including
            // children posted by earlier syncs that this batch doesn't know about.
            .addExtras(android.os.Bundle().apply { putInt(EXTRA_REPLY_COUNT, notification.newReplyCount) })
            .build()
        NotificationManagerCompat.from(context).notify("topic-${notification.topicId}", id, built)
    }

    /**
     * (Re)posts the group summary. Totals are computed over the union of the currently
     * visible children and this batch — an earlier sync's still-showing topics keep
     * counting, and a batch that re-notifies a visible topic overrides its stale count.
     */
    private fun postReplySummary(batch: List<NewReplyNotification>) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val countsByTag = manager.activeNotifications
            .filter { active ->
                active.notification.group == GROUP_MY_POSTS_REPLIES &&
                    active.notification.flags and android.app.Notification.FLAG_GROUP_SUMMARY == 0
            }
            .associate { active -> active.tag.orEmpty() to active.notification.extras.getInt(EXTRA_REPLY_COUNT, 0) }
            .toMutableMap()
        batch.forEach { countsByTag["topic-${it.topicId}"] = it.newReplyCount }
        val summary = NotificationCompat.Builder(context, CHANNEL_MY_POSTS)
            .setSmallIcon(R.drawable.ic_notification_reply)
            .setContentTitle("New replies")
            .setContentText(MyPostsNotificationContent.summaryText(countsByTag.values.sum(), countsByTag.size))
            .setContentIntent(myPostsTapIntent(SUMMARY_REQUEST_CODE))
            .setAutoCancel(true)
            .setGroup(GROUP_MY_POSTS_REPLIES)
            .setGroupSummary(true)
            .build()
        NotificationManagerCompat.from(context).notify(MY_POSTS_SUMMARY_TAG, 0, summary)
    }

    private fun myPostsTapIntent(requestCode: Int): PendingIntent {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // One shared data URI is fine here (unlike the per-topic URIs below): the
            // only caller is the group summary, and there is exactly one of those.
            data = Uri.parse("fibersocial://my-posts")
            putExtra(EXTRA_OPEN_MY_POSTS, true)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun topicTapIntent(requestCode: Int, topicId: Long): PendingIntent {
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Distinct per topic for the same reason the event path documents below:
            // PendingIntents are matched by Intent.filterEquals, which ignores extras,
            // so a shared URI would let two topics collide onto one PendingIntent and
            // FLAG_UPDATE_CURRENT would silently rewrite the first one's topic id.
            data = Uri.parse("fibersocial://topic/$topicId")
            putExtra(EXTRA_TOPIC_ID, topicId)
        }
        return PendingIntent.getActivity(
            context,
            requestCode,
            tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun post(
        channel: String,
        id: Int,
        title: String,
        text: String,
        eventPermalink: String,
        eventGroupId: Long?,
    ) {
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
            eventGroupId?.let { putExtra(EXTRA_EVENT_GROUP_ID, it) }
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
