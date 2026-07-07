package com.autom8ed.fibersocial.notifications

import com.autom8ed.fibersocial.events.GroupEvent
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant

/** How long a known event is remembered after it was last seen before being pruned. */
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
 * Reminder identity is (permalink, kind, fireAtEpochMs) — occurrence-aware, since a
 * recurring RSVP'd event yields one reminder pair per occurrence under one permalink.
 * A moved event therefore appears as a cancel (old time) plus a schedule (new time);
 * cancel first, then schedule. Title-only changes touch no alarms. If an event is
 * postponed after a reminder already fired, the reminder is re-armed for the new time
 * (the user hears "tomorrow" again — deliberate: the old fire no longer applies).
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
     *   shouldn't announce every existing event). A non-null state with an empty known
     *   set seeds silently too — otherwise one glitchy scrape that persisted an empty
     *   list would turn the next healthy sync into a notification storm. Callers must
     *   not call plan()/save when a scrape failed; skip the cycle instead.
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

        val newEventNotifications = if (state == null || known.isEmpty()) {
            emptyList()
        } else {
            upcoming
                .filter { it.event.permalink !in known }
                // An event cross-listed by two of the user's groups is still one event.
                .distinctBy { it.event.permalink }
                .map {
                    NewEventNotification(
                        eventPermalink = it.event.permalink,
                        eventTitle = it.event.title,
                        groupName = it.group.name,
                        whenText = it.event.whenText,
                    )
                }
        }

        val retainedKnown = known.filterValues { lastSeenMs ->
            nowMs - lastSeenMs < KNOWN_EVENT_RETENTION.inWholeMilliseconds
        }
        // Every currently-visible event gets its last-seen stamp refreshed, so an event
        // that stays listed is never pruned; one unseen for the whole retention window
        // is forgotten and would be re-announced on return (deliberate: after 60 days
        // it is news again).
        val newKnown = retainedKnown + upcoming.map { it.event.permalink }.associateWith { nowMs }

        val desired = desiredReminders(saved, now, timeZone)
        val previous = state?.scheduledReminders ?: emptyList()
        val previousIdentities = previous.map { it.identity }.toSet()
        val desiredIdentities = desired.map { it.identity }.toSet()

        val toSchedule = desired.filter { it.identity !in previousIdentities }
        val toCancel = previous.filter {
            // Cancel anything no longer desired (un-RSVP, or a move's old time) — but
            // leave past-due entries alone: their alarms already fired.
            it.identity !in desiredIdentities && it.fireAtEpochMs > nowMs
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
            val fireAt = start - kind.offset
            if (fireAt <= now) return@mapNotNull null
            ScheduledReminder(
                eventPermalink = event.permalink,
                eventTitle = event.title,
                fireAtEpochMs = fireAt.toEpochMilliseconds(),
                kind = kind,
            )
        }
    }.distinct() // the saved list can repeat an occurrence; one reminder each is enough

    /** Occurrence-aware diff identity — title changes alone must not touch alarms. */
    private val ScheduledReminder.identity: Triple<String, ReminderKind, Long>
        get() = Triple(eventPermalink, kind, fireAtEpochMs)
}
