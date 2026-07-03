package com.autom8ed.fibersocial.notifications

import com.autom8ed.fibersocial.feed.FakeFeedTokenStorage
import com.autom8ed.fibersocial.feed.RavelryApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json

private val NOW = Instant.parse("2026-07-03T12:00:00Z")
private val ZONE = TimeZone.of("America/Los_Angeles")

private class InMemoryStateStore(private var state: NotificationState? = null) : NotificationStateStore {
    var saveCount = 0
    override suspend fun load(): NotificationState? = state
    override suspend fun save(state: NotificationState) {
        this.state = state
        saveCount++
    }
}

/**
 * Serves every page the sync touches: current user, memberships, group search, group
 * page (one upcoming event), saved events (one RSVP for July 5), and the event page
 * (5:30 PM start).
 */
private fun syncApiClient(): RavelryApiClient {
    val engine = MockEngine { request ->
        val path = request.url.encodedPath
        val (body, type) = when {
            path.contains("current_user") ->
                """{"user":{"username":"yarnie"}}""" to ContentType.Application.Json
            path.contains("memberships") ->
                """<a href="https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2">K</a>""" to ContentType.Text.Html
            path.contains("groups/search") ->
                """{"groups":[{"id":1,"name":"Kirkland Fiber Arts Circle","permalink":"kirkland-fiber-arts-circle-2","forum_id":9}]}""" to ContentType.Application.Json
            path.endsWith("/events/saved") ->
                """<div class="event_list"><div class="month">July 2026</div>
                   <div class="event"><div class="date"><div class="day">5th</div></div>
                   <div class="details"><a href="https://www.ravelry.com/events/sunday-circle" class="title">Sunday Circle</a></div>
                   </div></div>""" to ContentType.Text.Html
            path.contains("/events/sunday-circle") ->
                """<div class="page_title">Sunday Circle</div>
                   <div class="event__detail"><div class="event__dates">July  5, 2026 @  1:00 PM</div></div>""" to ContentType.Text.Html
            path.contains("/groups/") ->
                """<div id="upcoming_events"><div class="event">
                   <div class="what"><a href="https://www.ravelry.com/events/sunday-circle">Sunday Circle</a></div>
                   <div class="when">July  5, 2026 @  1:00 PM</div>
                   <div class="who"><a href="https://www.ravelry.com/events/sunday-circle/people">1 person</a></div>
                   </div></div>""" to ContentType.Text.Html
            else -> error("Unexpected path: $path")
        }
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", type.toString()))
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, FakeFeedTokenStorage())
}

class EventSyncRunnerTest {

    @Test
    fun `first sync seeds known events silently and schedules RSVP reminders`() = runTest {
        val store = InMemoryStateStore()
        val plan = EventSyncRunner(syncApiClient(), store).sync(NOW, ZONE)

        assertTrue(plan.newEventNotifications.isEmpty())
        assertEquals(setOf("sunday-circle"), plan.newState.knownEvents.keys)
        // July 5 1:00 PM PDT is under two days away: both reminders are in the future.
        assertEquals(
            setOf(ReminderKind.DAY_BEFORE, ReminderKind.SOON),
            plan.remindersToSchedule.map { it.kind }.toSet(),
        )
        assertEquals("Sunday Circle", plan.remindersToSchedule.first().eventTitle)
        assertEquals(1, store.saveCount)
    }

    @Test
    fun `second sync over unchanged data is a no-op plan`() = runTest {
        val store = InMemoryStateStore()
        val runner = EventSyncRunner(syncApiClient(), store)
        runner.sync(NOW, ZONE)
        val second = runner.sync(NOW, ZONE)

        assertTrue(second.newEventNotifications.isEmpty())
        assertTrue(second.remindersToSchedule.isEmpty())
        assertTrue(second.remindersToCancel.isEmpty())
        assertEquals(2, store.saveCount)
    }

    @Test
    fun `state saved by one runner is visible to the next`() = runTest {
        val store = InMemoryStateStore()
        EventSyncRunner(syncApiClient(), store).sync(NOW, ZONE)
        // A brand-new runner (fresh worker process) sees the seeded state.
        val plan = EventSyncRunner(syncApiClient(), store).sync(NOW, ZONE)
        assertTrue(plan.newEventNotifications.isEmpty())
    }
}
