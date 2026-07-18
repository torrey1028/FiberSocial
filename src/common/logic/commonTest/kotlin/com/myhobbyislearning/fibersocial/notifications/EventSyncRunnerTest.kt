package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.feed.FakeFeedTokenStorage
import com.myhobbyislearning.fibersocial.feed.RavelryApiClient
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
import kotlin.time.Instant
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
            path.contains("filtered_topics") ->
                """{"topics":[]}""" to ContentType.Application.Json
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
    fun `groups are scraped concurrently and a shared event is deduplicated`() = runTest {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val (body, type) = when {
                path.contains("filtered_topics") ->
                    """{"topics":[]}""" to ContentType.Application.Json
                path.contains("current_user") ->
                    """{"user":{"username":"yarnie"}}""" to ContentType.Application.Json
                path.contains("memberships") ->
                    """<a href="https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2">K</a>
                       <a href="https://www.ravelry.com/groups/sock-knitters">S</a>""" to ContentType.Text.Html
                path.contains("groups/search") -> {
                    val query = request.url.parameters["query"].orEmpty()
                    if (query.contains("sock")) {
                        """{"groups":[{"id":2,"name":"Sock Knitters","permalink":"sock-knitters","forum_id":8}]}""" to ContentType.Application.Json
                    } else {
                        """{"groups":[{"id":1,"name":"Kirkland Fiber Arts Circle","permalink":"kirkland-fiber-arts-circle-2","forum_id":9}]}""" to ContentType.Application.Json
                    }
                }
                path.endsWith("/events/saved") ->
                    """<div class="event_list"></div>""" to ContentType.Text.Html
                path.contains("/groups/kirkland") ->
                    // Both groups list the same event — the plan must not double-schedule it.
                    """<div id="upcoming_events"><div class="event">
                       <div class="what"><a href="https://www.ravelry.com/events/shared-event">Shared</a></div>
                       <div class="when">July  5, 2026 @  1:00 PM</div>
                       <div class="who"><a href="https://www.ravelry.com/events/shared-event/people">1 person</a></div>
                       </div></div>""" to ContentType.Text.Html
                path.contains("/groups/sock-knitters") ->
                    """<div id="upcoming_events"><div class="event">
                       <div class="what"><a href="https://www.ravelry.com/events/shared-event">Shared</a></div>
                       <div class="when">July  5, 2026 @  1:00 PM</div>
                       <div class="who"><a href="https://www.ravelry.com/events/shared-event/people">1 person</a></div>
                       </div></div>""" to ContentType.Text.Html
                else -> error("Unexpected path: $path")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", type.toString()))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val runner = EventSyncRunner(RavelryApiClient(client, FakeFeedTokenStorage()), InMemoryStateStore())

        val plan = runner.sync(NOW, ZONE)

        assertEquals(setOf("shared-event"), plan.newState.knownEvents.keys)
    }

    @Test
    fun `sync propagates a network failure without saving partial state`() = runTest {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("current_user")) {
                throw RuntimeException("network unreachable")
            }
            respond("<div class=\"event_list\"></div>", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Text.Html.toString()))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val store = InMemoryStateStore()
        val runner = EventSyncRunner(RavelryApiClient(client, FakeFeedTokenStorage()), store)

        val result = runCatching { runner.sync(NOW, ZONE) }

        assertTrue(result.isFailure)
        assertEquals(0, store.saveCount)
    }

    @Test
    fun `a saved event whose page no longer resolves gets no reminders`() = runTest {
        // Same routing as syncApiClient, but the event page is a 404-style page.
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val (body, type) = when {
                path.contains("filtered_topics") ->
                    """{"topics":[]}""" to ContentType.Application.Json
                path.contains("current_user") ->
                    """{"user":{"username":"yarnie"}}""" to ContentType.Application.Json
                path.contains("memberships") ->
                    """<a href="https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2">K</a>""" to ContentType.Text.Html
                path.contains("groups/search") ->
                    """{"groups":[{"id":1,"name":"Kirkland","permalink":"kirkland-fiber-arts-circle-2","forum_id":9}]}""" to ContentType.Application.Json
                path.endsWith("/events/saved") ->
                    """<div class="event_list"><div class="month">July 2026</div>
                       <div class="event"><div class="date"><div class="day">5th</div></div>
                       <div class="details"><a href="https://www.ravelry.com/events/gone" class="title">Gone</a></div>
                       </div></div>""" to ContentType.Text.Html
                path.contains("/events/gone") ->
                    "<html><body>this event was deleted</body></html>" to ContentType.Text.Html
                path.contains("/groups/") ->
                    """<div id="upcoming_events"></div>""" to ContentType.Text.Html
                else -> error("Unexpected path: ${'$'}path")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", type.toString()))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val runner = EventSyncRunner(RavelryApiClient(client, FakeFeedTokenStorage()), InMemoryStateStore())

        val plan = runner.sync(NOW, ZONE)

        assertTrue(plan.remindersToSchedule.isEmpty())
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

    /** Like [syncApiClient] but with a My Posts topic whose post count is mutable. */
    private fun syncApiClientWithMyTopic(postsCount: () -> Int, lastRead: () -> Int = { 0 }): RavelryApiClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val (body, type) = when {
                path.contains("filtered_topics") ->
                    """{"topics":[{"id":500,"title":"Cast-on question","forum_id":9,
                        "forum_posts_count":${postsCount()},"last_read":${lastRead()}}]}""" to
                        ContentType.Application.Json
                path.contains("current_user") ->
                    """{"user":{"username":"yarnie"}}""" to ContentType.Application.Json
                path.contains("memberships") ->
                    """<a href="https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2">K</a>""" to ContentType.Text.Html
                path.contains("groups/search") ->
                    """{"groups":[{"id":1,"name":"Kirkland Fiber Arts Circle","permalink":"kirkland-fiber-arts-circle-2","forum_id":9}]}""" to ContentType.Application.Json
                path.endsWith("/events/saved") ->
                    """<div class="event_list"></div>""" to ContentType.Text.Html
                path.contains("/groups/") ->
                    """<div id="upcoming_events"></div>""" to ContentType.Text.Html
                else -> error("Unexpected path: $path")
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", type.toString()))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(client, FakeFeedTokenStorage())
    }

    @Test
    fun `a topic that grew unread posts since the last sync yields a reply notification`() = runTest {
        var posts = 3
        val store = InMemoryStateStore()
        val runner = EventSyncRunner(syncApiClientWithMyTopic({ posts }), store)

        val first = runner.sync(NOW, ZONE)
        assertTrue(first.newReplyNotifications.isEmpty()) // first sync seeds silently
        assertEquals(3, first.newState.knownTopics.getValue(500L).postCount)

        posts = 5
        val second = runner.sync(NOW, ZONE)

        val notification = second.newReplyNotifications.single()
        assertEquals(500L, notification.topicId)
        assertEquals("Cast-on question", notification.topicTitle)
        // Attributed via the list entry's forum_id 9 -> the user's group.
        assertEquals("Kirkland Fiber Arts Circle", notification.groupName)
        assertEquals(2, notification.newReplyCount)
        assertEquals(5, second.newState.knownTopics.getValue(500L).postCount)
    }

    @Test
    fun `replies the user already read do not notify`() = runTest {
        var posts = 3
        var lastRead = 3
        val store = InMemoryStateStore()
        val runner = EventSyncRunner(syncApiClientWithMyTopic({ posts }, { lastRead }), store)
        runner.sync(NOW, ZONE)

        // Two new posts arrive but the user has read everything (e.g. their own reply
        // plus one they saw in-app before this sync ran).
        posts = 5
        lastRead = 5
        val plan = runner.sync(NOW, ZONE)

        assertTrue(plan.newReplyNotifications.isEmpty())
        // The count still advances so the next growth is measured from here.
        assertEquals(5, plan.newState.knownTopics.getValue(500L).postCount)
    }
}
