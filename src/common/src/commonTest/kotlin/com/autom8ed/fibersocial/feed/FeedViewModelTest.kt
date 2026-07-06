package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    /**
     * Joins every child of [job] except [excluded]. Whether the non-excluded call's
     * coroutine is still active or has already resolved synchronously is platform-
     * dependent (observed to differ between the JVM and Robolectric test targets), so
     * this tolerates it having already completed and dropped out of [Job.children]
     * rather than assuming a fresh child is always present to find.
     */
    private suspend fun awaitChildrenExcept(job: Job, excluded: Job) =
        job.children.filter { it != excluded }.toList().forEach { it.join() }

    private val group = Group(id = 10L, name = "KAL Hub", permalink = "kal-hub", forumId = 42L)

    private fun successRepo(): FeedRepository {
        return FeedRepository(routingApiClient { path ->
            when {
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("/groups/search") -> GROUPS_JSON
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        })
    }

    /** Two groups: KAL Hub (id 10, forum 42) and Sock Society (id 11, forum 43). */
    private fun twoGroupRepo(onTopicsFetch: (Long) -> Unit = {}): FeedRepository =
        FeedRepository(routingApiClient { path ->
            when {
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") ->
                    """<html><body>
                    <a href="https://www.ravelry.com/groups/kal-hub">KAL Hub</a>
                    <a href="https://www.ravelry.com/groups/sock">Sock Society</a>
                    </body></html>"""
                path.contains("/groups/search") ->
                    """{"groups":[
                        {"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42},
                        {"id":11,"name":"Sock Society","permalink":"sock","forum_id":43}
                    ]}"""
                path.contains("/forums/") -> {
                    val forumId = path.split("/forums/")[1].split("/")[0].toLong()
                    onTopicsFetch(forumId)
                    topicsJson(if (forumId == 42L) 100L else 200L)
                }
                path.contains("/topics/") -> topicDetailJson(
                    path.split("/topics/")[1].replace(".json", "").toLong()
                )
                else -> error("Unexpected: $path")
            }
        })

    @Test
    fun `initial state is Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `reset clears a loaded feed back to Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)
        vm.reset()
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `load transitions to Loaded on success`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertEquals("yarnie", state.user.username)
        assertEquals(1, state.groups.size)
        assertEquals(1, state.items.size)
    }

    @Test
    fun `load selects the sole group as default and seeds the stored order`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeGroupOrderStore()
            val vm = FeedViewModel(successRepo(), this, store)
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(group, state.selectedGroup)
            assertEquals(listOf(10L), store.stored)
        }

    @Test
    fun `load opens the stored order's first group rather than the fetched order's`() =
        runTest(UnconfinedTestDispatcher()) {
            // Fetched order is [10, 11]; the user put Sock Society (11) first.
            val store = FakeGroupOrderStore(listOf(11L, 10L))
            val vm = FeedViewModel(twoGroupRepo(), this, store)
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(11L, state.selectedGroup?.id)
            assertEquals(listOf(11L, 10L), state.groups.map { it.id })
        }

    @Test
    fun `load appends joined groups and prunes left ones from the stored order`() =
        runTest(UnconfinedTestDispatcher()) {
            // 99 was left; 11 is newly joined and must land at the bottom.
            val store = FakeGroupOrderStore(listOf(99L, 10L))
            val vm = FeedViewModel(twoGroupRepo(), this, store)
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(10L, 11L), state.groups.map { it.id })
            assertEquals(listOf(10L, 11L), store.stored)
            assertEquals(10L, state.selectedGroup?.id)
        }

    @Test
    fun `load with no groups yields an empty selection`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = FeedRepository(routingApiClient { path ->
                when {
                    path.contains("/current_user") -> CURRENT_USER_JSON
                    path.contains("memberships") -> "<html><body></body></html>"
                    else -> error("Unexpected: $path")
                }
            })
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(null, state.selectedGroup)
            assertEquals(emptyList(), state.items)
        }

    @Test
    fun `forceError drops the feed into the Error state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        vm.forceError()
        assertIs<FeedState.Error>(vm.state.value)
    }

    @Test
    fun `load recovers from a forced Error state`() = runTest(UnconfinedTestDispatcher()) {
        // The Retry button's contract: load() must work from Error (refresh() does not).
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        vm.forceError()
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)
    }

    @Test
    fun `load transitions to Error when api fails`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(FeedRepository(errorApiClient()), this, FakeGroupOrderStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Error>(vm.state.value)
        assertEquals("Simulated network error", state.message)
    }

    @Test
    fun `refresh transitions through Refreshing to Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)

        vm.refresh()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)
    }

    @Test
    fun `refresh is a no-op when state is not Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        // State is Loading — refresh should silently do nothing
        vm.refresh()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `selectGroup filters feed to the chosen group`() = runTest(UnconfinedTestDispatcher()) {
        val group2 = Group(id = 11L, name = "Sock Society", permalink = "sock", forumId = 43L)
        var topicsForumId = 0L
        val vm = FeedViewModel(twoGroupRepo { topicsForumId = it }, this, FakeGroupOrderStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)

        vm.selectGroup(group2)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertEquals(group2, state.selectedGroup)
        assertEquals(43L, topicsForumId)
    }

    @Test
    fun `reorderGroups reorders the drawer and persists the new order`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeGroupOrderStore()
            val vm = FeedViewModel(twoGroupRepo(), this, store)
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val loaded = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(10L, 11L), loaded.groups.map { it.id })

            vm.reorderGroups(loaded.groups.reversed())
            awaitChildren(coroutineContext[Job]!!)
            val after = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(11L, 10L), after.groups.map { it.id })
            assertEquals(listOf(11L, 10L), store.stored)
            // Selection and items are untouched — reordering only affects the drawer.
            assertEquals(loaded.selectedGroup, after.selectedGroup)
            assertEquals(loaded.items, after.items)
        }

    @Test
    fun `reorderGroups ignores a list that isn't a permutation of the current groups`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeGroupOrderStore()
            val vm = FeedViewModel(twoGroupRepo(), this, store)
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val loaded = assertIs<FeedState.Loaded>(vm.state.value)
            val (first) = loaded.groups
            val seededOrder = store.stored

            // Dropping a group and duplicating another isn't a valid reorder.
            vm.reorderGroups(listOf(first, first))
            awaitChildren(coroutineContext[Job]!!)

            val after = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(loaded.groups, after.groups)
            assertEquals(seededOrder, store.stored)
        }

    @Test
    fun `reorderGroups is a no-op when state is not Loaded`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeGroupOrderStore(listOf(10L))
            val vm = FeedViewModel(successRepo(), this, store)
            vm.reorderGroups(listOf(group))
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedState.Loading>(vm.state.value)
            assertEquals(listOf(10L), store.stored)
        }

    @Test
    fun `selectGroup is a no-op when state is not Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        vm.selectGroup(group)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `load signals sessionExpired and stays Loading when session expires`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(FeedRepository(sessionExpiredApiClient()), this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedState.Loading>(vm.state.value)
            // sessionExpired is filter{it}.map{} on a StateFlow, so first() returns
            // immediately without suspension when the flag is already true
            vm.sessionExpired.first()
        }

    @Test
    fun `selectGroup signals sessionExpired and returns stale state when session expires`() =
        runTest(UnconfinedTestDispatcher()) {
            var topicCallCount = 0
            val repo = FeedRepository(routingApiClient { path ->
                when {
                    path.contains("/current_user") -> CURRENT_USER_JSON
                    path.contains("memberships") -> MEMBERSHIPS_HTML
                    path.contains("/groups/search") -> GROUPS_JSON
                    path.contains("/forums/") -> {
                        topicCallCount++
                        if (topicCallCount > 1) throw SessionExpiredException("expired")
                        topicsJson(100L)
                    }
                    path.contains("/topics/") -> topicDetailJson(100L)
                    else -> error("Unexpected: $path")
                }
            })
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedState.Loaded>(vm.state.value)

            vm.selectGroup((vm.state.value as FeedState.Loaded).groups.first())
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedState.Loaded>(vm.state.value)
            vm.sessionExpired.first()
        }

    @Test
    fun `load error falls back to default message when exception has no message`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(FeedRepository(nullMessageApiClient()), this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Error>(vm.state.value)
            assertEquals("Failed to load feed", state.message)
        }

    @Test
    fun `refresh re-fetches with the current group filter`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            vm.selectGroup(group)
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(group, (vm.state.value as FeedState.Loaded).selectedGroup)

            vm.refresh()
            awaitChildren(coroutineContext[Job]!!)
            val after = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(group, after.selectedGroup)
        }

    /**
     * Like [successRepo] but also answers the join flow: the homepage GET that scrapes the
     * CSRF token, and the group-join POST. Lets a join succeed and reload.
     */
    private fun joinRepo(): FeedRepository =
        FeedRepository(routingApiClient { path ->
            when {
                path.endsWith("/join") -> "ok"
                path.isEmpty() || path == "/" -> TOKEN_PAGE_HTML
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("/groups/search") -> GROUPS_JSON
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        })

    @Test
    fun `joinSupportGroup joins then reloads ending Idle and Loaded`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(joinRepo(), this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedState.Loaded>(vm.state.value)

            vm.joinSupportGroup("fibersocial-app-support")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<JoinState.Idle>(vm.joinState.value)
            assertIs<FeedState.Loaded>(vm.state.value)
        }

    @Test
    fun `joinSupportGroup surfaces a failure as JoinState Error`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(FeedRepository(errorApiClient()), this, FakeGroupOrderStore())
            vm.joinSupportGroup("fibersocial-app-support")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<JoinState.Error>(vm.joinState.value)
            // A reopened drawer clears the stale error.
            vm.acknowledgeJoinError()
            assertIs<JoinState.Idle>(vm.joinState.value)
        }

    @Test
    fun `joinSupportGroup routes a session expiry to login`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(FeedRepository(sessionExpiredApiClient()), this, FakeGroupOrderStore())
            vm.joinSupportGroup("fibersocial-app-support")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<JoinState.Idle>(vm.joinState.value)
            assertEquals(Unit, vm.sessionExpired.first())
        }

    @Test
    fun `joinSupportGroup ignores a double-tap while already joining`() =
        runTest(UnconfinedTestDispatcher()) {
            // Gated so the "still joining" window is deterministic rather than dependent
            // on whether the underlying fetch happens to suspend for real — platform-
            // dependent (see the loadMore concurrency tests) and would make this flaky.
            val gate = CompletableDeferred<Unit>()
            var tokenFetchCount = 0
            val repo = FeedRepository(
                suspendableRoutingApiClient { url ->
                    val path = url.encodedPath
                    when {
                        path.isEmpty() || path == "/" -> {
                            tokenFetchCount++
                            gate.await()
                            TOKEN_PAGE_HTML
                        }
                        path.endsWith("/join") -> "ok"
                        path.contains("/current_user") -> CURRENT_USER_JSON
                        path.contains("memberships") -> MEMBERSHIPS_HTML
                        path.contains("/groups/search") -> GROUPS_JSON
                        path.contains("/forums/") -> topicsJson(100L)
                        path.contains("/topics/") -> topicDetailJson(100L)
                        else -> error("Unexpected: $path")
                    }
                },
            )
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)

            vm.joinSupportGroup("fibersocial-app-support")
            assertIs<JoinState.Joining>(vm.joinState.value)
            // Still parked on the token fetch — a second call must no-op, not double-join.
            vm.joinSupportGroup("fibersocial-app-support")

            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(1, tokenFetchCount)
            assertIs<JoinState.Idle>(vm.joinState.value)
        }

    @Test
    fun `joinSupportGroup resolves the selected group from stale state while Refreshing`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            var membershipFetchCount = 0
            val repo = FeedRepository(
                suspendableRoutingApiClient { url ->
                    val path = url.encodedPath
                    when {
                        path.endsWith("/join") -> "ok"
                        path.isEmpty() || path == "/" -> TOKEN_PAGE_HTML
                        path.contains("/current_user") -> CURRENT_USER_JSON
                        path.contains("memberships") -> {
                            membershipFetchCount++
                            // The first fetch (from refresh()) parks here so the test can
                            // observe FeedState.Refreshing; later fetches (from load()'s
                            // completion and joinSupportGroup's own reload) go straight through.
                            if (membershipFetchCount == 2) gate.await()
                            MEMBERSHIPS_HTML
                        }
                        path.contains("/groups/search") -> GROUPS_JSON
                        path.contains("/forums/") -> topicsJson(100L)
                        path.contains("/topics/") -> topicDetailJson(100L)
                        else -> error("Unexpected: $path")
                    }
                },
            )
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val loaded = assertIs<FeedState.Loaded>(vm.state.value)

            // Starts refresh()'s own fetchFeed, which parks on the 2nd memberships fetch —
            // state should be Refreshing(stale = loaded) by the time joinSupportGroup reads
            // it below (not asserted directly here: observing it needs a second gate step,
            // and the point of this test is the *resolution*, not the intermediate state).
            vm.refresh()

            // joinSupportGroup must resolve the group to reselect from stale.selectedGroup,
            // not a direct FeedState.Loaded read (state isn't Loaded right now).
            vm.joinSupportGroup("fibersocial-app-support")
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            val after = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(loaded.selectedGroup, after.selectedGroup)
        }

    /** Two-page repo for the single group above: page 1 has topic 100, page 2 has topic 200. */
    private fun twoPageRepo(): FeedRepository {
        var forumCallCount = 0
        return FeedRepository(routingApiClient { path ->
            when {
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("/groups/search") -> GROUPS_JSON
                path.contains("/forums/") -> {
                    forumCallCount++
                    if (forumCallCount == 1) {
                        """{"topics":[{"id":100,"title":"Topic 100"}],
                            "paginator":{"page":1,"page_count":2,"results":2}}"""
                    } else {
                        """{"topics":[{"id":200,"title":"Topic 200"}],
                            "paginator":{"page":2,"page_count":2,"results":2}}"""
                    }
                }
                path.contains("/topics/") ->
                    topicDetailJson(path.split("/topics/")[1].replace(".json", "").toLong())
                else -> error("Unexpected: $path")
            }
        })
    }

    @Test
    fun `load reports hasMore true when the first page is not the last`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(twoPageRepo(), this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(100L), state.items.map { it.id })
            assertTrue(state.hasMore)
        }

    @Test
    fun `loadMore appends the next page and clears hasMore once exhausted`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(twoPageRepo(), this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)

            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)
            val after = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(100L, 200L), after.items.map { it.id })
            assertFalse(after.hasMore)
            assertFalse(after.loadingMore)
        }

    @Test
    fun `loadMore is a no-op when hasMore is false`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val before = assertIs<FeedState.Loaded>(vm.state.value)
        assertFalse(before.hasMore)

        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(before, vm.state.value)
    }

    @Test
    fun `loadMore is a no-op when state is not Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(twoPageRepo(), this, FakeGroupOrderStore())
        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `loadMore sets loadingMore synchronously so a second call is ignored while in flight`() =
        runTest(UnconfinedTestDispatcher()) {
            // Gated so the "still in flight" window is deterministic rather than
            // dependent on whether the underlying fetch happens to suspend for real —
            // that's platform-dependent (observed to differ between the JVM and
            // Robolectric test targets) and made this assertion flaky.
            val gate = CompletableDeferred<Unit>()
            val vm = FeedViewModel(gatedTwoPageRepo(gate), this, FakeGroupOrderStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)

            vm.loadMore()
            val midFlight = assertIs<FeedState.Loaded>(vm.state.value)
            assertTrue(midFlight.loadingMore)

            // A second call while the first is still in flight must no-op, not double-fetch.
            vm.loadMore()
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)
            val after = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(100L, 200L), after.items.map { it.id })
        }

    /**
     * Two-page repo for the single group above whose page-2 request suspends on [gate]
     * until the test completes it — lets a test hold a `loadMore()` fetch in flight while
     * driving other ViewModel calls to completion.
     */
    private fun gatedTwoPageRepo(gate: CompletableDeferred<Unit>): FeedRepository =
        FeedRepository(
            suspendableRoutingApiClient { url ->
                val path = url.encodedPath
                when {
                    path.contains("/current_user") -> CURRENT_USER_JSON
                    path.contains("memberships") -> MEMBERSHIPS_HTML
                    path.contains("/groups/search") -> GROUPS_JSON
                    path.contains("/forums/") ->
                        if (url.parameters["page"] == "2") {
                            gate.await()
                            """{"topics":[{"id":200,"title":"Topic 200"}],
                                "paginator":{"page":2,"page_count":2,"results":2}}"""
                        } else {
                            """{"topics":[{"id":100,"title":"Topic 100"}],
                                "paginator":{"page":1,"page_count":2,"results":2}}"""
                        }
                    path.contains("/topics/") ->
                        topicDetailJson(path.split("/topics/")[1].replace(".json", "").toLong())
                    else -> error("Unexpected: $path")
                }
            },
        )

    @Test
    fun `loadMore discards its stale page if a refresh completes first`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val vm = FeedViewModel(gatedTwoPageRepo(gate), this, FakeGroupOrderStore())
            val parentJob = coroutineContext[Job]!!
            vm.load()
            awaitChildren(parentJob)
            assertEquals(listOf(100L), (vm.state.value as FeedState.Loaded).items.map { it.id })

            // Starts the page-2 fetch, which parks on `gate` — loadingMore stays true
            // until the test lets it through below. Capture its Job by reference so later
            // steps can await specific coroutines without joining this still-parked one.
            vm.loadMore()
            assertTrue((vm.state.value as FeedState.Loaded).loadingMore)
            val loadMoreJob = parentJob.children.single()

            // A refresh for the same group completes in full while the page-2 fetch is
            // still in flight, replacing state with a brand-new page-1 Loaded instance.
            vm.refresh()
            awaitChildrenExcept(parentJob, loadMoreJob)
            val refreshed = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(100L), refreshed.items.map { it.id })
            assertFalse(refreshed.loadingMore)

            // Now let the stale page-2 fetch resolve. Before the fix, its guard only
            // compared group id (unchanged) and would splice topic 200 onto the
            // already-refreshed items. It must be discarded instead.
            gate.complete(Unit)
            loadMoreJob.join()
            val after = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(100L), after.items.map { it.id })
            assertEquals(refreshed, after)
        }

    @Test
    fun `loadMore failure does not clobber state after the user switched groups`() =
        runTest(UnconfinedTestDispatcher()) {
            val group2 = Group(id = 11L, name = "Sock Society", permalink = "sock", forumId = 43L)
            val gate = CompletableDeferred<Unit>()
            val repo = FeedRepository(
                suspendableRoutingApiClient { url ->
                    val path = url.encodedPath
                    when {
                        path.contains("/current_user") -> CURRENT_USER_JSON
                        path.contains("memberships") ->
                            """<html><body>
                            <a href="https://www.ravelry.com/groups/kal-hub">KAL Hub</a>
                            <a href="https://www.ravelry.com/groups/sock">Sock Society</a>
                            </body></html>"""
                        path.contains("/groups/search") ->
                            """{"groups":[
                                {"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42},
                                {"id":11,"name":"Sock Society","permalink":"sock","forum_id":43}
                            ]}"""
                        path.contains("/forums/42/") -> {
                            // group 1 (KAL Hub)'s first page reports a further page so
                            // loadMore() is reachable, then its page-2 fetch parks on
                            // `gate` and fails once released.
                            if (url.parameters["page"] == "2") {
                                gate.await()
                                error("Simulated failure for a superseded loadMore fetch")
                            } else {
                                """{"topics":[{"id":100,"title":"Topic 100"}],
                                    "paginator":{"page":1,"page_count":2,"results":2}}"""
                            }
                        }
                        path.contains("/forums/43/") -> topicsJson(200L)
                        path.contains("/topics/") ->
                            topicDetailJson(path.split("/topics/")[1].replace(".json", "").toLong())
                        else -> error("Unexpected: $path")
                    }
                },
            )
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore())
            val parentJob = coroutineContext[Job]!!
            vm.load()
            awaitChildren(parentJob)
            assertEquals(group, (vm.state.value as FeedState.Loaded).selectedGroup)

            // Starts group 1's page-2 fetch, which parks on `gate`. Capture its Job by
            // reference so later steps can await specific coroutines without joining
            // this still-parked one.
            vm.loadMore()
            assertTrue((vm.state.value as FeedState.Loaded).loadingMore)
            val loadMoreJob = parentJob.children.single()

            // The user switches to group 2 before the stuck fetch resolves.
            vm.selectGroup(group2)
            awaitChildrenExcept(parentJob, loadMoreJob)
            val switched = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(group2, switched.selectedGroup)

            // Now the superseded fetch fails. Before the fix, the catch block
            // unconditionally reverted `_state.value` to the pre-fetch group-1 snapshot,
            // even though the user has since moved on to group 2.
            gate.complete(Unit)
            loadMoreJob.join()
            val after = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(group2, after.selectedGroup)
            assertEquals(switched, after)
        }
}
