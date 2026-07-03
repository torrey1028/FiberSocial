package com.autom8ed.fibersocial.notifications

import com.autom8ed.fibersocial.events.GroupEvent
import com.autom8ed.fibersocial.feed.RavelryApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * One event-notification sync cycle: scrape → plan → persist.
 *
 * Pure orchestration over [RavelryApiClient] and [EventNotificationPlanner]; applying
 * the returned [SyncPlan] (posting notifications, alarm scheduling) is the platform
 * worker's job. The state store is updated before returning, so a crash while applying
 * platform effects can drop a notification but never duplicate one.
 */
class EventSyncRunner(
    private val apiClient: RavelryApiClient,
    private val stateStore: NotificationStateStore,
) {

    /**
     * Runs one sync cycle.
     *
     * @param now Current instant.
     * @param timeZone Zone for interpreting venue-local event times (pass the device zone).
     */
    suspend fun sync(now: Instant, timeZone: TimeZone): SyncPlan = coroutineScope {
        val savedDeferred = async { savedEventsWithTimes() }

        val user = apiClient.getCurrentUser()
        val groups = apiClient.getUserGroups(user.username)
        val upcoming: List<GroupEvent> = groups
            .map { group -> async { apiClient.getGroupEvents(group.permalink).map { GroupEvent(group, it) } } }
            .awaitAll()
            .flatten()
            .distinctBy { it.event.permalink }

        val plan = EventNotificationPlanner.plan(
            state = stateStore.load(),
            upcoming = upcoming,
            saved = savedDeferred.await(),
            now = now,
            timeZone = timeZone,
        )
        stateStore.save(plan.newState)
        println(
            "FiberSocial: EventSyncRunner -> ${plan.newEventNotifications.size} new events, " +
                "${plan.remindersToSchedule.size} reminders to schedule, " +
                "${plan.remindersToCancel.size} to cancel",
        )
        plan
    }

    /**
     * The user's RSVP'd events with exact start times. The saved-events listing only
     * carries dates, so each saved event's page is fetched for its time (in parallel;
     * typically 1–5 events).
     */
    private suspend fun savedEventsWithTimes(): List<SavedEventWithTime> = coroutineScope {
        apiClient.getSavedEvents().map { saved ->
            async {
                val detail = apiClient.getEvent(saved.permalink)
                SavedEventWithTime(
                    permalink = saved.permalink,
                    title = saved.title,
                    startsAt = detail?.startsAt,
                )
            }
        }.awaitAll()
    }
}
