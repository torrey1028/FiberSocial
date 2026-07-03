package com.autom8ed.fibersocial.notifications

import com.autom8ed.fibersocial.events.EventSummary
import com.autom8ed.fibersocial.events.GroupEvent
import com.autom8ed.fibersocial.feed.models.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

private val ZONE = TimeZone.of("America/Los_Angeles")
private val NOW = Instant.parse("2026-07-03T12:00:00Z")

private fun group(name: String) =
    Group(id = name.hashCode().toLong(), name = name, permalink = name, forumId = 1L)

private fun upcoming(slug: String, groupName: String = "Kirkland") = GroupEvent(
    group = group(groupName),
    event = EventSummary(
        permalink = slug,
        title = slug.replace('-', ' '),
        startsAt = null,
        whenText = "July 10, 2026 @ 5:30 PM",
        attendeeCount = 1,
    ),
)

/** A saved event starting exactly [fromNow] after NOW, expressed in venue-local time. */
private fun saved(slug: String, fromNow: kotlin.time.Duration) = SavedEventWithTime(
    permalink = slug,
    title = slug.replace('-', ' '),
    startsAt = (NOW + fromNow).toLocalDateTime(ZONE),
)

private fun plan(
    state: NotificationState?,
    upcoming: List<GroupEvent> = emptyList(),
    saved: List<SavedEventWithTime> = emptyList(),
    now: Instant = NOW,
) = EventNotificationPlanner.plan(state, upcoming, saved, now, ZONE)

class NewEventPlanningTest {
    @Test
    fun `first sync seeds known events without notifying`() {
        val result = plan(state = null, upcoming = listOf(upcoming("a"), upcoming("b")))
        assertTrue(result.newEventNotifications.isEmpty())
        assertEquals(setOf("a", "b"), result.newState.knownEvents.keys)
    }

    @Test
    fun `unknown events notify with group and time context`() {
        val state = plan(state = null, upcoming = listOf(upcoming("a"))).newState
        val result = plan(state = state, upcoming = listOf(upcoming("a"), upcoming("b", "Lace Knitters")))
        assertEquals(
            listOf(
                NewEventNotification(
                    eventPermalink = "b",
                    eventTitle = "b",
                    groupName = "Lace Knitters",
                    whenText = "July 10, 2026 @ 5:30 PM",
                ),
            ),
            result.newEventNotifications,
        )
    }

    @Test
    fun `known events do not re-notify`() {
        val state = plan(state = null, upcoming = listOf(upcoming("a"))).newState
        val result = plan(state = state, upcoming = listOf(upcoming("a")))
        assertTrue(result.newEventNotifications.isEmpty())
    }

    @Test
    fun `an event that disappears from the scrape stays known and does not re-notify on return`() {
        val seeded = plan(state = null, upcoming = listOf(upcoming("a"))).newState
        val without = plan(state = seeded, upcoming = emptyList())
        assertTrue("a" in without.newState.knownEvents)
        val returned = plan(state = without.newState, upcoming = listOf(upcoming("a")))
        assertTrue(returned.newEventNotifications.isEmpty())
    }

    @Test
    fun `known events are pruned after the retention window`() {
        val seeded = plan(state = null, upcoming = listOf(upcoming("old"))).newState
        val later = plan(state = seeded, upcoming = emptyList(), now = NOW + 61.days)
        assertTrue(later.newState.knownEvents.isEmpty())
    }

    @Test
    fun `first-seen timestamps survive later syncs`() {
        val seeded = plan(state = null, upcoming = listOf(upcoming("a"))).newState
        val later = plan(state = seeded, upcoming = listOf(upcoming("a")), now = NOW + 1.days)
        assertEquals(seeded.knownEvents["a"], later.newState.knownEvents["a"])
    }
}

class ReminderPlanningTest {
    @Test
    fun `far-future events get both reminders at the right instants`() {
        val start = NOW + 3.days
        val result = plan(state = null, saved = listOf(saved("meetup", 3.days)))
        assertEquals(
            mapOf(
                ReminderKind.DAY_BEFORE to (start - 1.days).toEpochMilliseconds(),
                ReminderKind.SOON to (start - 15.minutes).toEpochMilliseconds(),
            ),
            result.remindersToSchedule.associate { it.kind to it.fireAtEpochMs },
        )
        assertEquals(result.remindersToSchedule, result.newState.scheduledReminders)
    }

    @Test
    fun `events within a day only get the soon reminder`() {
        val result = plan(state = null, saved = listOf(saved("meetup", 3.hours)))
        assertEquals(listOf(ReminderKind.SOON), result.remindersToSchedule.map { it.kind })
    }

    @Test
    fun `events starting within 15 minutes get no reminders`() {
        val result = plan(state = null, saved = listOf(saved("meetup", 10.minutes)))
        assertTrue(result.remindersToSchedule.isEmpty())
    }

    @Test
    fun `saved events without a start time get no reminders`() {
        val result = plan(
            state = null,
            saved = listOf(SavedEventWithTime("festival", "Fiber Festival", startsAt = null)),
        )
        assertTrue(result.remindersToSchedule.isEmpty())
    }

    @Test
    fun `already-scheduled reminders are not rescheduled`() {
        val first = plan(state = null, saved = listOf(saved("meetup", 3.days)))
        val second = plan(state = first.newState, saved = listOf(saved("meetup", 3.days)))
        assertTrue(second.remindersToSchedule.isEmpty())
        assertTrue(second.remindersToCancel.isEmpty())
    }

    @Test
    fun `un-RSVPing cancels pending reminders`() {
        val first = plan(state = null, saved = listOf(saved("meetup", 3.days)))
        val second = plan(state = first.newState, saved = emptyList())
        assertEquals(2, second.remindersToCancel.size)
        assertTrue(second.newState.scheduledReminders.isEmpty())
    }

    @Test
    fun `a moved event reschedules both reminders`() {
        val first = plan(state = null, saved = listOf(saved("meetup", 3.days)))
        val second = plan(state = first.newState, saved = listOf(saved("meetup", 4.days)))
        assertEquals(2, second.remindersToCancel.size)
        assertEquals(2, second.remindersToSchedule.size)
        val newStart = (NOW + 4.days).toEpochMilliseconds()
        assertEquals(
            newStart - 1.days.inWholeMilliseconds,
            second.remindersToSchedule.first { it.kind == ReminderKind.DAY_BEFORE }.fireAtEpochMs,
        )
    }

    @Test
    fun `reminders that already fired are not cancelled`() {
        val first = plan(state = null, saved = listOf(saved("meetup", 3.days)))
        // Two days later the DAY_BEFORE reminder has fired; the user un-RSVPs.
        val second = plan(state = first.newState, saved = emptyList(), now = NOW + 2.days + 1.hours)
        assertEquals(listOf(ReminderKind.SOON), second.remindersToCancel.map { it.kind })
    }

    @Test
    fun `venue-local start times are interpreted in the given zone`() {
        val startLocal = LocalDateTime(2026, 7, 10, 17, 30)
        val result = plan(
            state = null,
            saved = listOf(SavedEventWithTime("meetup", "Meetup", startLocal)),
        )
        val soon = result.remindersToSchedule.first { it.kind == ReminderKind.SOON }
        assertEquals(
            startLocal.toInstant(ZONE).toEpochMilliseconds() - 15.minutes.inWholeMilliseconds,
            soon.fireAtEpochMs,
        )
    }
}
