package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.AuthToken
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.VoteType
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
    fun `toggleVote computes direction from current state, not a stale post parameter`() = runTest {
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
}
