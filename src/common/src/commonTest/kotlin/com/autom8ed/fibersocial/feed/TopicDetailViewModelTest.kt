package com.autom8ed.fibersocial.feed

import io.ktor.http.HttpMethod
import kotlinx.coroutines.CompletableDeferred
import com.autom8ed.fibersocial.auth.AuthToken
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.Post
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
            val engine = MockEngine { request ->
                if (request.method == HttpMethod.Post) {
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
            val engine = MockEngine { request ->
                if (request.method == HttpMethod.Post) {
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
    fun `acknowledgeReplySent does not clear an Error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = TopicDetailViewModel(errorApiClient(), this)
        vm.sendReply(42L, "reply")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ReplyState.Error>(vm.replyState.value)
        vm.acknowledgeReplySent()
        assertIs<ReplyState.Error>(vm.replyState.value)
    }
}
