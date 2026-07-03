package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.AuthToken
import com.autom8ed.fibersocial.auth.ForbiddenException
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.VoteType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.formUrlEncode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Clock
import kotlinx.serialization.json.Json

class RavelryApiClientTest {

    @Test
    fun `getCurrentUser returns user from response`() = runTest {
        val client = routingApiClient { CURRENT_USER_JSON }
        val user = client.getCurrentUser()
        assertEquals("yarnie", user.username)
        assertEquals("https://example.com/a.jpg", user.avatarUrl)
    }

    @Test
    fun `getUserGroups scrapes memberships HTML and resolves groups via search`() = runTest {
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("groups/search") -> GROUPS_JSON
                else -> """{"groups":[]}"""
            }
        }
        val groups = client.getUserGroups("yarnie")
        assertEquals(1, groups.size)
        assertEquals("KAL Hub", groups[0].name)
        assertEquals(42L, groups[0].forumId)
    }

    @Test
    fun `getUserGroups returns empty list when memberships page has no group links`() = runTest {
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> "<html><body>no groups here</body></html>"
                else -> """{"groups":[]}"""
            }
        }
        assertEquals(emptyList(), client.getUserGroups("yarnie"))
    }

    @Test
    fun `getUserGroups skips groups not found in search`() = runTest {
        val htmlWithExtra = MEMBERSHIPS_HTML +
            """<a href="https://www.ravelry.com/groups/unknown-group">Unknown</a>"""
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> htmlWithExtra
                path.contains("groups/search") -> GROUPS_JSON
                else -> """{"groups":[]}"""
            }
        }
        val groups = client.getUserGroups("yarnie")
        assertEquals(1, groups.size)
        assertTrue(groups.none { it.permalink == "unknown-group" })
    }

    @Test
    fun `getUserGroups falls back to a narrower query when the full permalink query misses`() = runTest {
        // Regression test for #23: Ravelry appends "-2" to disambiguate a group's slug from
        // an existing one, but that digit isn't part of the group's name and pollutes the
        // search query enough that a small/niche group can fall out of the top 25 results.
        val membershipsHtml = """<html><body>
            <a href="https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2">Kirkland Fiber Arts Circle</a>
        </body></html>"""
        val targetGroupJson =
            """{"groups":[{"id":99,"name":"Kirkland Fiber Arts Circle","permalink":"kirkland-fiber-arts-circle-2","forum_id":77}]}"""
        val queriesSeen = mutableListOf<String>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            val body = when {
                path.contains("memberships") -> membershipsHtml
                path.contains("groups/search") -> {
                    val query = request.url.parameters["query"].orEmpty()
                    queriesSeen.add(query)
                    if (query == "kirkland fiber arts circle") targetGroupJson else """{"groups":[]}"""
                }
                else -> """{"groups":[]}"""
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val groups = client.getUserGroups("yarnie")

        assertEquals(1, groups.size)
        assertEquals(77L, groups[0].forumId)
        assertEquals(listOf("kirkland fiber arts circle 2", "kirkland fiber arts circle"), queriesSeen)
    }

    @Test
    fun `getUserGroups omits a group when every fallback query misses`() = runTest {
        val client = routingApiClient { path ->
            when {
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("groups/search") -> """{"groups":[]}"""
                else -> """{"groups":[]}"""
            }
        }
        assertEquals(emptyList(), client.getUserGroups("yarnie"))
    }

    @Test
    fun `getUserGroups fails when no session cookie stored`() = runTest {
        val storage = FakeFeedTokenStorage(
            initial = com.autom8ed.fibersocial.auth.AuthToken("tok", "ref", Long.MAX_VALUE, null)
        )
        val client = routingApiClient(storage = storage) { "" }
        val result = runCatching { client.getUserGroups("yarnie") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `getGroupTopics returns topics list`() = runTest {
        val client = routingApiClient { topicsJson(100L, 101L) }
        val topics = client.getGroupTopics(42L)
        assertEquals(2, topics.size)
        assertEquals(100L, topics[0].id)
        assertEquals(101L, topics[1].id)
    }

    @Test
    fun `getGroupTopics returns empty list when forum has no topics`() = runTest {
        val client = routingApiClient { """{"topics":[]}""" }
        assertEquals(emptyList(), client.getGroupTopics(42L))
    }

    @Test
    fun `getTopicDetail returns full topic with author and summary`() = runTest {
        val client = routingApiClient { topicDetailJson(100L, imagesCount = 2, summary = "Great WIP!") }
        val topic = client.getTopicDetail(100L)
        assertEquals(100L, topic.id)
        assertEquals(2, topic.imagesCount)
        assertEquals("yarnie", topic.createdByUser?.username)
        assertEquals("Great WIP!", topic.summary)
    }

    @Test
    fun `all requests include Bearer token header`() = runTest {
        val storage = FakeFeedTokenStorage()
        var capturedAuth: String? = null
        val engine = MockEngine { request ->
            capturedAuth = request.headers[HttpHeaders.Authorization]
            respond(
                content = CURRENT_USER_JSON,
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, storage).getCurrentUser()
        assertEquals("Bearer test-token", capturedAuth)
    }

    @Test
    fun `getTopicPosts returns posts list`() = runTest {
        val client = routingApiClient { postsJson(1L, 2L) }
        val posts = client.getTopicPosts(42L)
        assertEquals(2, posts.size)
        assertEquals(1L, posts[0].id)
        assertEquals("<p>Reply 1</p>", posts[0].bodyHtml)
        assertEquals("user1", posts[0].user?.username)
        assertEquals("2024-01-15T10:00:00Z", posts[0].createdAt)
    }

    @Test
    fun `getTopicPosts returns empty list when topic has no posts`() = runTest {
        val client = routingApiClient { """{"posts":[]}""" }
        assertEquals(emptyList(), client.getTopicPosts(42L))
    }

    @Test
    fun `getTopicPosts requests vote_totals and user_votes`() = runTest {
        var capturedInclude: String? = null
        val engine = MockEngine { request ->
            capturedInclude = request.url.parameters["include"]
            respond(postsJson(1L), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        RavelryApiClient(httpClient, FakeFeedTokenStorage()).getTopicPosts(42L)
        assertEquals("vote_totals user_votes", capturedInclude)
    }

    @Test
    fun `getTopicPosts merges top-level vote_totals and user_votes onto matching post by id`() = runTest {
        // Ravelry doesn't nest vote_totals/user_votes inside each post in this endpoint's
        // response — it returns them as separate maps keyed by post id (as a string),
        // alongside the posts array.
        val client = routingApiClient {
            """{"posts":[{"id":1,"body_html":"<p>Reply</p>","user":{"username":"user1"}}],
                "vote_totals":{"1":{"love":3}},"user_votes":{"1":["love"]}}"""
        }
        val posts = client.getTopicPosts(42L)
        assertEquals(mapOf("love" to 3), posts[0].voteTotals)
        assertEquals(listOf("love"), posts[0].userVotes)
    }

    @Test
    fun `getTopicPosts matches each post's own vote data by id, not another post's`() = runTest {
        val client = routingApiClient {
            """{"posts":[
                {"id":1,"body_html":"<p>A</p>","user":{"username":"user1"}},
                {"id":2,"body_html":"<p>B</p>","user":{"username":"user2"}}
               ],
               "vote_totals":{"1":{"interesting":1,"agree":2},"2":{"funny":1}},
               "user_votes":{"1":["agree"],"2":[]}}"""
        }
        val posts = client.getTopicPosts(42L)
        assertEquals(mapOf("interesting" to 1, "agree" to 2), posts[0].voteTotals)
        assertEquals(listOf("agree"), posts[0].userVotes)
        assertEquals(mapOf("funny" to 1), posts[1].voteTotals)
        assertEquals(emptyList(), posts[1].userVotes)
    }

    @Test
    fun `getTopicPosts defaults vote fields to empty when absent`() = runTest {
        val client = routingApiClient { postsJson(1L) }
        val posts = client.getTopicPosts(42L)
        assertEquals(emptyMap(), posts[0].voteTotals)
        assertEquals(emptyList(), posts[0].userVotes)
    }

    @Test
    fun `voteOnPost posts to forum_posts vote endpoint with the given type`() = runTest {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedType: String? = null
        var capturedVote: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method.value
            capturedType = request.url.parameters["type"]
            capturedVote = request.url.parameters["vote"]
            respond(voteResponseJson("love", 1, userVoted = true), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .voteOnPost(1000L, VoteType.LOVE, voted = true)

        assertEquals("/forum_posts/1000/vote.json", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals("love", capturedType)
        assertEquals("1", capturedVote)
        assertEquals(mapOf("love" to 1), result.voteTotals)
        assertEquals(listOf("love"), result.userVotes)
    }

    @Test
    fun `voteOnPost passes through non-love vote types`() = runTest {
        var capturedType: String? = null
        val engine = MockEngine { request ->
            capturedType = request.url.parameters["type"]
            respond(voteResponseJson("funny", 1, userVoted = true), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .voteOnPost(1000L, VoteType.FUNNY, voted = true)

        assertEquals("funny", capturedType)
        assertEquals(mapOf("funny" to 1), result.voteTotals)
        assertEquals(listOf("funny"), result.userVotes)
    }

    @Test
    fun `voteOnPost clears vote when voted is false`() = runTest {
        var capturedVote: String? = null
        val engine = MockEngine { request ->
            capturedVote = request.url.parameters["vote"]
            respond(voteResponseJson("love", 0, userVoted = false), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val result = RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .voteOnPost(1000L, VoteType.LOVE, voted = false)

        assertEquals("0", capturedVote)
        assertEquals(emptyList(), result.userVotes)
    }

    @Test
    fun `getCurrentUser throws when no token stored`() = runTest {
        val emptyStorage = FakeFeedTokenStorage(initial = null)
        val engine = MockEngine { respond("", HttpStatusCode.OK, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<IllegalStateException> {
            RavelryApiClient(httpClient, emptyStorage).getCurrentUser()
        }
    }

    @Test
    fun `on 401 retries request after successful token refresh`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("", HttpStatusCode.Unauthorized, headersOf())
            else respond(CURRENT_USER_JSON, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        var refreshCalled = false
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { refreshCalled = true })
        val user = client.getCurrentUser()
        assertEquals("yarnie", user.username)
        assertTrue(refreshCalled)
        assertEquals(2, callCount)
    }

    @Test
    fun `on 401 without refresh callback throws SessionExpiredException`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        assertFailsWith<SessionExpiredException> {
            RavelryApiClient(httpClient, FakeFeedTokenStorage()).getCurrentUser()
        }
    }

    @Test
    fun `on 401 when refresh throws SessionExpiredException is propagated`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { throw Exception("network error") })
        assertFailsWith<SessionExpiredException> {
            client.getCurrentUser()
        }
    }

    @Test
    fun `on 403 throws ForbiddenException without refreshing or retrying`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            respond("", HttpStatusCode.Forbidden, headersOf())
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        var refreshCalled = false
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { refreshCalled = true })
        assertFailsWith<ForbiddenException> { client.getCurrentUser() }
        assertFalse(refreshCalled)
        assertEquals(1, callCount)
    }

    @Test
    fun `on 401 when retry also returns 401 throws SessionExpiredException`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Unauthorized, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { /* refresh succeeds, but retry still fails */ })
        assertFailsWith<SessionExpiredException> {
            client.getCurrentUser()
        }
    }

    @Test
    fun `on 401 when retry returns 403 throws ForbiddenException`() = runTest {
        var callCount = 0
        val engine = MockEngine {
            callCount++
            if (callCount == 1) respond("", HttpStatusCode.Unauthorized, headersOf())
            else respond("", HttpStatusCode.Forbidden, headersOf())
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage(),
            refreshToken = { /* refresh succeeds, but retry is forbidden */ })
        assertFailsWith<ForbiddenException> {
            client.getCurrentUser()
        }
    }

    @Test
    fun `ForbiddenException message avoids status-code digits that UI matches as expiry`() = runTest {
        val engine = MockEngine { respond("", HttpStatusCode.Forbidden, headersOf()) }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())
        val e = assertFailsWith<ForbiddenException> { client.getCurrentUser() }
        val message = e.message ?: ""
        assertFalse(message.contains("403"))
        assertFalse(message.contains("401"))
        assertTrue(message.contains("/current_user.json"))
    }

    @Test
    fun `proactively refreshes when token expires within 60 seconds`() = runTest {
        val nearExpiry = Clock.System.now().toEpochMilliseconds() + 30_000L
        val storage = FakeFeedTokenStorage(
            initial = AuthToken("test-token", "test-refresh", nearExpiry, "sess=test")
        )
        val engine = MockEngine {
            respond(CURRENT_USER_JSON, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        var refreshCalled = false
        val client = RavelryApiClient(httpClient, storage,
            refreshToken = { refreshCalled = true })
        client.getCurrentUser()
        assertTrue(refreshCalled)
    }

    @Test
    fun `getGroupEvents scrapes the group page with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            respond(GROUP_PAGE_EVENTS_HTML, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val events = client.getGroupEvents("kirkland-fiber-arts-circle-2")

        assertEquals("https://www.ravelry.com/groups/kirkland-fiber-arts-circle-2", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals(2, events.size)
        assertEquals("sunday-circle-at-postdoc-brewing-10", events[0].permalink)
        assertEquals(1, events[0].attendeeCount)
    }

    @Test
    fun `getGroupEvents returns empty list for a group without events`() = runTest {
        val client = routingApiClient { "<html><body>no box</body></html>" }
        assertEquals(emptyList(), client.getGroupEvents("quiet-group"))
    }

    @Test
    fun `getGroupEvents throws SessionExpiredException on 401`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<SessionExpiredException> { client.getGroupEvents("quiet-group") }
    }

    @Test
    fun `getGroupEvents throws SessionExpiredException when redirected to the login page`() = runTest {
        // An expired session cookie doesn't 401 on www.ravelry.com — it 302s to the login
        // page, which Ktor follows to a 200. The client must not mistake that for a group
        // page with no events box.
        val client = htmlApiClient(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/groups/")) {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"))
            } else {
                respond("<html><body>please log in</body></html>", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Text.Html.toString()))
            }
        })
        assertFailsWith<SessionExpiredException> { client.getGroupEvents("quiet-group") }
    }

    @Test
    fun `getGroupEvents throws on a server error response`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("oops", HttpStatusCode.InternalServerError)
        })
        assertFailsWith<IllegalStateException> { client.getGroupEvents("quiet-group") }
    }

    @Test
    fun `getEvent scrapes the event page with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            respond(EVENT_PAGE_HTML_SNIPPET, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val detail = client.getEvent("wednesday-hh-at-chainline-39")

        assertEquals("https://www.ravelry.com/events/wednesday-hh-at-chainline-39", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals("Wednesday HH at Chainline", detail?.title)
        assertEquals("Knitting/crochet group", detail?.eventType)
    }

    @Test
    fun `getEvent returns null for a non-event page`() = runTest {
        val client = routingApiClient { "<html><body>not an event</body></html>" }
        assertEquals(null, client.getEvent("deleted-event"))
    }

    @Test
    fun `getEvent throws SessionExpiredException on 401`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<SessionExpiredException> { client.getEvent("some-event") }
    }

    @Test
    fun `getEvent throws SessionExpiredException when redirected to the login page`() = runTest {
        // Same trap as getGroupEvents: an expired cookie 302s to the login page, which
        // Ktor follows to a 200 whose HTML simply has no .event__detail — without the
        // redirect check that would masquerade as "not an event" (null).
        val client = htmlApiClient(MockEngine { request ->
            if (request.url.encodedPath.startsWith("/events/")) {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"))
            } else {
                respond("<html><body>please log in</body></html>", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Text.Html.toString()))
            }
        })
        assertFailsWith<SessionExpiredException> { client.getEvent("deleted-event") }
    }

    @Test
    fun `setEventAttendance posts the csrf token as a form with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        var sentBody = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            sentBody = (request.body as io.ktor.client.request.forms.FormDataContent)
                .formData.formUrlEncode()
            respond("R.popover.close();", HttpStatusCode.OK,
                headersOf("Content-Type", "text/javascript"))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val ok = client.setEventAttendance("cozy-meetup", attending = true, csrfToken = "tok+en=")

        assertTrue(ok)
        assertEquals("https://www.ravelry.com/events/cozy-meetup/attend?attending=1", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals("authenticity_token=tok%2Ben%3D", sentBody)
    }

    @Test
    fun `getEventAttendees scrapes the people page with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            respond(
                """<div class="event__user_cards"><div class="user_card">
                   <div class="details"><a class="login" href="/people/knitwit">knitwit</a></div>
                   </div></div>""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()),
            )
        }
        val client = htmlApiClient(engine)

        val attendees = client.getEventAttendees("cozy-meetup")

        assertEquals("https://www.ravelry.com/events/cozy-meetup/people", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals(listOf("knitwit"), attendees.map { it.username })
    }

    @Test
    fun `getEventAttendees throws SessionExpiredException on 401`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<SessionExpiredException> { client.getEventAttendees("cozy-meetup") }
    }

    @Test
    fun `getSavedEvents scrapes the saved-events page with the session cookie`() = runTest {
        var requestedUrl = ""
        var sentCookie: String? = null
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            sentCookie = request.headers[HttpHeaders.Cookie]
            respond(
                """<div class="event_list" id="event_list">
                   <div class="month">July 2026</div>
                   <div class="event"><div class="date"><div class="day">5th</div></div>
                   <div class="details"><a href="https://www.ravelry.com/events/cozy-meetup" class="title">Cozy Meetup</a></div>
                   </div></div>""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val saved = client.getSavedEvents()

        assertEquals("https://www.ravelry.com/events/saved", requestedUrl)
        assertEquals("sess=test", sentCookie)
        assertEquals(listOf("cozy-meetup"), saved.map { it.permalink })
    }

    @Test
    fun `getSavedEvents throws SessionExpiredException on 401`() = runTest {
        val client = htmlApiClient(MockEngine { _ ->
            respond("", HttpStatusCode.Unauthorized)
        })
        assertFailsWith<SessionExpiredException> { client.getSavedEvents() }
    }

    @Test
    fun `getSavedEvents rejects a redirect to another events page`() = runTest {
        // A session-limited redirect to /events/search renders similar markup that
        // would otherwise parse as a bogus RSVP list — the exact /events/saved
        // prefix must treat it as session expiry, not data.
        val client = htmlApiClient(MockEngine { request ->
            if (request.url.encodedPath == "/events/saved") {
                respond("", HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/events/search"))
            } else {
                respond("<div class=\"event_list\"></div>", HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Text.Html.toString()))
            }
        })
        assertFailsWith<SessionExpiredException> { client.getSavedEvents() }
    }

    @Test
    fun `setEventAttendance false posts to unattend and reports rejection`() = runTest {
        var requestedUrl = ""
        val engine = MockEngine { request ->
            requestedUrl = request.url.toString()
            respond("nope", HttpStatusCode.Forbidden,
                headersOf("Content-Type", "text/html"))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

        val ok = client.setEventAttendance("cozy-meetup", attending = false, csrfToken = "t")

        assertEquals(false, ok)
        assertEquals("https://www.ravelry.com/events/cozy-meetup/unattend", requestedUrl)
    }

    @Test
    fun `getLatestPost requests single newest post`() = runTest {
        var capturedSort: String? = null
        var capturedPageSize: String? = null
        var capturedPath: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedSort = request.url.parameters["sort_reverse"]
            capturedPageSize = request.url.parameters["page_size"]
            respond(
                content = latestPostJson(username = "replier"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val post = RavelryApiClient(httpClient, FakeFeedTokenStorage()).getLatestPost(100L)
        assertEquals("/topics/100/posts.json", capturedPath)
        assertEquals("1", capturedSort)
        assertEquals("1", capturedPageSize)
        assertEquals("replier", post?.user?.username)
        assertEquals("<p>Latest <b>reply</b> text</p>", post?.bodyHtml)
    }

    @Test
    fun `getLatestPost returns null when topic has no posts`() = runTest {
        val client = routingApiClient { """{"posts":[]}""" }
        assertEquals(null, client.getLatestPost(100L))
    }

    @Test
    fun `postReply raises a cautious error when the response is not the created post`() = runTest {
        // A 200 with a non-JSON body (maintenance page, HTML error) must not be treated
        // as success, and the message must warn the reply may still have posted.
        val client = routingApiClient { path ->
            if (path.contains("/reply.json")) "<html>down for maintenance</html>" else postsJson(1L)
        }
        val failure = runCatching { client.postReply(42L, "hello") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the reply may have posted"))
    }

    @Test
    fun `postReply posts body to reply endpoint and returns created post`() = runTest {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedBody: String? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method.value
            capturedBody = (request.body as io.ktor.client.request.forms.FormDataContent)
                .formData["body"]
            respond(
                content = replyResponseJson(id = 99L, username = "yarnie"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val post = RavelryApiClient(httpClient, FakeFeedTokenStorage()).postReply(42L, "My new reply")
        assertEquals("/topics/42/reply.json", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals("My new reply", capturedBody)
        assertEquals(99L, post.id)
        assertEquals("yarnie", post.user?.username)
        assertEquals("<p>My new reply</p>", post.bodyHtml)
    }

    @Test
    fun `createTopic posts form fields to create endpoint and returns created topic`() = runTest {
        var capturedPath: String? = null
        var capturedMethod: String? = null
        var capturedForm: io.ktor.http.Parameters? = null
        val engine = MockEngine { request ->
            capturedPath = request.url.encodedPath
            capturedMethod = request.method.value
            capturedForm = (request.body as io.ktor.client.request.forms.FormDataContent).formData
            respond(
                content = topicCreateResponseJson(id = 7001L, title = "Show us your WIPs"),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val topic = RavelryApiClient(httpClient, FakeFeedTokenStorage())
            .createTopic(123L, "Show us your WIPs", "Post a photo of what's on your needles!")
        assertEquals("/topics/create.json", capturedPath)
        assertEquals("POST", capturedMethod)
        assertEquals("123", capturedForm!!["forum_id"])
        assertEquals("Show us your WIPs", capturedForm!!["title"])
        assertEquals("Post a photo of what's on your needles!", capturedForm!!["body"])
        assertEquals(7001L, topic.id)
        assertEquals("Show us your WIPs", topic.title)
        assertEquals("yarnie", topic.createdByUser?.username)
    }

    @Test
    fun `createTopic raises a cautious error when the response is not the created topic`() = runTest {
        // A 200 with a non-JSON body (maintenance page, HTML error) must not be treated
        // as success, and the message must warn the topic may still have been created.
        val client = routingApiClient { path ->
            if (path.contains("/topics/create.json")) "<html>down for maintenance</html>" else postsJson(1L)
        }
        val failure = runCatching { client.createTopic(123L, "title", "body") }.exceptionOrNull()
        assertTrue(failure!!.message!!.contains("the topic may have been created"))
    }
}

private fun htmlApiClient(engine: MockEngine): RavelryApiClient {
    val httpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(httpClient, FakeFeedTokenStorage())
}

private val EVENT_PAGE_HTML_SNIPPET = """
<div class="page_title"><div class="page_title__supertitle"><a href="https://www.ravelry.com/events">events</a></div>
Wednesday HH at Chainline
</div>
<div class="event__detail">
<div class="event__type">Knitting/crochet group</div>
<div class="event__dates">July  1, 2026 @  5:30 PM</div>
</div>
"""

private val GROUP_PAGE_EVENTS_HTML = """
<div class="box" id="upcoming_events"><div id="events">
<div class="event">
<div class="what"><a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-10">Sunday Circle at Postdoc Brewing</a></div>
<div class="when">July  5, 2026 @ 1:00 PM</div>
<div class="who"><a href="https://www.ravelry.com/events/sunday-circle-at-postdoc-brewing-10/people">1 person</a></div>
</div>
<div class="event">
<div class="what"><a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-39">Wednesday HH at Chainline</a></div>
<div class="when">July  8, 2026 @ 5:30 PM</div>
<div class="who"><a href="https://www.ravelry.com/events/wednesday-hh-at-chainline-39/people">0 people</a></div>
</div>
</div></div>
"""
