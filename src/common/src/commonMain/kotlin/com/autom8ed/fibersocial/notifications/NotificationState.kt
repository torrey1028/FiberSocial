package com.autom8ed.fibersocial.notifications

import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.serialization.Serializable

/**
 * Persistent state of the event-notification system, updated by each sync cycle.
 *
 * @property knownEvents Event permalinks already seen in group scrapes, mapped to the
 *   epoch-millis they were last seen (refreshed by every sync that sees them; events
 *   unseen for the retention window are pruned). An event absent from this map is
 *   "new" and triggers a notification.
 * @property scheduledReminders Reminders currently scheduled with the platform's alarm
 *   system. Kept so a sync can compute which alarms to cancel or reschedule when an
 *   event's time changes or an RSVP is withdrawn.
 */
@Serializable
data class NotificationState(
    val knownEvents: Map<String, Long> = emptyMap(),
    val scheduledReminders: List<ScheduledReminder> = emptyList(),
)

/**
 * One reminder scheduled with the platform.
 *
 * @property eventPermalink Event the reminder is for.
 * @property eventTitle Title at scheduling time (used for the notification text).
 * @property fireAtEpochMs When the reminder fires.
 * @property kind Which of the reminder offsets this is.
 */
@Serializable
data class ScheduledReminder(
    val eventPermalink: String,
    val eventTitle: String,
    val fireAtEpochMs: Long,
    val kind: ReminderKind,
)

/**
 * The reminder offsets the feature supports. The [offset] is how long before the
 * event's start the reminder fires (serialization is by name; the offset is code-only).
 */
@Serializable
enum class ReminderKind(val offset: Duration) {
    DAY_BEFORE(1.days),
    SOON(15.minutes),
}

/**
 * User-configurable notification settings.
 *
 * @property pollIntervalHours How often the background sync scrapes for new events and
 *   RSVP changes. The settings UI offers [POLL_INTERVAL_CHOICES]; nothing enforces the
 *   bound on persisted values (a stale/corrupt store must not brick loading), so
 *   schedule computations should read [effectivePollIntervalHours].
 */
@Serializable
data class NotificationSettings(
    val pollIntervalHours: Int = DEFAULT_POLL_INTERVAL_HOURS,
) {
    /** [pollIntervalHours] clamped to the supported choices; the default when off-menu. */
    val effectivePollIntervalHours: Int
        get() = if (pollIntervalHours in POLL_INTERVAL_CHOICES) pollIntervalHours
        else DEFAULT_POLL_INTERVAL_HOURS

    companion object {
        const val DEFAULT_POLL_INTERVAL_HOURS: Int = 6

        /** Intervals offered in settings, in hours. */
        val POLL_INTERVAL_CHOICES: List<Int> = listOf(1, 3, 6, 12, 24)
    }
}

/** Persistence for [NotificationState]; implemented per platform (like `TokenStorage`). */
interface NotificationStateStore {
    /** Returns the stored state, or null before the first completed sync. */
    suspend fun load(): NotificationState?

    suspend fun save(state: NotificationState)
}

/** Persistence for [NotificationSettings]; implemented per platform. */
interface NotificationSettingsStore {
    /** Returns the stored settings, or defaults when none were saved. */
    suspend fun load(): NotificationSettings

    suspend fun save(settings: NotificationSettings)
}
