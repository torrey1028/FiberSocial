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
import kotlin.test.assertNull
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

private class InMemorySettingsStore(
    private val settings: NotificationSettings = NotificationSettings(),
) : NotificationSettingsStore {
    override suspend fun load(): NotificationSettings = settings
    override suspend fun save(settings: NotificationSettings) = Unit
}

private class InMemoryMutedTopicsStore(initial: Set<Long> = emptySet()) : MutedTopicsStore {
    var muted: Set<Long> = initial
        private set
    var saveCount = 0
    override suspend fun load(): Set<Long> = muted
    override suspend fun save(mutedTopicIds: Set<Long>) {
        muted = mutedTopicIds
        saveCount++
    }
    override suspend fun mutate(transform: (Set<Long>) -> Set<Long>): Set<Long> {
        val updated = transform(muted)
        if (updated != muted) save(updated)
        return updated
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
            path.contains("/messages/list") ->
                """{"messages":[]}""" to ContentType.Application.Json
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
        val plan = EventSyncRunner(syncApiClient(), store, InMemorySettingsStore()).sync(NOW, ZONE)

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
                path.contains("/messages/list") ->
                    """{"messages":[]}""" to ContentType.Application.Json
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
        val runner = EventSyncRunner(RavelryApiClient(client, FakeFeedTokenStorage()), InMemoryStateStore(), InMemorySettingsStore())

        val plan = runner.sync(NOW, ZONE)

        assertEquals(setOf("shared-event"), plan.newState.knownEvents.keys)
    }

    @Test
    fun `sync propagates a network failure without saving partial state`() = runTest {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            if (path.contains("current_user")) {
                throw RuntimeException("network unreachable")
            }
            // getMyTopics() runs concurrently with the current_user fetch (both are
            // async'd in sync()) — give it a well-formed response so this test
            // deterministically exercises only the intended current_user failure,
            // not an incidental JSON-parse failure racing it.
            if (path.contains("filtered_topics") || path.contains("/messages/list")) {
                val body = if (path.contains("/messages/list")) """{"messages":[]}""" else """{"topics":[]}"""
                return@MockEngine respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
            respond("<div class=\"event_list\"></div>", HttpStatusCode.OK, headersOf("Content-Type", ContentType.Text.Html.toString()))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val store = InMemoryStateStore()
        val runner = EventSyncRunner(RavelryApiClient(client, FakeFeedTokenStorage()), store, InMemorySettingsStore())

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
                path.contains("/messages/list") ->
                    """{"messages":[]}""" to ContentType.Application.Json
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
        val runner = EventSyncRunner(RavelryApiClient(client, FakeFeedTokenStorage()), InMemoryStateStore(), InMemorySettingsStore())

        val plan = runner.sync(NOW, ZONE)

        assertTrue(plan.remindersToSchedule.isEmpty())
    }

    @Test
    fun `second sync over unchanged data is a no-op plan`() = runTest {
        val store = InMemoryStateStore()
        val runner = EventSyncRunner(syncApiClient(), store, InMemorySettingsStore())
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
        EventSyncRunner(syncApiClient(), store, InMemorySettingsStore()).sync(NOW, ZONE)
        // A brand-new runner (fresh worker process) sees the seeded state.
        val plan = EventSyncRunner(syncApiClient(), store, InMemorySettingsStore()).sync(NOW, ZONE)
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
                path.contains("/messages/list") ->
                    """{"messages":[]}""" to ContentType.Application.Json
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
        val runner = EventSyncRunner(syncApiClientWithMyTopic({ posts }), store, InMemorySettingsStore())

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
        val runner = EventSyncRunner(syncApiClientWithMyTopic({ posts }, { lastRead }), store, InMemorySettingsStore())
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

    // --- Per-kind toggles (issue #335) ---

    @Test
    fun `replies disabled skips the reply leg and clears known topics`() = runTest {
        var posts = 3
        val store = InMemoryStateStore()
        // Seed knownTopics with replies enabled.
        EventSyncRunner(syncApiClientWithMyTopic({ posts }), store, InMemorySettingsStore()).sync(NOW, ZONE)

        // With replies off, a grown topic notifies nothing and its state is cleared so a
        // later re-enable seeds silently instead of firing the backlog.
        posts = 9
        val plan = EventSyncRunner(
            syncApiClientWithMyTopic({ posts }),
            store,
            InMemorySettingsStore(NotificationSettings(topicRepliesEnabled = false)),
        ).sync(NOW, ZONE)

        assertTrue(plan.newReplyNotifications.isEmpty())
        assertTrue(plan.newState.knownTopics.isEmpty())
    }

    @Test
    fun `re-enabling replies seeds silently rather than firing the backlog`() = runTest {
        var posts = 3
        val store = InMemoryStateStore()
        val enabled = InMemorySettingsStore()
        val disabled = InMemorySettingsStore(NotificationSettings(topicRepliesEnabled = false))

        EventSyncRunner(syncApiClientWithMyTopic({ posts }), store, enabled).sync(NOW, ZONE)
        // A pile of replies arrive while replies are off.
        posts = 30
        EventSyncRunner(syncApiClientWithMyTopic({ posts }), store, disabled).sync(NOW, ZONE)
        // First sync after re-enabling must NOT announce the 27-post backlog.
        val reenabled = EventSyncRunner(syncApiClientWithMyTopic({ posts }), store, enabled).sync(NOW, ZONE)

        assertTrue(reenabled.newReplyNotifications.isEmpty())
        assertEquals(30, reenabled.newState.knownTopics.getValue(500L).postCount)

        // A genuine new reply after re-seeding notifies from the fresh baseline.
        posts = 32
        val next = EventSyncRunner(syncApiClientWithMyTopic({ posts }), store, enabled).sync(NOW, ZONE)
        assertEquals(2, next.newReplyNotifications.single().newReplyCount)
    }

    @Test
    fun `new group events disabled produces no event notifications and clears known events`() = runTest {
        val store = InMemoryStateStore()
        EventSyncRunner(syncApiClient(), store, InMemorySettingsStore()).sync(NOW, ZONE)

        val plan = EventSyncRunner(
            syncApiClient(),
            store,
            InMemorySettingsStore(NotificationSettings(newGroupEventsEnabled = false)),
        ).sync(NOW, ZONE)

        assertTrue(plan.newEventNotifications.isEmpty())
        assertTrue(plan.newState.knownEvents.isEmpty())
    }

    // --- Per-topic mute (issue #338) ---

    @Test
    fun `a muted topic yields no reply notification but keeps advancing its count`() = runTest {
        var posts = 3
        val store = InMemoryStateStore()
        val muted = InMemoryMutedTopicsStore(setOf(500L))
        val runner = EventSyncRunner(
            syncApiClientWithMyTopic({ posts }),
            store,
            InMemorySettingsStore(),
            muted,
        )
        runner.sync(NOW, ZONE) // seed

        posts = 8
        val plan = runner.sync(NOW, ZONE)

        assertTrue(plan.newReplyNotifications.isEmpty())
        assertEquals(8, plan.newState.knownTopics.getValue(500L).postCount)
    }

    @Test
    fun `a mute for a topic no longer tracked is pruned`() = runTest {
        // 999 isn't among the user's My Posts topics, so it never enters knownTopics;
        // its mute is pruned so the set can't grow forever.
        val store = InMemoryStateStore()
        val muted = InMemoryMutedTopicsStore(setOf(999L))
        EventSyncRunner(syncApiClient(), store, InMemorySettingsStore(), muted).sync(NOW, ZONE)

        assertTrue(muted.muted.isEmpty())
        assertEquals(1, muted.saveCount)
    }

    @Test
    fun `mutes are left untouched when replies are disabled`() = runTest {
        val store = InMemoryStateStore()
        val muted = InMemoryMutedTopicsStore(setOf(500L))
        EventSyncRunner(
            syncApiClientWithMyTopic({ 5 }),
            store,
            InMemorySettingsStore(NotificationSettings(topicRepliesEnabled = false)),
            muted,
        ).sync(NOW, ZONE)

        assertEquals(setOf(500L), muted.muted)
        assertEquals(0, muted.saveCount)
    }

    // --- Messages leg (issue #375) ---

    /**
     * Like [syncApiClient] but serving a mutable unread inbox. [unreadIds] are returned as
     * unread inbox messages from "wooliam" to the signed-in user.
     */
    private fun syncApiClientWithInbox(unreadIds: () -> List<Long>): RavelryApiClient {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val (body, type) = when {
                path.contains("filtered_topics") ->
                    """{"topics":[]}""" to ContentType.Application.Json
                path.contains("/messages/list") -> {
                    val entries = unreadIds().joinToString(",") { id ->
                        """{"id":$id,"subject":"Yarn swap $id","read_message":false,
                           "sender":{"username":"wooliam"},"recipient":{"username":"yarnie"}}"""
                    }
                    """{"messages":[$entries]}""" to ContentType.Application.Json
                }
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
    fun `the first messages sync seeds the mark silently`() = runTest {
        // The highest-risk bug in #375: an inbox full of unread history must produce no
        // notifications at all on the first sync after upgrading.
        val store = InMemoryStateStore()
        val plan = EventSyncRunner(
            syncApiClientWithInbox { listOf(101L, 102L, 103L) },
            store,
            InMemorySettingsStore(),
        ).sync(NOW, ZONE)

        assertTrue(plan.newMessageNotifications.isEmpty())
        assertEquals(103L, plan.newState.knownMessages?.newestMessageId)
    }

    @Test
    fun `a message arriving after the first sync notifies`() = runTest {
        var unread = listOf(101L)
        val store = InMemoryStateStore()
        val runner = EventSyncRunner(syncApiClientWithInbox { unread }, store, InMemorySettingsStore())

        assertTrue(runner.sync(NOW, ZONE).newMessageNotifications.isEmpty()) // seeds

        unread = listOf(101L, 102L)
        val plan = runner.sync(NOW, ZONE)

        val notification = plan.newMessageNotifications.single()
        assertEquals(102L, notification.messageId)
        assertEquals("wooliam", notification.senderName)
        assertEquals("Yarn swap 102", notification.subject)
        assertEquals(102L, plan.newState.knownMessages?.newestMessageId)
    }

    @Test
    fun `messages disabled skips the messages leg and clears the mark`() = runTest {
        var unread = listOf(101L)
        val store = InMemoryStateStore()
        EventSyncRunner(syncApiClientWithInbox { unread }, store, InMemorySettingsStore()).sync(NOW, ZONE)

        // With messages off, new arrivals notify nothing and the mark is cleared so a
        // later re-enable seeds silently instead of firing the backlog.
        unread = listOf(101L, 102L, 103L)
        val plan = EventSyncRunner(
            syncApiClientWithInbox { unread },
            store,
            InMemorySettingsStore(NotificationSettings(newMessagesEnabled = false)),
        ).sync(NOW, ZONE)

        assertTrue(plan.newMessageNotifications.isEmpty())
        assertNull(plan.newState.knownMessages)
    }

    @Test
    fun `re-enabling messages seeds silently rather than firing the backlog`() = runTest {
        var unread = listOf(101L)
        val store = InMemoryStateStore()
        val enabled = InMemorySettingsStore()
        val disabled = InMemorySettingsStore(NotificationSettings(newMessagesEnabled = false))

        EventSyncRunner(syncApiClientWithInbox { unread }, store, enabled).sync(NOW, ZONE)
        // A pile of messages arrives while the kind is off.
        unread = (101L..130L).toList()
        EventSyncRunner(syncApiClientWithInbox { unread }, store, disabled).sync(NOW, ZONE)
        // First sync after re-enabling must NOT announce the 29-message backlog.
        val reenabled = EventSyncRunner(syncApiClientWithInbox { unread }, store, enabled).sync(NOW, ZONE)

        assertTrue(reenabled.newMessageNotifications.isEmpty())
        assertEquals(130L, reenabled.newState.knownMessages?.newestMessageId)

        // A genuine new message after re-seeding notifies from the fresh baseline.
        unread = unread + 131L
        val next = EventSyncRunner(syncApiClientWithInbox { unread }, store, enabled).sync(NOW, ZONE)
        assertEquals(listOf(131L), next.newMessageNotifications.map { it.messageId })
    }

    @Test
    fun `the messages leg leaves the other legs' state alone`() = runTest {
        // A regression guard for the newState.copy() merge: adding knownMessages must not
        // drop knownEvents or knownTopics on the way through.
        val store = InMemoryStateStore()
        val plan = EventSyncRunner(syncApiClient(), store, InMemorySettingsStore()).sync(NOW, ZONE)

        assertEquals(setOf("sunday-circle"), plan.newState.knownEvents.keys)
        assertTrue(plan.newState.scheduledReminders.isNotEmpty())
    }

    @Test
    fun `event reminders disabled schedules none and cancels the pending ones`() = runTest {
        val store = InMemoryStateStore()
        val first = EventSyncRunner(syncApiClient(), store, InMemorySettingsStore()).sync(NOW, ZONE)
        assertTrue(first.remindersToSchedule.isNotEmpty())

        val plan = EventSyncRunner(
            syncApiClient(),
            store,
            InMemorySettingsStore(NotificationSettings(eventRemindersEnabled = false)),
        ).sync(NOW, ZONE)

        assertTrue(plan.remindersToSchedule.isEmpty())
        assertEquals(first.remindersToSchedule.toSet(), plan.remindersToCancel.toSet())
        assertTrue(plan.newState.scheduledReminders.isEmpty())
    }
}
