package com.autom8ed.fibersocial.feed

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import com.autom8ed.fibersocial.auth.AuthToken
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.VoteType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TopicDetailViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    @Test
    fun `initial state is Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(routingApiClient { postsJson(1L) }, this)
        assertIs<TopicDetailState.Loading>(vm.state.value)
    }

    @Test
    fun `load transitions to Loaded with posts`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(routingApiClient { postsJson(1L, 2L) }, this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(2, state.posts.size)
        assertEquals(1L, state.posts[0].id)
        assertEquals("<p>Reply 1</p>", state.posts[0].bodyHtml)
        assertEquals(2L, state.posts[1].id)
    }

    @Test
    fun `load transitions to Error when api fails`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(errorApiClient(), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<TopicDetailState.Error>(vm.state.value)
        assertEquals("Simulated network error", state.message)
    }

    @Test
    fun `load resets state to Loading before each fetch`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(routingApiClient { postsJson(1L) }, this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<TopicDetailState.Loaded>(vm.state.value)

        vm.load(99L)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<TopicDetailState.Loaded>(vm.state.value)
    }

    @Test
    fun `load with empty posts list produces Loaded with empty list`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(routingApiClient { """{"posts":[]}""" }, this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(0, state.posts.size)
    }

    @Test
    fun `load does not advance the read marker`() = runTest(UnconfinedTestDispatcher()) {
        // Read is marked on the way out based on how far the user scrolled (issue #206),
        // not eagerly on load — opening a long thread must not mark its first page read.
        var readCalls = 0
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/read.json")) {
                readCalls++
                respond("", HttpStatusCode.OK, headersOf())
            } else {
                respond(postsJson(1L, 2L, 3L), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(0, readCalls)
    }

    @Test
    fun `markRead advances the read marker via a form POST to read`() = runTest(UnconfinedTestDispatcher()) {
        // Issue #206: leaving the thread POSTs the furthest-scrolled post number to
        // /topics/{id}/read.json as a last_read form field.
        var readPath: String? = null
        var readMethod: String? = null
        var readLastRead: String? = null
        val engine = MockEngine { request ->
            readPath = request.url.encodedPath
            readMethod = request.method.value
            readLastRead = (request.body as io.ktor.client.request.forms.FormDataContent)
                .formData["last_read"]
            respond("", HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.markRead(42L, 7)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("/topics/42/read.json", readPath)
        assertEquals("POST", readMethod)
        assertEquals("7", readLastRead)
    }

    @Test
    fun `markRead does nothing for a non-positive last read`() = runTest(UnconfinedTestDispatcher()) {
        // Nothing was seen (furthest scrolled = 0), so there's no marker to advance — no POST.
        var readCalls = 0
        val engine = MockEngine {
            readCalls++
            respond("", HttpStatusCode.OK, headersOf())
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.markRead(42L, 0)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(0, readCalls)
    }

    @Test
    fun `markRead swallows failures`() = runTest(UnconfinedTestDispatcher()) {
        // Best-effort: a failed read POST must not crash or surface anywhere.
        val vm = TopicDetailViewModel(errorApiClient(), this)
        vm.markRead(42L, 3)
        awaitChildren(coroutineContext[Job]!!)
        // Reaching here without a thrown exception is the assertion.
    }

    @Test
    fun `markRead signals sessionExpired when the session has expired`() = runTest(UnconfinedTestDispatcher()) {
        // markRead is now the ONLY caller of markReadBestEffort (load() no longer marks read,
        // issue #206), so its session-expiry routing must stay covered: the read POST is
        // best-effort, but a genuine expiry during it must reach _sessionExpired and route the
        // user to login rather than be swallowed by the generic best-effort catch.
        val vm = TopicDetailViewModel(sessionExpiredApiClient(), this)
        vm.markRead(42L, 3)
        awaitChildren(coroutineContext[Job]!!)
        vm.sessionExpired.first()
    }

    /** MockEngine that pages posts: page N returns [pageContents]`[N]`, plus a paginator. */
    private fun pagedPostsClient(pages: Map<Int, String>, pageCount: Int): RavelryApiClient {
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/read.json")) {
                ""
            } else {
                val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
                pages.getValue(page)
            }
            respond(body, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(httpClient, FakeFeedTokenStorage())
    }

    @Test
    fun `load reports hasMore and loadMore appends the next page`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            pagedPostsClient(
                pages = mapOf(1 to postsPageJson(1, 2, 1L, 2L), 2 to postsPageJson(2, 2, 3L, 4L)),
                pageCount = 2,
            ),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val afterLoad = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(listOf(1L, 2L), afterLoad.posts.map { it.id })
        assertTrue(afterLoad.hasMore)

        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        val afterMore = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(listOf(1L, 2L, 3L, 4L), afterMore.posts.map { it.id })
        assertFalse(afterMore.hasMore)
    }

    @Test
    fun `loadMore does nothing when the thread has no more pages`() = runTest(UnconfinedTestDispatcher()) {
        var postsRequests = 0
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/read.json")) {
                ""
            } else {
                postsRequests++
                postsJson(1L) // no paginator -> hasMore = false
            }
            respond(body, HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        assertFalse(assertIs<TopicDetailState.Loaded>(vm.state.value).hasMore)

        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, postsRequests) // no second page fetched
    }

    @Test
    fun `loadMore drops posts already loaded from the previous page`() = runTest(UnconfinedTestDispatcher()) {
        // A post inserted between fetches can shift Ravelry's paging window and repeat a
        // post across two pages; the overlap must not be duplicated in the thread.
        val vm = TopicDetailViewModel(
            pagedPostsClient(
                pages = mapOf(1 to postsPageJson(1, 2, 1L, 2L), 2 to postsPageJson(2, 2, 2L, 3L)),
                pageCount = 2,
            ),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(listOf(1L, 2L, 3L), loaded.posts.map { it.id })
    }

    /** Engine paging posts where fetching page [failPage] fails with [status] (or throws). */
    private fun failingPageClient(
        firstPage: String,
        failPage: Int,
        status: HttpStatusCode? = null,
    ): RavelryApiClient {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/read.json") ->
                    respond("", HttpStatusCode.OK, headersOf())
                request.url.parameters["page"]?.toIntOrNull() == failPage ->
                    if (status != null) respond("", status, headersOf()) else error("boom")
                else -> respond(firstPage, HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(httpClient, FakeFeedTokenStorage())
    }

    @Test
    fun `loadMore clears the loading flag and signals expiry on session expiry`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            failingPageClient(postsPageJson(1, 2, 1L, 2L), failPage = 2, status = HttpStatusCode.Unauthorized),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertFalse(loaded.isLoadingMore)
        assertEquals(listOf(1L, 2L), loaded.posts.map { it.id }) // failed page not appended
        vm.sessionExpired.first()
    }

    @Test
    fun `loadMore clears the loading flag on error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            failingPageClient(postsPageJson(1, 2, 1L, 2L), failPage = 2),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertFalse(loaded.isLoadingMore)
        assertEquals(listOf(1L, 2L), loaded.posts.map { it.id })
    }

    @Test
    fun `loadUntilPost pulls forward pages until the target post is loaded`() = runTest(UnconfinedTestDispatcher()) {
        // Pages of 2 posts each; a jump to post 5 must pull pages 2 and 3 (posts 3..6).
        val vm = TopicDetailViewModel(
            pagedPostsClient(
                pages = mapOf(
                    1 to postsPageJson(1, 3, 1L, 2L),
                    2 to postsPageJson(2, 3, 3L, 4L),
                    3 to postsPageJson(3, 3, 5L, 6L),
                ),
                pageCount = 3,
            ),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadUntilPost(5)
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L, 6L), loaded.posts.map { it.id })
        assertFalse(loaded.isLoadingMore)
    }

    @Test
    fun `loadUntilPost stops as soon as the target is reached without fetching further pages`() = runTest(UnconfinedTestDispatcher()) {
        val requestedPages = mutableListOf<Int>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.endsWith("/read.json")) {
                respond("", HttpStatusCode.OK, headersOf())
            } else {
                val page = request.url.parameters["page"]?.toIntOrNull() ?: 1
                requestedPages += page
                respond(postsPageJson(page, 5, (page * 2 - 1).toLong(), (page * 2).toLong()),
                    HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadUntilPost(3) // reached after page 2 (4 posts); page 3+ must not be fetched
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(listOf(1, 2), requestedPages)
    }

    @Test
    fun `loadUntilPost does nothing when the target is already loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            pagedPostsClient(mapOf(1 to postsPageJson(1, 2, 1L, 2L)), pageCount = 2),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadUntilPost(2) // already have 2 posts
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(listOf(1L, 2L), loaded.posts.map { it.id })
        assertFalse(loaded.isLoadingMore)
    }

    @Test
    fun `loadUntilPost stops at the end of the thread when the target is beyond it`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            pagedPostsClient(
                pages = mapOf(1 to postsPageJson(1, 2, 1L, 2L), 2 to postsPageJson(2, 2, 3L, 4L)),
                pageCount = 2,
            ),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadUntilPost(999) // never reachable — must load everything then stop
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(listOf(1L, 2L, 3L, 4L), loaded.posts.map { it.id })
        assertFalse(loaded.hasMore)
        assertFalse(loaded.isLoadingMore)
    }

    @Test
    fun `loadUntilPost clears the loading flag on error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            failingPageClient(postsPageJson(1, 3, 1L, 2L), failPage = 2),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadUntilPost(5)
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertFalse(loaded.isLoadingMore)
        assertEquals(listOf(1L, 2L), loaded.posts.map { it.id })
    }

    @Test
    fun `loadUntilPost clears the loading flag and signals expiry on session expiry`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            failingPageClient(postsPageJson(1, 3, 1L, 2L), failPage = 2, status = HttpStatusCode.Unauthorized),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadUntilPost(5)
        awaitChildren(coroutineContext[Job]!!)
        assertFalse(assertIs<TopicDetailState.Loaded>(vm.state.value).isLoadingMore)
        vm.sessionExpired.first()
    }

    @Test
    fun `loadMore does nothing before the thread is loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(pagedPostsClient(mapOf(1 to postsPageJson(1, 2, 1L)), pageCount = 2), this)
        vm.loadMore() // still in the initial Loading state
        awaitChildren(coroutineContext[Job]!!)
        assertIs<TopicDetailState.Loading>(vm.state.value)
    }

    @Test
    fun `loadUntilPost does nothing before the thread is loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(pagedPostsClient(mapOf(1 to postsPageJson(1, 2, 1L)), pageCount = 2), this)
        vm.loadUntilPost(5) // still in the initial Loading state
        awaitChildren(coroutineContext[Job]!!)
        assertIs<TopicDetailState.Loading>(vm.state.value)
    }

    @Test
    fun `loadUntilPost does nothing when the thread has no more pages`() = runTest(UnconfinedTestDispatcher()) {
        var postsRequests = 0
        val engine = MockEngine { request ->
            val body = if (request.url.encodedPath.endsWith("/read.json")) "" else {
                postsRequests++
                postsJson(1L) // no paginator -> hasMore = false
            }
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadUntilPost(50) // beyond the thread, but nothing more to fetch
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, postsRequests) // only the initial page was ever requested
    }

    /**
     * suspendableRoutingApiClient that gates the topic-42 page-2 fetch on [gate], so a
     * load-more can be left in flight while the ViewModel is driven into another state.
     */
    private fun gatedSecondPageClient(gate: CompletableDeferred<Unit>): RavelryApiClient =
        suspendableRoutingApiClient { url ->
            when {
                url.encodedPath.endsWith("/read.json") -> ""
                url.encodedPath.contains("/topics/99/") -> postsPageJson(1, 1, 7L)
                url.parameters["page"] == "2" -> { gate.await(); postsPageJson(2, 2, 3L, 4L) }
                else -> postsPageJson(1, 2, 1L, 2L)
            }
        }

    @Test
    fun `loadMore ignores a concurrent call and a stale one cannot touch a newer topic`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val vm = TopicDetailViewModel(gatedSecondPageClient(gate), this)
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)

            vm.loadMore()  // fetches page 2, suspends on the gate (isLoadingMore = true)
            vm.loadMore()  // second call must no-op while the first is in flight
            vm.load(99L)   // switch topics — bumps the generation the stale load-more captured
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            // The stale page-2 result must not have leaked into topic 99's thread.
            val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
            assertEquals(listOf(7L), loaded.posts.map { it.id })
            assertFalse(loaded.isLoadingMore)
        }

    @Test
    fun `loadUntilPost from a previous topic cannot touch a newer thread`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val vm = TopicDetailViewModel(gatedSecondPageClient(gate), this)
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)

            vm.loadUntilPost(4) // needs page 2, which is gated
            vm.load(99L)        // switch topics before it can resume
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            val loaded = assertIs<TopicDetailState.Loaded>(vm.state.value)
            assertEquals(listOf(7L), loaded.posts.map { it.id })
            assertFalse(loaded.isLoadingMore)
        }

    @Test
    fun `load error message falls back to default when exception has null message`() = runTest(UnconfinedTestDispatcher()) {
        val engine = MockEngine { throw RuntimeException() }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(
            RavelryApiClient(httpClient, FakeFeedTokenStorage()),
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<TopicDetailState.Error>(vm.state.value)
        assertEquals("Failed to load replies", state.message)
    }

    @Test
    fun `load signals sessionExpired and stays Loading when session expires`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = TopicDetailViewModel(sessionExpiredApiClient(), this)
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)
            assertIs<TopicDetailState.Loading>(vm.state.value)
            // sessionExpired is filter{it}.map{} on a StateFlow, so first() returns
            // immediately without suspension when the flag is already true
            vm.sessionExpired.first()
        }

    @Test
    fun `toggleVote optimistically flips vote state and count before server responds`() = runTest {
        // Deliberately NOT UnconfinedTestDispatcher: toggleVote's network call runs in a
        // launched child coroutine that must stay suspended so we can observe the
        // synchronous optimistic update before it's overwritten by the server response.
        val vm = TopicDetailViewModel(routingApiClient { path ->
            if (path.contains("vote")) voteResponseJson("love", 1, userVoted = true) else postsJson(1L)
        }, this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val original = (vm.state.value as TopicDetailState.Loaded).posts[0]
        assertEquals(0, original.voteTotals["love"] ?: 0)

        vm.toggleVote(original, VoteType.LOVE)

        val optimistic = (vm.state.value as TopicDetailState.Loaded).posts[0]
        assertEquals(listOf("love"), optimistic.userVotes)
        assertEquals(1, optimistic.voteTotals["love"])
    }

    @Test
    fun `toggleVote replaces optimistic state with server-confirmed vote totals`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = TopicDetailViewModel(routingApiClient { path ->
                if (path.contains("vote")) voteResponseJson("funny", 5, userVoted = true) else postsJson(1L)
            }, this)
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)
            val original = (vm.state.value as TopicDetailState.Loaded).posts[0]

            vm.toggleVote(original, VoteType.FUNNY)
            awaitChildren(coroutineContext[Job]!!)

            val confirmed = (vm.state.value as TopicDetailState.Loaded).posts[0]
            assertEquals(5, confirmed.voteTotals["funny"])
            assertEquals(listOf("funny"), confirmed.userVotes)
        }

    @Test
    fun `toggleVote clears an already-cast vote`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(routingApiClient { path ->
            if (path.contains("vote")) {
                voteResponseJson("love", 0, userVoted = false)
            } else {
                """{"posts":[{"id":1,"body_html":"<p>Reply</p>","user":{"username":"user1"}}],
                    "vote_totals":{"1":{"love":1}},"user_votes":{"1":["love"]}}"""
            }
        }, this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val original = (vm.state.value as TopicDetailState.Loaded).posts[0]
        assertTrue(original.userVotes.contains("love"))

        vm.toggleVote(original, VoteType.LOVE)
        awaitChildren(coroutineContext[Job]!!)

        val updated = (vm.state.value as TopicDetailState.Loaded).posts[0]
        assertEquals(0, updated.voteTotals["love"] ?: 0)
        assertTrue(updated.userVotes.isEmpty())
    }

    @Test
    fun `toggleVote on one type does not disturb an existing vote of another type`() = runTest {
        // Deliberately NOT UnconfinedTestDispatcher — see comment on the optimistic-flip test above.
        val vm = TopicDetailViewModel(routingApiClient { path ->
            if (path.contains("vote")) {
                voteResponseJson("funny", 1, userVoted = true)
            } else {
                """{"posts":[{"id":1,"body_html":"<p>Reply</p>","user":{"username":"user1"}}],
                    "vote_totals":{"1":{"love":1}},"user_votes":{"1":["love"]}}"""
            }
        }, this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val original = (vm.state.value as TopicDetailState.Loaded).posts[0]

        vm.toggleVote(original, VoteType.FUNNY)

        val optimistic = (vm.state.value as TopicDetailState.Loaded).posts[0]
        assertTrue(optimistic.userVotes.containsAll(listOf("love", "funny")))
    }

    @Test
    fun `toggleVote computes direction from current state rather than a stale post parameter`() = runTest {
        val capturedVotedParams = mutableListOf<String?>()
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("vote")) {
                val voted = request.url.parameters["vote"]
                capturedVotedParams.add(voted)
                respond(voteResponseJson("love", if (voted == "1") 1 else 0, userVoted = voted == "1"),
                    HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
            } else {
                respond(
                    """{"posts":[{"id":1,"body_html":"<p>Reply</p>","user":{"username":"user1"}}],
                        "vote_totals":{"1":{"love":1}},"user_votes":{"1":["love"]}}""",
                    HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        // Captured once, before any toggling, and deliberately reused below as a stale reference.
        val stalePost = (vm.state.value as TopicDetailState.Loaded).posts[0]
        assertTrue(stalePost.userVotes.contains("love"))

        vm.toggleVote(stalePost, VoteType.LOVE)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("0", capturedVotedParams[0])
        assertTrue((vm.state.value as TopicDetailState.Loaded).posts[0].userVotes.isEmpty())

        // Second call passes the SAME stale `stalePost` (whose userVotes still says ["love"]).
        // If toggleVote read that parameter instead of the current state, it would compute
        // wantsVoted=false again (trying to remove an already-removed vote) instead of
        // correctly re-adding it.
        vm.toggleVote(stalePost, VoteType.LOVE)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("1", capturedVotedParams[1])
        assertTrue((vm.state.value as TopicDetailState.Loaded).posts[0].userVotes.contains("love"))
    }

    @Test
    fun `toggleVote reverts optimistic update when the vote call fails`() = runTest(UnconfinedTestDispatcher()) {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("vote")) {
                error("Simulated network error")
            } else {
                respond(postsJson(1L), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val original = (vm.state.value as TopicDetailState.Loaded).posts[0]

        vm.toggleVote(original, VoteType.LOVE)
        awaitChildren(coroutineContext[Job]!!)

        assertEquals(original, (vm.state.value as TopicDetailState.Loaded).posts[0])
    }

    @Test
    fun `toggleVote reverts and signals sessionExpired when session expires`() = runTest(UnconfinedTestDispatcher()) {
        val engine = MockEngine { request ->
            if (request.url.encodedPath.contains("vote")) {
                throw SessionExpiredException("expired")
            } else {
                respond(postsJson(1L), HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val original = (vm.state.value as TopicDetailState.Loaded).posts[0]

        vm.toggleVote(original, VoteType.LOVE)
        awaitChildren(coroutineContext[Job]!!)

        assertEquals(original, (vm.state.value as TopicDetailState.Loaded).posts[0])
        vm.sessionExpired.first()
    }

    @Test
    fun `toggleVote before any load computes wantsVoted from the post parameter and leaves state untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = TopicDetailViewModel(routingApiClient { path ->
                if (path.contains("vote")) voteResponseJson("love", 1, userVoted = true) else postsJson(1L)
            }, this)
            // Deliberately no vm.load() call: state stays Loading, so currentPost(post.id) can't
            // find anything and toggleVote must fall back to the passed-in `post` parameter.
            assertIs<TopicDetailState.Loading>(vm.state.value)

            vm.toggleVote(Post(id = 1L), VoteType.LOVE)
            awaitChildren(coroutineContext[Job]!!)

            assertIs<TopicDetailState.Loading>(vm.state.value)
        }

    @Test
    fun `toggleVote on a post no longer in the loaded list leaves the list unchanged`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = TopicDetailViewModel(routingApiClient { path ->
                if (path.contains("vote")) voteResponseJson("love", 1, userVoted = true) else postsJson(1L)
            }, this)
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)
            val before = (vm.state.value as TopicDetailState.Loaded).posts

            // id 999 isn't in the loaded list, so currentPost(999) must search the whole list,
            // find nothing, and fall back to the passed-in post parameter for direction.
            vm.toggleVote(Post(id = 999L), VoteType.LOVE)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(before, (vm.state.value as TopicDetailState.Loaded).posts)
        }

    @Test
    fun `toggleVote reverts when clearing an already-cast vote fails with a generic exception`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MockEngine { request ->
                if (request.url.encodedPath.contains("vote")) {
                    error("Simulated network error")
                } else {
                    respond(
                        """{"posts":[{"id":1,"body_html":"<p>Reply</p>","user":{"username":"user1"}}],
                            "vote_totals":{"1":{"love":1}},"user_votes":{"1":["love"]}}""",
                        HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)
            val original = (vm.state.value as TopicDetailState.Loaded).posts[0]
            assertTrue(original.userVotes.contains("love"))

            vm.toggleVote(original, VoteType.LOVE)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(original, (vm.state.value as TopicDetailState.Loaded).posts[0])
        }

    @Test
    fun `toggleVote reverts and signals sessionExpired when clearing an already-cast vote fails`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MockEngine { request ->
                if (request.url.encodedPath.contains("vote")) {
                    throw SessionExpiredException("expired")
                } else {
                    respond(
                        """{"posts":[{"id":1,"body_html":"<p>Reply</p>","user":{"username":"user1"}}],
                            "vote_totals":{"1":{"love":1}},"user_votes":{"1":["love"]}}""",
                        HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)
            val original = (vm.state.value as TopicDetailState.Loaded).posts[0]
            assertTrue(original.userVotes.contains("love"))

            vm.toggleVote(original, VoteType.LOVE)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(original, (vm.state.value as TopicDetailState.Loaded).posts[0])
            vm.sessionExpired.first()
        }

    @Test
    fun `TopicDetailState data classes support equality copy hashCode and toString`() {
        val posts = listOf<com.autom8ed.fibersocial.feed.models.Post>()
        val loaded1 = TopicDetailState.Loaded(posts)
        val loaded2 = TopicDetailState.Loaded(posts)
        assertEquals(loaded1, loaded2)
        assertEquals(loaded1.hashCode(), loaded2.hashCode())
        assertEquals(loaded1, loaded1.copy())
        assertTrue(loaded1.toString().contains("Loaded"))

        val err1 = TopicDetailState.Error("oops")
        val err2 = TopicDetailState.Error("oops")
        assertEquals(err1, err2)
        assertEquals(err1.hashCode(), err2.hashCode())
        assertNotEquals(err1, TopicDetailState.Error("other"))
        assertEquals("oops", err1.copy().message)
        assertTrue(err1.toString().contains("oops"))
    }

    private fun deleteRoutingClient(deleteResponds: String = "ok") = routingApiClient { path ->
        when {
            path.contains("/posts.json") -> postsJson(1L, 2L)
            Regex("/forum_posts/\\d+$").containsMatchIn(path) -> deleteResponds
            else -> TOKEN_PAGE_HTML
        }
    }

    @Test
    fun `deletePost removes the post and returns to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(deleteRoutingClient(), this)
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val target = (vm.state.value as TopicDetailState.Loaded).posts.first()
        vm.deletePost(target)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<DeleteState.Idle>(vm.deleteState.value)
        val state = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(listOf(2L), state.posts.map { it.id })
    }

    @Test
    fun `deletePost failure keeps the thread and reports Error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                when {
                    path.contains("/posts.json") -> postsJson(1L, 2L)
                    Regex("/forum_posts/\\d+$").containsMatchIn(path) -> error("Simulated delete failure")
                    else -> TOKEN_PAGE_HTML
                }
            },
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.deletePost((vm.state.value as TopicDetailState.Loaded).posts.first())
        awaitChildren(coroutineContext[Job]!!)
        assertIs<DeleteState.Error>(vm.deleteState.value)
        assertEquals(2, (vm.state.value as TopicDetailState.Loaded).posts.size)
    }

    @Test
    fun `deletePost failure with no exception message falls back to a default`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                when {
                    path.contains("/posts.json") -> postsJson(1L)
                    Regex("/forum_posts/\\d+$").containsMatchIn(path) -> throw IllegalStateException()
                    else -> TOKEN_PAGE_HTML
                }
            },
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.deletePost((vm.state.value as TopicDetailState.Loaded).posts.first())
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(DeleteState.Error("Failed to delete post"), vm.deleteState.value)
    }

    @Test
    fun `deletePost session expiry signals sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(sessionExpiredApiClient(), this)
        vm.deletePost(Post(id = 1L))
        awaitChildren(coroutineContext[Job]!!)
        assertIs<DeleteState.Idle>(vm.deleteState.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `acknowledgeDeleteError clears the error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(errorApiClient(), this)
        vm.deletePost(Post(id = 1L))
        awaitChildren(coroutineContext[Job]!!)
        assertIs<DeleteState.Error>(vm.deleteState.value)
        vm.acknowledgeDeleteError()
        assertIs<DeleteState.Idle>(vm.deleteState.value)
    }

    @Test
    fun `editPost replaces post body and returns to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                when {
                    path.contains("/forum_posts/") -> forumPostJson(id = 1L, body = "edited body", bodyHtml = "<p>edited body</p>")
                    else -> postsJson(1L, 2L)
                }
            },
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        val target = (vm.state.value as TopicDetailState.Loaded).posts.first()
        vm.editPost(target, "edited body")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EditState.Idle>(vm.editState.value)
        val updated = (vm.state.value as TopicDetailState.Loaded).posts.first { it.id == 1L }
        assertEquals("edited body", updated.body)
        assertEquals("<p>edited body</p>", updated.bodyHtml)
    }

    @Test
    fun `editPost ignores blank body`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(errorApiClient(), this)
        vm.editPost(Post(id = 1L), "   ")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EditState.Idle>(vm.editState.value)
    }

    @Test
    fun `editPost failure keeps original body and reports Error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                when {
                    path.contains("/forum_posts/") -> error("Simulated edit failure")
                    else -> postsJson(1L)
                }
            },
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.editPost((vm.state.value as TopicDetailState.Loaded).posts.first(), "new text")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EditState.Error>(vm.editState.value)
        assertEquals("Reply 1", (vm.state.value as TopicDetailState.Loaded).posts.first().body)
    }

    @Test
    fun `editPost failure with no exception message falls back to a default`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                when {
                    path.contains("/forum_posts/") -> throw IllegalStateException()
                    else -> postsJson(1L)
                }
            },
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.editPost((vm.state.value as TopicDetailState.Loaded).posts.first(), "new text")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(EditState.Error("Failed to save edit"), vm.editState.value)
    }

    @Test
    fun `editPost session expiry signals sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(sessionExpiredApiClient(), this)
        vm.editPost(Post(id = 1L), "text")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EditState.Idle>(vm.editState.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `acknowledgeEditError clears the error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(errorApiClient(), this)
        vm.editPost(Post(id = 1L), "text")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EditState.Error>(vm.editState.value)
        vm.acknowledgeEditError()
        assertIs<EditState.Idle>(vm.editState.value)
    }

    @Test
    fun `sendReply appends created post and transitions to Sent`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                if (path.contains("/reply.json")) replyResponseJson(id = 99L) else postsJson(1L)
            },
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.sendReply(42L, "My new reply")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ReplyState.Sent>(vm.replyState.value)
        val state = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(listOf(1L, 99L), state.posts.map { it.id })
    }

    @Test
    fun `sendReply ignores blank bodies`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(errorApiClient(), this)
        vm.sendReply(42L, "   ")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ReplyState.Idle>(vm.replyState.value)
    }

    @Test
    fun `sendReply trims whitespace before submitting`() = runTest(UnconfinedTestDispatcher()) {
        var sentBody: String? = null
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                sentBody = (request.body as io.ktor.client.request.forms.FormDataContent)
                    .formData["body"]
                respond(replyResponseJson(), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            } else {
                respond(postsJson(1L), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
        vm.sendReply(42L, "  hello  ")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("hello", sentBody)
    }

    @Test
    fun `sendReply failure keeps thread state and reports Error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                if (path.contains("/reply.json")) error("Simulated network error") else postsJson(1L, 2L)
            },
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        vm.sendReply(42L, "My new reply")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ReplyState.Error>(vm.replyState.value)
        val state = assertIs<TopicDetailState.Loaded>(vm.state.value)
        assertEquals(2, state.posts.size)
    }

    @Test
    fun `sendReply session expiry signals sessionExpired and returns to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(sessionExpiredApiClient(), this)
        vm.sendReply(42L, "My new reply")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ReplyState.Idle>(vm.replyState.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    /**
     * Client whose reply POSTs suspend until [gate] completes (to interleave navigation
     * with an in-flight send); GETs serve a one-post thread whose post ID is the topic ID.
     */
    private fun gatedReplyApiClient(
        gate: CompletableDeferred<Unit>,
        postOutcome: suspend () -> String,
    ): RavelryApiClient {
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                gate.await()
                respond(postOutcome(), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            } else {
                val topicId = Regex("""/topics/(\d+)/""").find(request.url.encodedPath)!!.groupValues[1].toLong()
                respond(postsJson(topicId), HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(httpClient, FakeFeedTokenStorage())
    }

    @Test
    fun `a stale send failure does not surface an error on the new topic`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = TopicDetailViewModel(gatedReplyApiClient(gate, { error("late failure") }), this)
        vm.load(42L)
        vm.sendReply(42L, "typed before navigating")

        vm.load(43L)
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)

        assertIs<ReplyState.Idle>(vm.replyState.value)
    }

    @Test
    fun `a stale send session expiry still signals but leaves composer state alone`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = TopicDetailViewModel(
            gatedReplyApiClient(gate, { throw com.autom8ed.fibersocial.auth.SessionExpiredException("expired") }),
            this,
        )
        vm.load(42L)
        vm.sendReply(42L, "typed before navigating")

        vm.load(43L)
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)

        assertIs<ReplyState.Idle>(vm.replyState.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `a reply that succeeds while the thread is not loaded still reports Sent`() = runTest(UnconfinedTestDispatcher()) {
        // Topic failed to load (state = Error, composer visible is a UI concern); the
        // send itself succeeded on the server, so the composer must acknowledge it.
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                if (path.contains("/reply.json")) replyResponseJson(id = 99L) else error("load failed")
            },
            this,
        )
        vm.load(42L)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<TopicDetailState.Error>(vm.state.value)

        vm.sendReply(42L, "still works")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ReplyState.Sent>(vm.replyState.value)
        assertIs<TopicDetailState.Error>(vm.state.value)
    }

    @Test
    fun `acknowledgeReplySent resets Sent to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                if (path.contains("/reply.json")) replyResponseJson() else postsJson(1L)
            },
            this,
        )
        vm.sendReply(42L, "reply")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ReplyState.Sent>(vm.replyState.value)
        vm.acknowledgeReplySent()
        assertIs<ReplyState.Idle>(vm.replyState.value)
    }

    @Test
    fun `a second send while one is in flight issues exactly one request`() =
        runTest(UnconfinedTestDispatcher()) {
            val releaseFirst = CompletableDeferred<Unit>()
            var postCount = 0
            // Gate only the reply POST, not the posts fetch — gating that would park
            // load() before it reaches Loaded and inflate the reply count this asserts on.
            val engine = MockEngine { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/reply.json")) {
                    postCount++
                    releaseFirst.await()
                    respond(replyResponseJson(id = 99L), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                } else {
                    respond(postsJson(1L), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }

            vm.sendReply(1L, "first")
            vm.sendReply(1L, "second")
            releaseFirst.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(1, postCount)
        }

    @Test
    fun `a send that outlives its topic touches neither the new thread nor the composer`() =
        runTest(UnconfinedTestDispatcher()) {
            val releasePost = CompletableDeferred<Unit>()
            // Gate only the reply POST — the posts fetch must fall through so the thread
            // can reach Loaded.
            val engine = MockEngine { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.endsWith("/reply.json")) {
                    releasePost.await()
                    respond(replyResponseJson(id = 99L), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                } else {
                    respond(postsJson(7L), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }
            vm.sendReply(1L, "late reply")

            // Navigate to another topic while the send is parked mid-flight.
            vm.load(2L)
            vm.state.first { it is TopicDetailState.Loaded }
            releasePost.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            // Topic 2's thread must not gain topic 1's reply, and the composer must
            // stay Idle rather than flashing a stale Sent (which would wipe a draft).
            val posts = (vm.state.value as TopicDetailState.Loaded).posts
            assertTrue(posts.none { it.id == 99L })
            assertIs<ReplyState.Idle>(vm.replyState.value)
        }

    @Test
    fun `a failed delete that outlives its topic shows no stale error dialog`() =
        runTest(UnconfinedTestDispatcher()) {
            val releaseDelete = CompletableDeferred<Unit>()
            val engine = MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.startsWith("/forum_posts/") -> {
                        releaseDelete.await()
                        respond("nope", HttpStatusCode.Forbidden)
                    }
                    request.url.encodedPath == "/" ->
                        respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                    else ->
                        respond(postsJson(7L), HttpStatusCode.OK,
                            headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }
            val victim = (vm.state.value as TopicDetailState.Loaded).posts.first()
            vm.deletePost(victim)

            vm.load(2L)
            vm.state.first { it is TopicDetailState.Loaded }
            releaseDelete.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            // The failure belongs to topic 1; topic 2 must not pop its error dialog.
            assertIs<DeleteState.Idle>(vm.deleteState.value)
        }

    @Test
    fun `a successful delete that outlives its topic leaves the new thread untouched`() =
        runTest(UnconfinedTestDispatcher()) {
            val releaseDelete = CompletableDeferred<Unit>()
            val engine = MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.startsWith("/forum_posts/") -> {
                        releaseDelete.await()
                        respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                    }
                    request.url.encodedPath == "/" ->
                        respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                    else ->
                        respond(postsJson(7L), HttpStatusCode.OK,
                            headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }
            val victim = (vm.state.value as TopicDetailState.Loaded).posts.first()
            vm.deletePost(victim)

            vm.load(2L)
            vm.state.first { it is TopicDetailState.Loaded }
            releaseDelete.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            // The delete belongs to topic 1; topic 2's thread must keep all its posts.
            val posts = (vm.state.value as TopicDetailState.Loaded).posts
            assertTrue(posts.any { it.id == victim.id })
        }

    @Test
    fun `a delete that completes during a same-topic reload still applies`() =
        runTest(UnconfinedTestDispatcher()) {
            // Regression: load() used to bump topicGeneration on every call, including a
            // pull-to-refresh of the SAME topic — which silently discarded a delete/edit's
            // outcome (the coroutine's captured generation no longer matched by the time
            // it resolved), even though nothing about the topic actually changed.
            val releaseDelete = CompletableDeferred<Unit>()
            var loadCount = 0
            val engine = MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.startsWith("/forum_posts/") -> {
                        releaseDelete.await()
                        respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                    }
                    // fetchAuthenticityToken() GETs the bare WWW_URL — Ktor reports that as
                    // an empty encodedPath, not "/".
                    request.url.encodedPath.isEmpty() ->
                        respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                    else -> {
                        // The reload's response carries an extra post (99) not in the
                        // initial load's — a marker distinguishing "the refresh actually
                        // landed" from the initial Loaded value, which has identical shape
                        // otherwise and so can't be waited on via `it is Loaded` alone.
                        loadCount++
                        val ids = if (loadCount == 1) longArrayOf(7L) else longArrayOf(7L, 99L)
                        respond(postsJson(*ids), HttpStatusCode.OK,
                            headersOf("Content-Type", ContentType.Application.Json.toString()))
                    }
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }
            val victim = (vm.state.value as TopicDetailState.Loaded).posts.first()
            vm.deletePost(victim)

            // A pull-to-refresh of the SAME topic while the delete is still in flight.
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded && it.posts.any { p -> p.id == 99L } }

            releaseDelete.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertIs<DeleteState.Idle>(vm.deleteState.value)
            val posts = (vm.state.value as TopicDetailState.Loaded).posts
            assertTrue(posts.none { it.id == victim.id })
        }

    @Test
    fun `an edit that outlives its topic touches neither the new thread nor the editor`() =
        runTest(UnconfinedTestDispatcher()) {
            val releaseEdit = CompletableDeferred<Unit>()
            val engine = MockEngine { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.startsWith("/forum_posts/")) {
                    releaseEdit.await()
                    respond(forumPostJson(id = 7L, body = "late edit", bodyHtml = "<p>late edit</p>"),
                        HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
                } else {
                    respond(postsJson(7L), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }
            val victim = (vm.state.value as TopicDetailState.Loaded).posts.first()
            vm.editPost(victim, "late edit")

            // Navigate to another topic while the edit is parked mid-flight.
            vm.load(2L)
            vm.state.first { it is TopicDetailState.Loaded }
            releaseEdit.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            // Topic 2's thread must not gain topic 1's edited body, and the editor
            // must stay Idle rather than flashing a stale Idle-from-elsewhere state.
            val posts = (vm.state.value as TopicDetailState.Loaded).posts
            assertTrue(posts.none { it.body == "late edit" })
            assertIs<EditState.Idle>(vm.editState.value)
        }

    @Test
    fun `a stale delete session expiry still signals but leaves delete state alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val releaseDelete = CompletableDeferred<Unit>()
            val engine = MockEngine { request ->
                when {
                    request.method == HttpMethod.Post && request.url.encodedPath.startsWith("/forum_posts/") -> {
                        releaseDelete.await()
                        respond("", HttpStatusCode.Found,
                            headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"))
                    }
                    request.url.host == "www.ravelry.com" ->
                        respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                    else ->
                        respond(postsJson(7L), HttpStatusCode.OK,
                            headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }
            val victim = (vm.state.value as TopicDetailState.Loaded).posts.first()
            vm.deletePost(victim)

            vm.load(2L)
            vm.state.first { it is TopicDetailState.Loaded }
            releaseDelete.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertIs<DeleteState.Idle>(vm.deleteState.value)
            assertEquals(Unit, vm.sessionExpired.first())
        }

    @Test
    fun `a stale edit failure does not surface an error on the new topic`() =
        runTest(UnconfinedTestDispatcher()) {
            val releaseEdit = CompletableDeferred<Unit>()
            val engine = MockEngine { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.startsWith("/forum_posts/")) {
                    releaseEdit.await()
                    respond("<html>down for maintenance</html>", HttpStatusCode.OK,
                        headersOf("Content-Type", "text/html"))
                } else {
                    respond(postsJson(7L), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }
            val victim = (vm.state.value as TopicDetailState.Loaded).posts.first()
            vm.editPost(victim, "late edit")

            vm.load(2L)
            vm.state.first { it is TopicDetailState.Loaded }
            releaseEdit.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertIs<EditState.Idle>(vm.editState.value)
        }

    @Test
    fun `a stale edit session expiry still signals but leaves editor state alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val releaseEdit = CompletableDeferred<Unit>()
            val engine = MockEngine { request ->
                if (request.method == HttpMethod.Post && request.url.encodedPath.startsWith("/forum_posts/")) {
                    releaseEdit.await()
                    respond("", HttpStatusCode.Unauthorized)
                } else {
                    respond(postsJson(7L), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()))
                }
            }
            val httpClient = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = TopicDetailViewModel(RavelryApiClient(httpClient, FakeFeedTokenStorage()), this)
            vm.load(1L)
            vm.state.first { it is TopicDetailState.Loaded }
            val victim = (vm.state.value as TopicDetailState.Loaded).posts.first()
            vm.editPost(victim, "late edit")

            vm.load(2L)
            vm.state.first { it is TopicDetailState.Loaded }
            releaseEdit.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertIs<EditState.Idle>(vm.editState.value)
            assertEquals(Unit, vm.sessionExpired.first())
        }

    @Test
    fun `a delete that succeeds while the thread is not loaded leaves state alone`() =
        runTest(UnconfinedTestDispatcher()) {
            // Topic failed to load (state = Error); the delete itself succeeded on the
            // server, so it must not crash trying to update a thread that isn't Loaded.
            val fakePost = Post(id = 7L, bodyHtml = "<p>hi</p>", user = RavelryUser(username = "me"))
            val vm = TopicDetailViewModel(
                routingApiClient { path ->
                    when {
                        path.startsWith("/forum_posts/") -> "ok"
                        path.isEmpty() -> TOKEN_PAGE_HTML
                        else -> error("load failed")
                    }
                },
                this,
            )
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)
            assertIs<TopicDetailState.Error>(vm.state.value)

            vm.deletePost(fakePost)
            awaitChildren(coroutineContext[Job]!!)
            assertIs<DeleteState.Idle>(vm.deleteState.value)
            assertIs<TopicDetailState.Error>(vm.state.value)
        }

    @Test
    fun `an edit that succeeds while the thread is not loaded leaves state alone`() =
        runTest(UnconfinedTestDispatcher()) {
            val fakePost = Post(id = 7L, bodyHtml = "<p>hi</p>", user = RavelryUser(username = "me"))
            val vm = TopicDetailViewModel(
                routingApiClient { path ->
                    if (path.startsWith("/forum_posts/")) forumPostJson(id = 7L, body = "edited", bodyHtml = "<p>edited</p>")
                    else error("load failed")
                },
                this,
            )
            vm.load(42L)
            awaitChildren(coroutineContext[Job]!!)
            assertIs<TopicDetailState.Error>(vm.state.value)

            vm.editPost(fakePost, "edited")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<EditState.Idle>(vm.editState.value)
            assertIs<TopicDetailState.Error>(vm.state.value)
        }

    @Test
    fun `load resets leftover edit and delete state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(
            routingApiClient { path ->
                if (path.contains("/forum_posts/")) error("boom") else postsJson(1L)
            },
            this,
        )
        vm.load(1L)
        awaitChildren(coroutineContext[Job]!!)
        val post = (vm.state.value as TopicDetailState.Loaded).posts.first()
        vm.editPost(post, "new body")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EditState.Error>(vm.editState.value)

        vm.load(2L)
        assertIs<EditState.Idle>(vm.editState.value)
        assertIs<DeleteState.Idle>(vm.deleteState.value)
    }

    @Test
    fun `acknowledgeReplySent does not clear an Error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(errorApiClient(), this)
        vm.sendReply(42L, "reply")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ReplyState.Error>(vm.replyState.value)
        vm.acknowledgeReplySent()
        assertIs<ReplyState.Error>(vm.replyState.value)
    }

    // --- Scroll position (issue #243) ---
    // Persisted as a plain field on the ViewModel — not Compose state — so it survives
    // TopicDetailScreen being torn down and recomposed (e.g. a project link's page shown
    // over it via FeedScreen's early-return branches).

    @Test
    fun `scrollPositionFor defaults to the top for a topic never recorded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(routingApiClient { postsJson(1L) }, this)
        assertEquals(ScrollPosition.TOP, vm.scrollPositionFor(999L))
    }

    @Test
    fun `setScrollPosition then scrollPositionFor returns what was recorded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(routingApiClient { postsJson(1L) }, this)
        vm.setScrollPosition(topicId = 42L, index = 7, offset = 130)
        assertEquals(ScrollPosition(index = 7, offset = 130), vm.scrollPositionFor(42L))
    }

    @Test
    fun `scroll positions for different topics do not clobber each other`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(routingApiClient { postsJson(1L) }, this)
        vm.setScrollPosition(topicId = 1L, index = 3, offset = 10)
        vm.setScrollPosition(topicId = 2L, index = 9, offset = 40)
        assertEquals(ScrollPosition(3, 10), vm.scrollPositionFor(1L))
        assertEquals(ScrollPosition(9, 40), vm.scrollPositionFor(2L))
    }
}
