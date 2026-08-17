package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.Group
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.first
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupSearchViewModelTest {

    // Drains repeatedly rather than joining a snapshot: a search launches, and a join
    // launches onGroupsChanged as a further child after its own body runs, so one pass
    // would miss the tail. Same helper shape as ProjectPageViewModelTest.
    private suspend fun awaitChildren(job: Job) {
        do {
            job.children.toList().forEach { it.join() }
        } while (job.children.any { it.isActive })
    }

    private fun groupJson(id: Long, name: String, permalink: String) =
        """{"id":$id,"name":"$name","permalink":"$permalink","forum_id":${id + 1000}}"""

    private fun searchJson(vararg groups: String, page: Int = 1, pageCount: Int = 1) =
        """{"groups":[${groups.joinToString(",")}],"paginator":{"page":$page,"page_count":$pageCount}}"""

    private fun viewModel(
        scope: kotlinx.coroutines.CoroutineScope,
        onGroupsChanged: suspend () -> Unit = {},
        route: (path: String) -> String,
    ) = GroupSearchViewModel(
        FeedRepository(routingApiClient(route = route)),
        scope,
        onGroupsChanged = onGroupsChanged,
    )

    /**
     * A client whose search returns [searchBody] and whose join actually works: joinGroup
     * replays the website's form, so it first GETs a page to scrape the CSRF token out of
     * and only then POSTs. A plain JSON stub leaves it with no token and the join fails
     * for the wrong reason.
     */
    private fun joinableViewModel(
        scope: kotlinx.coroutines.CoroutineScope,
        searchBody: String,
        onGroupsChanged: suspend () -> Unit = {},
        onJoinPost: () -> Unit = {},
        failJoin: Boolean = false,
        joinGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null,
    ): GroupSearchViewModel {
        val engine = MockEngine { request ->
            when {
                request.url.encodedPath.endsWith("/search.json") ->
                    respond(
                        searchBody,
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                request.method.value == "POST" -> {
                    // Parks the join mid-flight when a gate is supplied, so a test about
                    // in-flight behaviour doesn't depend on whether the whole request
                    // happens to resolve without suspending.
                    joinGate?.await()
                    onJoinPost()
                    if (failJoin) {
                        respond("no", HttpStatusCode.InternalServerError, headersOf("Content-Type", "text/html"))
                    } else {
                        respond("ok", HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                    }
                }
                else -> respond(TOKEN_PAGE_HTML, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return GroupSearchViewModel(
            FeedRepository(RavelryApiClient(httpClient, FakeFeedTokenStorage())),
            scope,
            onGroupsChanged = onGroupsChanged,
        )
    }

    @Test
    fun `start browses the directory before anything is typed`() = runTest(UnconfinedTestDispatcher()) {
        // Issue #232 asked for something browsable, not a blank box demanding a query.
        var seenPath: String? = null
        val vm = viewModel(this) { path ->
            seenPath = path
            searchJson(groupJson(1, "Lace Knitters", "lace-knitters"))
        }
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        val state = vm.state.value as GroupSearchState.Loaded
        assertEquals(1, state.groups.size)
        assertEquals("", state.query)
        assertEquals("/groups/search.json", seenPath)
    }

    @Test
    fun `typing searches once the debounce elapses`() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val vm = viewModel(this) {
            calls++
            searchJson(groupJson(2, "Sock Knitters", "sock-knitters"))
        }
        vm.onQueryChanged("sock")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, calls)
        val state = vm.state.value as GroupSearchState.Loaded
        assertEquals("sock", state.query)
        assertEquals("Sock Knitters", state.groups.single().name)
    }

    @Test
    fun `a burst of keystrokes costs one request for the final query`() = runTest(UnconfinedTestDispatcher()) {
        // Without the debounce this is one request per letter, and the responses can land
        // out of order — the whole reason onQueryChanged cancels the pending search.
        var calls = 0
        val vm = viewModel(this) {
            calls++
            searchJson(groupJson(3, "Socks", "socks"))
        }
        vm.onQueryChanged("s")
        vm.onQueryChanged("so")
        vm.onQueryChanged("soc")
        vm.onQueryChanged("sock")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, calls)
        assertEquals("sock", (vm.state.value as GroupSearchState.Loaded).query)
    }

    @Test
    fun `loadMore appends the next page and tracks hasMore`() = runTest(UnconfinedTestDispatcher()) {
        val vm = viewModel(this) {
            searchJson(groupJson(4, "Page One", "page-one"), page = 1, pageCount = 2)
        }
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        assertTrue((vm.state.value as GroupSearchState.Loaded).hasMore)

        val paged = GroupSearchViewModel(
            FeedRepository(
                suspendableRoutingApiClient { url ->
                    val page = url.parameters["page"]?.toInt() ?: 1
                    searchJson(
                        groupJson(page.toLong(), "Group $page", "group-$page"),
                        page = page,
                        pageCount = 2,
                    )
                },
            ),
            this,
        )
        paged.start()
        awaitChildren(coroutineContext[Job]!!)
        paged.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        val state = paged.state.value as GroupSearchState.Loaded
        assertEquals(listOf("Group 1", "Group 2"), state.groups.map { it.name })
        assertEquals(2, state.page)
        assertTrue(!state.hasMore)
    }

    @Test
    fun `loadMore is ignored when there is no further page`() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val vm = viewModel(this) {
            calls++
            searchJson(groupJson(5, "Only Page", "only-page"), page = 1, pageCount = 1)
        }
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, calls)
    }

    @Test
    fun `a failed search surfaces a retryable error`() = runTest(UnconfinedTestDispatcher()) {
        var fail = true
        val vm = viewModel(this) {
            if (fail) throw RuntimeException("boom") else searchJson(groupJson(6, "Later", "later"))
        }
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        assertTrue(vm.state.value is GroupSearchState.Error)

        fail = false
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("Later", (vm.state.value as GroupSearchState.Loaded).groups.single().name)
    }

    @Test
    fun `joining marks the group joined and tells the host membership changed`() = runTest(UnconfinedTestDispatcher()) {
        var refreshed = 0
        val vm = joinableViewModel(
            this,
            searchBody = searchJson(groupJson(7, "Join Me", "join-me")),
            onGroupsChanged = { refreshed++ },
        )
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        val group = (vm.state.value as GroupSearchState.Loaded).groups.single()

        vm.join(group)
        awaitChildren(coroutineContext[Job]!!)
        assertTrue("join-me" in vm.joinedPermalinks.value)
        assertNull(vm.joiningPermalink.value)
        assertEquals(1, refreshed)
        assertNull(vm.joinError.value)
    }

    @Test
    fun `a failed join surfaces a dismissable error and does not mark it joined`() = runTest(UnconfinedTestDispatcher()) {
        // Issue #263's lesson on the leave path: a silent failure reads as success, and
        // the user is left believing they joined something they did not.
        val vm = joinableViewModel(
            this,
            searchBody = searchJson(groupJson(8, "Nope Group", "nope-group")),
            failJoin = true,
        )
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        vm.join((vm.state.value as GroupSearchState.Loaded).groups.single())
        awaitChildren(coroutineContext[Job]!!)

        assertTrue("nope-group" !in vm.joinedPermalinks.value)
        assertTrue(vm.joinError.value!!.contains("Nope Group"))
        assertNull(vm.joiningPermalink.value)

        vm.dismissJoinError()
        assertNull(vm.joinError.value)
    }

    @Test
    fun `a second join of the same group is ignored`() = runTest(UnconfinedTestDispatcher()) {
        var joins = 0
        val vm = joinableViewModel(
            this,
            searchBody = searchJson(groupJson(9, "Once", "once")),
            onJoinPost = { joins++ },
        )
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        val group = (vm.state.value as GroupSearchState.Loaded).groups.single()
        vm.join(group)
        awaitChildren(coroutineContext[Job]!!)
        vm.join(group)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, joins)
    }

    @Test
    fun `an empty result set is still a Loaded state rather than an error`() = runTest(UnconfinedTestDispatcher()) {
        // The screen distinguishes them: "no groups match" versus "something went wrong".
        val vm = viewModel(this) { """{"groups":[]}""" }
        vm.onQueryChanged("zzzzz")
        awaitChildren(coroutineContext[Job]!!)
        val state = vm.state.value as GroupSearchState.Loaded
        assertEquals(emptyList<Group>(), state.groups)
        assertEquals("zzzzz", state.query)
    }

    @Test
    fun `start is a no-op once a search has already run`() = runTest(UnconfinedTestDispatcher()) {
        // The screen calls start() on every entry; re-fetching what is already on screen
        // each time the user backs out of a group and returns would be pure waste.
        var calls = 0
        val vm = viewModel(this) {
            calls++
            searchJson(groupJson(10, "Once Only", "once-only"))
        }
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, calls)
    }

    @Test
    fun `a page that lands after the query changed is discarded`() = runTest(UnconfinedTestDispatcher()) {
        // Otherwise page 2 of "sock" appends onto the results for "lace" — the reason
        // loadMore re-reads state instead of closing over the page it started from.
        val gate = CompletableDeferred<Unit>()
        val vm = GroupSearchViewModel(
            FeedRepository(
                suspendableRoutingApiClient { url ->
                    if (url.parameters["page"] == "2") gate.await()
                    searchJson(
                        groupJson(11, "Result", "result-${url.parameters["page"]}"),
                        page = url.parameters["page"]?.toInt() ?: 1,
                        pageCount = 2,
                    )
                },
            ),
            this,
        )
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        vm.loadMore()
        // Move to a different query while page 2 is still in flight.
        vm.onQueryChanged("lace")
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        val state = vm.state.value as GroupSearchState.Loaded
        assertEquals("lace", state.query)
        assertEquals(1, state.page)
    }

    @Test
    fun `a failed loadMore clears the spinner and keeps the results`() =
        runTest(UnconfinedTestDispatcher()) {
            var calls = 0
            val vm = viewModel(this) {
                calls++
                if (calls > 1) throw RuntimeException("page 2 boom")
                searchJson(groupJson(12, "First", "first"), page = 1, pageCount = 2)
            }
            vm.start()
            awaitChildren(coroutineContext[Job]!!)
            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)
            val state = vm.state.value as GroupSearchState.Loaded
            assertTrue(!state.loadingMore)
            assertEquals(1, state.groups.size)
        }

    @Test
    fun `a join is ignored while another is already in flight`() = runTest(UnconfinedTestDispatcher()) {
        // The first join is PARKED rather than merely started. Without the gate this test
        // depended on the first join not finishing inside its own `vm.join(...)` call:
        // under UnconfinedTestDispatcher the launched body runs eagerly and inline, so if
        // nothing in the request actually suspends, `finally { _joiningPermalink = null }`
        // has already run by the time the second tap arrives, the guard sees null, and the
        // second join proceeds — joins == 2. That is scheduling, not behaviour, and it is
        // what failed on CI while passing locally 8/8.
        var joins = 0
        val gate = kotlinx.coroutines.CompletableDeferred<Unit>()
        val vm = joinableViewModel(
            this,
            searchBody = searchJson(
                groupJson(13, "One", "one"),
                groupJson(14, "Two", "two"),
            ),
            onJoinPost = { joins++ },
            joinGate = gate,
        )
        vm.start()
        awaitChildren(coroutineContext[Job]!!)
        val groups = (vm.state.value as GroupSearchState.Loaded).groups

        vm.join(groups[0])
        // The first join is provably still in flight, so the second tap's guard is being
        // exercised rather than raced.
        assertEquals(groups[0].permalink, vm.joiningPermalink.value)
        vm.join(groups[1])

        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        // The second tap was dropped — joining two groups from one gesture is never what
        // the user meant.
        assertEquals(1, joins)
        assertEquals(setOf(groups[0].permalink), vm.joinedPermalinks.value)
    }

    /**
     * A superseded keystroke must not paint the error screen (the debounce's own guard
     * turning on the user).
     *
     * `onQueryChanged` cancels the in-flight search on purpose — that is what stops an
     * older response landing after a newer one. But `CancellationException` IS an
     * `Exception`, so before the rethrow the generic catch treated every cancelled search
     * as a failed request and set
     * `Error("Couldn't search groups. Check your connection and try again.")` over results
     * the user was still looking at. Verified against the unfixed version, which reported
     * exactly that state here.
     *
     * Reachable whenever a request outlives the next keystroke — i.e. pausing longer than
     * the debounce, then resuming, on a slow connection. The flash is transient (the new
     * search overwrites it) but it is a full-screen error, and it also logged a spurious
     * network-failure line.
     *
     * Same rethrow-before-generic-catch shape as `FeedViewModel` and `FeedRepository`.
     */
    @Test
    fun `a superseded keystroke does not surface as a search failure`() =
        runTest(UnconfinedTestDispatcher()) {
            val release = kotlinx.coroutines.CompletableDeferred<Unit>()
            val vm = GroupSearchViewModel(
                FeedRepository(
                    suspendableRoutingApiClient { _ ->
                        release.await()
                        searchJson(groupJson(1, "Socks", "socks"))
                    },
                ),
                this,
            )
            vm.start()
            // The first request is parked in flight; this keystroke cancels it.
            vm.onQueryChanged("so")

            assertTrue(
                vm.state.value !is GroupSearchState.Error,
                "a cancelled search surfaced as an error: ${vm.state.value}",
            )

            release.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)
            // And the replacement search still lands, so the cancel didn't strand the screen.
            val loaded = vm.state.value as? GroupSearchState.Loaded
            assertTrue(loaded != null, "expected results after the cancel, got ${vm.state.value}")
            assertEquals(listOf("socks"), loaded.groups.map { it.permalink })
        }

    /**
     * A session expiry must reach the host, which routes to the login screen.
     *
     * This ViewModel's `sessionExpired` was missing from BOTH platforms' `merge(...)`
     * lists — the only one of the ~14 that was — so the signal fired into a flow nobody
     * collected. `search` also leaves the state on `Loading` in that branch (deliberately,
     * since the host tears the screen down), which without the wiring meant a spinner that
     * never resolved and no way back. This pins the ViewModel half; the platform half is
     * the one-line addition next to `feed.sessionExpired` in FeedAndroidViewModel/IosModels.
     */
    @Test
    fun `a session expiry while searching signals the host`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = GroupSearchViewModel(
                FeedRepository(sessionExpiredApiClient(FakeFeedTokenStorage())),
                this,
            )
            vm.start()
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(Unit, vm.sessionExpired.first())
        }
}
