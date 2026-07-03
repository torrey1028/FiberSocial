package com.autom8ed.fibersocial.notifications

import com.autom8ed.fibersocial.events.GroupEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** How long a known event is remembered after it was first seen before being pruned. */
private val KNOWN_EVENT_RETENTION = 60.days

/** A "new event was added to your group" notification to post. */
data class NewEventNotification(
    val eventPermalink: String,
    val eventTitle: String,
    val groupName: String,
    val whenText: String,
)

/**
 * An RSVP'd event with its exact start time, the input for reminder planning.
 *
 * @property startsAt Venue-local start from the event page; null when unparseable
 *   (multi-day festivals) — such events get no reminders.
 */
data class SavedEventWithTime(
    val permalink: String,
    val title: String,
    val startsAt: LocalDateTime?,
)

/**
 * What one sync cycle should do: notifications to post, alarms to schedule and cancel,
 * and the state to persist afterwards.
 *
 * Reminder identity is (permalink, kind): a reminder in [remindersToCancel] and
 * [remindersToSchedule] with the same identity is a reschedule (the event's time
 * changed) — cancel first, then schedule.
 */
data class SyncPlan(
    val newEventNotifications: List<NewEventNotification>,
    val remindersToSchedule: List<ScheduledReminder>,
    val remindersToCancel: List<ScheduledReminder>,
    val newState: NotificationState,
)

/**
 * Pure planning logic for event notifications: given what a sync scraped and what the
 * previous sync recorded, decides what to notify, schedule, cancel, and persist. All
 * platform work (posting notifications, AlarmManager) happens outside, in the worker.
 */
object EventNotificationPlanner {

    /**
     * Plans one sync cycle.
     *
     * @param state State persisted by the previous sync; null on the very first sync,
     *   which seeds [NotificationState.knownEvents] without notifying (a fresh install
     *   shouldn't announce every existing event).
     * @param upcoming All upcoming events scraped from the user's groups.
     * @param saved The user's RSVP'd events with start times ([SavedEventWithTime]).
     * @param now Current instant.
     * @param timeZone Zone used to interpret venue-local start times (Ravelry provides
     *   no zone information; callers pass the device zone).
     */
    fun plan(
        state: NotificationState?,
        upcoming: List<GroupEvent>,
        saved: List<SavedEventWithTime>,
        now: Instant,
        timeZone: TimeZone,
    ): SyncPlan {
        val known = state?.knownEvents ?: emptyMap()
        val nowMs = now.toEpochMilliseconds()

        val newEventNotifications = if (state == null) {
            emptyList()
        } else {
            upcoming
                .filter { it.event.permalink !in known }
                .map {
                    NewEventNotification(
                        eventPermalink = it.event.permalink,
                        eventTitle = it.event.title,
                        groupName = it.group.name,
                        whenText = it.event.whenText,
                    )
                }
        }

        val retainedKnown = known.filterValues { firstSeenMs ->
            nowMs - firstSeenMs < KNOWN_EVENT_RETENTION.inWholeMilliseconds
        }
        val newKnown = retainedKnown + upcoming
            .map { it.event.permalink }
            .filter { it !in retainedKnown }
            .associateWith { nowMs }

        val desired = desiredReminders(saved, now, timeZone)
        val previous = state?.scheduledReminders ?: emptyList()
        val previousByIdentity = previous.associateBy { it.eventPermalink to it.kind }
        val desiredByIdentity = desired.associateBy { it.eventPermalink to it.kind }

        val toSchedule = desired.filter { previousByIdentity[it.eventPermalink to it.kind] != it }
        val toCancel = previous.filter { identity ->
            val replacement = desiredByIdentity[identity.eventPermalink to identity.kind]
            // Cancel anything no longer desired or being rescheduled to a new time —
            // but leave past-due entries alone: their alarms already fired.
            replacement != identity && identity.fireAtEpochMs > nowMs
        }

        return SyncPlan(
            newEventNotifications = newEventNotifications,
            remindersToSchedule = toSchedule,
            remindersToCancel = toCancel,
            newState = NotificationState(
                knownEvents = newKnown,
                scheduledReminders = desired,
            ),
        )
    }

    /** Future-only reminders for the saved events, at each supported offset. */
    private fun desiredReminders(
        saved: List<SavedEventWithTime>,
        now: Instant,
        timeZone: TimeZone,
    ): List<ScheduledReminder> = saved.flatMap { event ->
        val startsAt = event.startsAt ?: return@flatMap emptyList()
        val start = startsAt.toInstant(timeZone)
        ReminderKind.entries.mapNotNull { kind ->
            val fireAt = start - kind.offset()
            if (fireAt <= now) return@mapNotNull null
            ScheduledReminder(
                eventPermalink = event.permalink,
                eventTitle = event.title,
                fireAtEpochMs = fireAt.toEpochMilliseconds(),
                kind = kind,
            )
        }
    }

    private fun ReminderKind.offset(): Duration = when (this) {
        ReminderKind.DAY_BEFORE -> 1.days
        ReminderKind.SOON -> 15.minutes
    }
}
