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
 * How often the user wants the app to check for new events, expressed qualitatively
 * rather than as precise hours: iOS background refresh is opportunistic (the OS decides
 * cadence from usage patterns, not the app), so a precise-hours promise would be
 * untruthful there. Each platform maps a cadence to its own scheduling primitive
 * underneath — Android to a `WorkManager` periodic interval (see `EventSyncWorker`).
 */
@Serializable
enum class PollCadence {
    HOURLY,
    A_FEW_TIMES_A_DAY,
    ONCE_A_DAY;

    companion object {
        /** Buckets a legacy precise-hours setting into the nearest qualitative cadence. */
        fun fromHours(hours: Int): PollCadence = when {
            hours <= 1 -> HOURLY
            hours < 12 -> A_FEW_TIMES_A_DAY
            else -> ONCE_A_DAY
        }
    }
}

/** Human label for a poll cadence choice. */
fun pollCadenceLabel(cadence: PollCadence): String = when (cadence) {
    PollCadence.HOURLY -> "Hourly"
    PollCadence.A_FEW_TIMES_A_DAY -> "A few times a day"
    PollCadence.ONCE_A_DAY -> "Once a day"
}

/**
 * User-configurable notification settings.
 *
 * @property pollCadence How often the background sync scrapes for new events and RSVP
 *   changes. Null before the first migration read (see [effectivePollCadence]).
 */
@Serializable
data class NotificationSettings(
    val pollCadence: PollCadence? = null,
    // Legacy precise-hours setting, superseded by [pollCadence] (issue #113). Retained,
    // internal to this module, purely so a store holding pre-migration JSON migrates to
    // the nearest qualitative bucket via [effectivePollCadence] instead of silently
    // resetting to the default the first time it's next loaded.
    internal val pollIntervalHours: Int = 6,
) {
    /** [pollCadence] if set; otherwise the legacy hours setting bucketed into a cadence. */
    val effectivePollCadence: PollCadence
        get() = pollCadence ?: PollCadence.fromHours(pollIntervalHours)

    companion object {
        val DEFAULT_POLL_CADENCE: PollCadence = PollCadence.A_FEW_TIMES_A_DAY
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
