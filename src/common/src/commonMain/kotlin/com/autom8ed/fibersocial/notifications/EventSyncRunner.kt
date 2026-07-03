package com.autom8ed.fibersocial.notifications

import com.autom8ed.fibersocial.events.GroupEvent
import com.autom8ed.fibersocial.feed.RavelryApiClient
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
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

    // Background sync fans out one request per group plus one per saved event; a user
    // in many groups would otherwise fire dozens of simultaneous scrapes (rate-limit
    // bait, and N concurrent 401s can race the token refresh).
    private val scrapeConcurrency = Semaphore(4)

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
            .map { group ->
                async {
                    scrapeConcurrency.withPermit {
                        apiClient.getGroupEvents(group.permalink).map { GroupEvent(group, it) }
                    }
                }
            }
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
        // Recurring events repeat their permalink once per occurrence in the saved
        // list, and the event page carries a single start time — fetch each page once.
        apiClient.getSavedEvents().distinctBy { it.permalink }.map { saved ->
            async {
                scrapeConcurrency.withPermit {
                    val detail = apiClient.getEvent(saved.permalink)
                    SavedEventWithTime(
                        permalink = saved.permalink,
                        title = saved.title,
                        startsAt = detail?.startsAt,
                    )
                }
            }
        }.awaitAll()
    }
}
