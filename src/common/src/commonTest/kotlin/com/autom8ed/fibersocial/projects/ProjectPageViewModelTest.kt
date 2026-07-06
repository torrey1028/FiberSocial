package com.autom8ed.fibersocial.projects

import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.feed.errorApiClient
import com.autom8ed.fibersocial.feed.nullMessageApiClient
import com.autom8ed.fibersocial.feed.routingApiClient
import com.autom8ed.fibersocial.feed.sessionExpiredApiClient
import com.autom8ed.fibersocial.feed.suspendableRoutingApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectPageViewModelTest {
    // Drains repeatedly: open() launches loadPattern/loadComments (and postComment
    // appends) as further children after its own body runs, so a single snapshot-join
    // would miss them.
    private suspend fun awaitChildren(job: Job) {
        do {
            job.children.toList().forEach { it.join() }
        } while (job.children.any { it.isActive })
    }

    private val link = ProjectLink("yarnie", "autumn-socks")

    private fun projectApiClient(): RavelryApiClient = routingApiClient {
        """{"project":{"id":7,"name":"Autumn Socks","permalink":"autumn-socks",
            "pattern_name":"Vanilla Socks","status_name":"Finished","progress":100,
            "notes":"So *cozy*","notes_html":"<p>So <em>cozy</em></p>",
            "tag_names":["socks"],
            "photos":[{"id":901,"medium_url":"https://img.example/m1.jpg"}]}}"""
    }

    @Test
    fun `initial state is Hidden`() = runTest(UnconfinedTestDispatcher()) {
        assertIs<ProjectPageState.Hidden>(ProjectPageViewModel(projectApiClient(), this).state.value)
    }

    @Test
    fun `open loads the project detail`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(projectApiClient(), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<ProjectPageState.Loaded>(vm.state.value)
        assertEquals(link, state.link)
        assertEquals("Autumn Socks", state.project.name)
        assertEquals("Vanilla Socks", state.project.patternName)
        assertEquals("Finished", state.project.statusName)
        assertEquals(100, state.project.progress)
        assertEquals("So *cozy*", state.project.notes)
        assertEquals(listOf("socks"), state.project.tagNames)
        assertEquals(listOf(901L), state.project.photos.map { it.id })
    }

    @Test
    fun `load failure reports the error and retry refetches`() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val client = suspendableRoutingApiClient { url ->
            calls++
            if (calls == 1) error("boom")
            """{"project":{"id":7,"name":"Autumn Socks"}}"""
        }
        val vm = ProjectPageViewModel(client, this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("boom", assertIs<ProjectPageState.Error>(vm.state.value).message)
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Loaded>(vm.state.value)
    }

    @Test
    fun `load failure without a message uses a fallback`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(nullMessageApiClient(), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("Couldn't load the project", assertIs<ProjectPageState.Error>(vm.state.value).message)
    }

    @Test
    fun `retry outside an error state is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(errorApiClient(), this)
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Hidden>(vm.state.value)
    }

    @Test
    fun `session expiry hides the page and signals sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(sessionExpiredApiClient(), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Hidden>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `session expiry from an open dropped after dismissal does not signal expiry`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = ProjectPageViewModel(
            suspendableRoutingApiClient { _ ->
                gate.await()
                throw com.autom8ed.fibersocial.auth.SessionExpiredException("expired")
            },
            this,
        )
        vm.open(link)
        vm.dismiss()          // supersedes the open (generation++)
        gate.complete(Unit)   // the now-stale open throws SessionExpiredException
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Hidden>(vm.state.value)
        // A load the user already dismissed must not yank them to the login screen.
        assertEquals(null, withTimeoutOrNull(1_000) { vm.sessionExpired.first() })
    }

    /** Routes project detail, pattern, and comments by path for the fuller flows. */
    private fun fullApiClient(
        patternId: Long? = 5L,
        comments: String = """{"comments":[{"id":1,"comment_html":"<p>nice</p>",
            "created_at":"2026-01-01T00:00:00Z","user":{"username":"fan"}}]}""",
    ) = routingApiClient { path ->
        when {
            path.endsWith("/comments.json") -> comments
            path.startsWith("/patterns/") ->
                """{"pattern":{"id":5,"name":"Vanilla Socks","permalink":"vanilla-socks",
                    "pattern_author":{"name":"Jane"}}}"""
            else -> """{"project":{"id":7,"name":"Autumn Socks","permalink":"autumn-socks"${
                if (patternId != null) ""","pattern_id":$patternId""" else ""
            }}}"""
        }
    }

    @Test
    fun `open also loads the pattern and comments`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(fullApiClient(), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Loaded>(vm.state.value)
        assertEquals("Vanilla Socks", vm.pattern.value?.name)
        assertEquals("Jane", vm.pattern.value?.author?.name)
        val comments = assertIs<ProjectCommentsState.Loaded>(vm.commentsState.value)
        assertEquals(listOf(1L), comments.comments.map { it.id })
    }

    @Test
    fun `a project without a pattern id skips the pattern lookup`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(fullApiClient(patternId = null), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Loaded>(vm.state.value)
        assertEquals(null, vm.pattern.value)
    }

    @Test
    fun `a comments failure leaves the page loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") -> error("comments boom")
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Loaded>(vm.state.value)
        assertEquals("comments boom", assertIs<ProjectCommentsState.Error>(vm.commentsState.value).message)
    }

    @Test
    fun `postComment appends the new comment`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") -> """{"comments":[]}"""
                    path == "/comments/create.json" ->
                        """{"comment":{"id":99,"comment_html":"<p>mine</p>","user":{"username":"me"}}}"""
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.postComment("mine")
        awaitChildren(coroutineContext[Job]!!)
        // Success is signalled as Posted (the composer clears its text on it), then the
        // composer acknowledges back to Idle.
        assertIs<CommentPostState.Posted>(vm.postState.value)
        val comments = assertIs<ProjectCommentsState.Loaded>(vm.commentsState.value)
        assertEquals(listOf(99L), comments.comments.map { it.id })
        vm.acknowledgePosted()
        assertIs<CommentPostState.Idle>(vm.postState.value)
    }

    @Test
    fun `postComment starts a fresh thread when comments never loaded`() = runTest(UnconfinedTestDispatcher()) {
        // Comments fail to load, then a post succeeds — the new comment becomes the
        // whole (previously errored) thread rather than being dropped.
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") -> error("comments down")
                    path == "/comments/create.json" ->
                        """{"comment":{"id":99,"comment_html":"<p>mine</p>","user":{"username":"me"}}}"""
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectCommentsState.Error>(vm.commentsState.value)
        vm.postComment("mine")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(listOf(99L), assertIs<ProjectCommentsState.Loaded>(vm.commentsState.value).comments.map { it.id })
    }

    @Test
    fun `deleteComment removes the comment on success`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") ->
                        """{"comments":[{"id":1,"comment_html":"<p>a</p>"},{"id":2,"comment_html":"<p>b</p>"}]}"""
                    path == "/comments/2.json" -> ""
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.deleteComment(ProjectComment(id = 2, commentHtml = "<p>b</p>"))
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(listOf(1L), assertIs<ProjectCommentsState.Loaded>(vm.commentsState.value).comments.map { it.id })
    }

    @Test
    fun `a double-tapped delete only fires one request for the comment`() = runTest(UnconfinedTestDispatcher()) {
        // Deletion is pessimistic (the row lingers), so a second confirm before the first
        // returns would fire a second DELETE that 403s on the gone comment and shows a
        // misleading re-login prompt. The in-flight guard must drop the repeat.
        var deletes = 0
        val gate = CompletableDeferred<Unit>()
        val vm = ProjectPageViewModel(
            suspendableRoutingApiClient { url ->
                when {
                    url.encodedPath.endsWith("/comments.json") -> """{"comments":[{"id":2,"comment_html":"<p>b</p>"}]}"""
                    url.encodedPath == "/comments/2.json" -> { deletes++; gate.await(); "" }
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        val comment = ProjectComment(id = 2, commentHtml = "<p>b</p>")
        vm.deleteComment(comment) // in flight, blocked on the gate
        vm.deleteComment(comment) // must be ignored while the first is in flight
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, deletes)
        assertEquals(emptyList(), assertIs<ProjectCommentsState.Loaded>(vm.commentsState.value).comments)
    }

    @Test
    fun `deleteComment succeeding while comments are unloaded is a safe no-op`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") -> error("comments down")
                    path == "/comments/1.json" -> ""
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectCommentsState.Error>(vm.commentsState.value)
        vm.deleteComment(ProjectComment(id = 1, commentHtml = "<p>a</p>"))
        awaitChildren(coroutineContext[Job]!!)
        // No Loaded thread to mutate; state stays as the load error, no crash.
        assertIs<ProjectCommentsState.Error>(vm.commentsState.value)
    }

    @Test
    fun `a 403 on deleteComment surfaces the re-login prompt`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient(route = { path ->
                when {
                    path.endsWith("/comments.json") -> """{"comments":[{"id":1,"comment_html":"<p>a</p>"}]}"""
                    path == "/comments/1.json" ->
                        throw com.autom8ed.fibersocial.auth.ForbiddenException("nope")
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            }),
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.deleteComment(ProjectComment(id = 1, commentHtml = "<p>a</p>"))
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(
            ProjectPageViewModel.COMMENT_PERMISSION_MESSAGE,
            assertIs<CommentPostState.Error>(vm.postState.value).message,
        )
        // The comment stays: deletion is pessimistic.
        assertEquals(listOf(1L), assertIs<ProjectCommentsState.Loaded>(vm.commentsState.value).comments.map { it.id })
    }

    @Test
    fun `a generic deleteComment failure reports the error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") -> """{"comments":[{"id":1,"comment_html":"<p>a</p>"}]}"""
                    path == "/comments/1.json" -> error("boom")
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.deleteComment(ProjectComment(id = 1, commentHtml = "<p>a</p>"))
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("boom", assertIs<CommentPostState.Error>(vm.postState.value).message)
    }

    @Test
    fun `session expiry while deleting signals expiry`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") -> """{"comments":[{"id":1,"comment_html":"<p>a</p>"}]}"""
                    path == "/comments/1.json" ->
                        throw com.autom8ed.fibersocial.auth.SessionExpiredException("expired")
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.deleteComment(ProjectComment(id = 1, commentHtml = "<p>a</p>"))
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `session expiry from a delete dropped after dismissal does not signal expiry`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = ProjectPageViewModel(
            suspendableRoutingApiClient { url ->
                when {
                    url.encodedPath.endsWith("/comments.json") -> """{"comments":[{"id":1,"comment_html":"<p>a</p>"}]}"""
                    url.encodedPath == "/comments/1.json" -> {
                        gate.await()
                        throw com.autom8ed.fibersocial.auth.SessionExpiredException("expired")
                    }
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.deleteComment(ProjectComment(id = 1, commentHtml = "<p>a</p>"))
        vm.dismiss() // supersedes the delete (generation++)
        gate.complete(Unit) // the now-stale delete throws SessionExpiredException
        awaitChildren(coroutineContext[Job]!!)
        // A delete the user already dismissed must not yank them to the login screen.
        assertEquals(null, withTimeoutOrNull(1_000) { vm.sessionExpired.first() })
    }

    @Test
    fun `comments that load after a re-open do not attach to the new page`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = ProjectPageViewModel(
            suspendableRoutingApiClient { url ->
                when {
                    url.encodedPath.endsWith("/comments.json") -> {
                        gate.await()
                        """{"comments":[{"id":1,"comment_html":"<p>stale</p>"}]}"""
                    }
                    // Name the project after its own permalink — deterministic regardless of
                    // which open's project resolves first (the generation guard, not resolve
                    // order, decides the winner; a mutable counter would race).
                    else -> {
                        val permalink = url.encodedPath.substringAfterLast('/').removeSuffix(".json")
                        """{"project":{"id":${permalink.length},"name":"$permalink"}}"""
                    }
                }
            },
            this,
        )
        vm.open(link)                        // gen 1: comments gated
        vm.open(ProjectLink("yarnie", "hat")) // gen 2: comments also gated
        gate.complete(Unit)                  // both land; gen-1's is stale
        awaitChildren(coroutineContext[Job]!!)
        // The gen-2 comments won (its own gated load); gen-1's stale result was dropped.
        assertIs<ProjectCommentsState.Loaded>(vm.commentsState.value)
        assertEquals("hat", assertIs<ProjectPageState.Loaded>(vm.state.value).project.name)
    }

    @Test
    fun `a delete error that lands after dismissal is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = ProjectPageViewModel(
            suspendableRoutingApiClient { url ->
                when {
                    url.encodedPath.endsWith("/comments.json") -> """{"comments":[{"id":1,"comment_html":"<p>a</p>"}]}"""
                    url.encodedPath == "/comments/1.json" -> { gate.await(); error("boom") }
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.deleteComment(ProjectComment(id = 1, commentHtml = "<p>a</p>"))
        vm.dismiss()             // bumps generation
        gate.complete(Unit)      // delete fails against a stale generation
        awaitChildren(coroutineContext[Job]!!)
        // The stale failure must not raise a post-error on the dismissed page.
        assertIs<ProjectPageState.Hidden>(vm.state.value)
        assertIs<CommentPostState.Idle>(vm.postState.value)
    }

    @Test
    fun `a delete that lands after dismissal is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val vm = ProjectPageViewModel(
            suspendableRoutingApiClient { url ->
                when {
                    url.encodedPath.endsWith("/comments.json") -> """{"comments":[{"id":1,"comment_html":"<p>a</p>"}]}"""
                    url.encodedPath == "/comments/1.json" -> { gate.await(); "" }
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.deleteComment(ProjectComment(id = 1, commentHtml = "<p>a</p>"))
        vm.dismiss()
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Hidden>(vm.state.value)
    }

    @Test
    fun `a comment post that lands after dismissal is dropped`() = runTest(UnconfinedTestDispatcher()) {
        val postGate = CompletableDeferred<Unit>()
        val vm = ProjectPageViewModel(
            suspendableRoutingApiClient { url ->
                when {
                    url.encodedPath.endsWith("/comments.json") -> """{"comments":[]}"""
                    url.encodedPath == "/comments/create.json" -> {
                        postGate.await()
                        """{"comment":{"id":99,"comment_html":"<p>mine</p>"}}"""
                    }
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.postComment("mine")   // in flight, gated
        vm.dismiss()             // bumps generation
        postGate.complete(Unit)  // post resolves against a stale generation
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Hidden>(vm.state.value)
    }

    @Test
    fun `session expiry from a post dropped after dismissal does not signal expiry`() = runTest(UnconfinedTestDispatcher()) {
        val postGate = CompletableDeferred<Unit>()
        val vm = ProjectPageViewModel(
            suspendableRoutingApiClient { url ->
                when {
                    url.encodedPath.endsWith("/comments.json") -> """{"comments":[]}"""
                    url.encodedPath == "/comments/create.json" -> {
                        postGate.await()
                        throw com.autom8ed.fibersocial.auth.SessionExpiredException("expired")
                    }
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.postComment("mine")  // in flight, gated
        vm.dismiss()            // supersedes the post (generation++)
        postGate.complete(Unit) // the now-stale post throws SessionExpiredException
        awaitChildren(coroutineContext[Job]!!)
        // A post the user already dismissed must not yank them to the login screen.
        assertEquals(null, withTimeoutOrNull(1_000) { vm.sessionExpired.first() })
    }

    @Test
    fun `postComment ignores a blank body`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(fullApiClient(), this)
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.postComment("   ")
        assertIs<CommentPostState.Idle>(vm.postState.value)
    }

    @Test
    fun `postComment before the project loads is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(errorApiClient(), this)
        vm.postComment("mine")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<CommentPostState.Idle>(vm.postState.value)
    }

    @Test
    fun `a generic postComment failure reports the error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") -> """{"comments":[]}"""
                    path == "/comments/create.json" -> error("nope")
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.postComment("mine")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("nope", assertIs<CommentPostState.Error>(vm.postState.value).message)
    }

    @Test
    fun `session expiry while posting resets to idle and signals`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") -> """{"comments":[]}"""
                    path == "/comments/create.json" ->
                        throw com.autom8ed.fibersocial.auth.SessionExpiredException("expired")
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.postComment("mine")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<CommentPostState.Idle>(vm.postState.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `a session expiry while loading comments signals without failing the page`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient { path ->
                when {
                    path.endsWith("/comments.json") ->
                        throw com.autom8ed.fibersocial.auth.SessionExpiredException("expired")
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            },
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        // The page stays loaded; comments just never arrive, and expiry is signalled.
        assertIs<ProjectPageState.Loaded>(vm.state.value)
        assertIs<ProjectCommentsState.Loading>(vm.commentsState.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `re-opening supersedes the first open's pattern and comment results`() = runTest(UnconfinedTestDispatcher()) {
        val firstGate = CompletableDeferred<Unit>()
        val client = suspendableRoutingApiClient { url ->
            when {
                // First project resolves immediately; its secondary loads block on the gate.
                url.encodedPath.endsWith("/comments.json") -> {
                    firstGate.await()
                    """{"comments":[{"id":1,"comment_html":"<p>stale</p>"}]}"""
                }
                url.encodedPath.startsWith("/patterns/") -> {
                    firstGate.await()
                    """{"pattern":{"id":5,"name":"Stale","permalink":"stale"}}"""
                }
                else -> {
                    // Name the project after its own permalink — deterministic regardless of
                    // which open's project resolves first (a mutable resolution-order counter
                    // would race, since the generation guard, not resolution order, decides
                    // the winner).
                    val permalink = url.encodedPath.substringAfterLast('/').removeSuffix(".json")
                    """{"project":{"id":${permalink.length},"name":"$permalink","pattern_id":5}}"""
                }
            }
        }
        val vm = ProjectPageViewModel(client, this)
        vm.open(link)                       // gen 1: project resolves, secondaries gated
        vm.open(ProjectLink("yarnie", "hat")) // gen 2: supersedes
        firstGate.complete(Unit)            // gen-1 secondaries now land, stale
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<ProjectPageState.Loaded>(vm.state.value)
        // The second open won (the gen-2 link "hat"); the first open's gated secondaries
        // land against a stale generation and are dropped (exercising the gen guards).
        assertEquals("hat", loaded.project.name)
    }

    @Test
    fun `dismiss drops in-flight pattern and comment loads`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val client = suspendableRoutingApiClient { url ->
            if (!url.encodedPath.endsWith(".json") || url.encodedPath.endsWith("/comments.json") ||
                url.encodedPath.startsWith("/patterns/")
            ) {
                gate.await()
            }
            when {
                url.encodedPath.endsWith("/comments.json") -> """{"comments":[{"id":1,"comment_html":"<p>x</p>"}]}"""
                url.encodedPath.startsWith("/patterns/") ->
                    """{"pattern":{"id":5,"name":"P","permalink":"p"}}"""
                else -> """{"project":{"id":7,"name":"Autumn Socks","pattern_id":5}}"""
            }
        }
        val vm = ProjectPageViewModel(client, this)
        vm.open(link)
        // Project resolves; pattern + comments are gated in flight.
        vm.dismiss()
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPageState.Hidden>(vm.state.value)
    }

    @Test
    fun `a 403 on postComment surfaces the re-login prompt`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPageViewModel(
            routingApiClient(route = { path ->
                when {
                    path.endsWith("/comments.json") -> """{"comments":[]}"""
                    path == "/comments/create.json" ->
                        throw com.autom8ed.fibersocial.auth.ForbiddenException("no scope")
                    else -> """{"project":{"id":7,"name":"Autumn Socks"}}"""
                }
            }),
            this,
        )
        vm.open(link)
        awaitChildren(coroutineContext[Job]!!)
        vm.postComment("mine")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(
            ProjectPageViewModel.COMMENT_PERMISSION_MESSAGE,
            assertIs<CommentPostState.Error>(vm.postState.value).message,
        )
        vm.acknowledgePostError()
        assertIs<CommentPostState.Idle>(vm.postState.value)
    }

    @Test
    fun `a load that outlives its page is ignored after dismissal`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val client = suspendableRoutingApiClient { url ->
            gate.await()
            """{"project":{"id":7,"name":"Autumn Socks"}}"""
        }
        val vm = ProjectPageViewModel(client, this)
        vm.open(link)
        assertIs<ProjectPageState.Loading>(vm.state.value)
        vm.dismiss()
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        // The page must not pop back open after the user closed it.
        assertIs<ProjectPageState.Hidden>(vm.state.value)
    }
}
