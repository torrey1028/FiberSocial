package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.feed.models.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    /**
     * Drains [job]'s children until none are left, rather than joining a single snapshot.
     *
     * WHY THE LOOP — this was a real flake source, not defensiveness. The ViewModel's
     * drawer-unread work spawns FOLLOW-ON children after its first ones resolve:
     * `markGroupViewed` and the drawer-unread refresh both persist via `scope.launch`
     * once their fetch completes. A snapshot-once join takes `job.children`, joins those,
     * and returns — while a persist launched during that join is still in flight, because
     * it was never in the snapshot. The test then asserts against half-applied state.
     *
     * That is exactly the shape of the failures seen on CI: `the group shown on load is
     * not dotted for activity the user is looking at` failed intermittently on THREE
     * separate PRs, two of which touched no code this test exercises. It reproduces
     * essentially never on a fast idle machine and shows up under CI load, which is what
     * you would expect from a race between a join and a launch.
     *
     * Looping until `children` is empty drains transitively-spawned work too. It cannot
     * hang on well-behaved code: every launch here is bounded, and a genuinely
     * never-completing child would hang the snapshot version on `join()` anyway.
     */
    private suspend fun awaitChildren(job: Job) {
        while (true) {
            val children = job.children.toList()
            if (children.isEmpty()) return
            children.forEach { it.join() }
        }
    }

    /**
     * Drains every child of [job] except [excluded], for tests that deliberately leave one
     * call parked in flight. Same drain-until-empty reasoning as [awaitChildren].
     *
     * Whether the non-excluded call's coroutine is still active or has already resolved
     * synchronously is platform-dependent (observed to differ between the JVM and
     * Robolectric test targets), so this tolerates it having already completed and dropped
     * out of [Job.children] rather than assuming a fresh child is always present to find.
     */
    private suspend fun awaitChildrenExcept(job: Job, excluded: Job) {
        while (true) {
            val children = job.children.filter { it != excluded }.toList()
            if (children.isEmpty()) return
            children.forEach { it.join() }
        }
    }

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
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `reset clears a loaded feed back to Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)
        vm.reset()
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `load transitions to Loaded on success`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertEquals("yarnie", state.user.username)
        assertEquals(1, state.groups.size)
        assertEquals(1, state.items.size)
    }

    @Test
    fun `refreshDrawerUnread populates the Your Posts dot before the feed has loaded`() =
        runTest(UnconfinedTestDispatcher()) {
            // The per-group leg needs the user's groups, which don't exist yet — but the
            // "Your Posts" dot is group-independent, so it still resolves. The fake serves
            // a posted-in topic with unread replies (5 posts, read to 3).
            val repo = FeedRepository(routingApiClient { path ->
                when {
                    path.contains("filtered_topics") ->
                        """{"topics":[{"id":1,"title":"T","forum_id":42,"forum_posts_count":5,"last_read":3}]}"""
                    else -> error("Unexpected: $path")
                }
            })
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())

            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.yourPostsHasUnread)
            assertTrue(vm.drawerUnread.value.unreadGroupForumIds.isEmpty())
        }

    @Test
    fun `refreshDrawerUnread keeps the previous value when the fetch fails`() =
        runTest(UnconfinedTestDispatcher()) {
            val repo = FeedRepository(routingApiClient { error("boom") })
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())

            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            // A failed fetch is swallowed — the dots stay at their (empty) default rather
            // than propagating the error into the feed.
            assertEquals(DrawerUnread(), vm.drawerUnread.value)
        }

    @Test
    fun `refreshDrawerUnread signals sessionExpired instead of swallowing it`() =
        runTest(UnconfinedTestDispatcher()) {
            // This can be the only in-flight request (e.g. a drawer pull-to-refresh while
            // otherwise idle on an already-loaded feed) — nothing else is guaranteed to
            // notice the expired session if this swallows it too.
            val vm = FeedViewModel(
                FeedRepository(sessionExpiredApiClient()),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )

            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            vm.sessionExpired.first()
        }

    @Test
    fun `refreshDrawerUnread cancels a still in-flight call rather than letting it clobber a newer result`() =
        runTest(UnconfinedTestDispatcher()) {
            // Ktor engines — including MockEngine — dispatch request handling via
            // withContext(Dispatchers.IO) regardless of the calling coroutine's own
            // dispatcher (see FakeFeedTokenStorage's KDoc), so requests from the two
            // refreshDrawerUnread() calls below genuinely run on real threads; the Mutex
            // keeps the shared flag's read-then-clear atomic across them (a plain var
            // here previously caused a Robolectric-only CI flake).
            //
            // NOTE vs the version of this fix that landed on main (#361): that one waits
            // for TWO of call 1's requests to park, because there getDrawerUnread() fires
            // two legs. On this branch the per-group leg is conditional on a loaded feed,
            // so an un-loaded VM fires exactly ONE request per call — waiting for a second
            // park would hang. This deliberately supersedes #361's version on merge.
            val holdMutex = Mutex()
            var holdNextRequest = true
            val firstRequestArrived = CompletableDeferred<Unit>()
            val firstCallGate = CompletableDeferred<Unit>()
            val repo = FeedRepository(
                suspendableRoutingApiClient { _ ->
                    val hold = holdMutex.withLock {
                        holdNextRequest.also { holdNextRequest = false }
                    }
                    if (hold) {
                        firstRequestArrived.complete(Unit)
                        // Never released: the first call is cancelled before this could
                        // ever resolve, so nothing needs to un-gate it.
                        firstCallGate.await()
                        error("unreachable — cancelled before the gate could open")
                    } else {
                        """{"topics":[{"id":2,"title":"T2","forum_id":42,"forum_posts_count":5,"last_read":3}]}"""
                    }
                },
            )
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())

            // With no feed loaded there are no groups to fan out over, so getDrawerUnread()
            // fires exactly 1 request (the "Your Posts" leg) per call. The first call's
            // request blocks on firstCallGate forever; the second call cancels it (the fix
            // under test) before starting its own, which resolves immediately. Without the
            // fix, the first call's request would still be in flight (not cancelled) when
            // the test ends, and this would hang / fail as an uncompleted coroutine instead
            // of asserting wrong data — which is itself evidence the cancellation is what
            // makes this test (and the real bug scenario) terminate cleanly at all.
            vm.refreshDrawerUnread()
            // Wait for the FIRST call's request to actually reach the route before firing
            // the second. Without this the two race: if the cancellation lands before the
            // first request gets there, the second call's request is the one the gate
            // catches and the test hangs on a held request nobody will ever cancel.
            firstRequestArrived.await()
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.yourPostsHasUnread)
        }

    @Test
    fun `load selects the sole group as default and seeds the stored order`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeGroupOrderStore()
            val vm = FeedViewModel(successRepo(), this, store, FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(twoGroupRepo(), this, store, FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(twoGroupRepo(), this, store, FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(null, state.selectedGroup)
            assertEquals(emptyList(), state.items)
        }

    @Test
    fun `forceError drops the feed into the Error state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.forceError()
        assertIs<FeedState.Error>(vm.state.value)
    }

    @Test
    fun `load recovers from a forced Error state`() = runTest(UnconfinedTestDispatcher()) {
        // The Retry button's contract: load() must work from Error (refresh() does not).
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.forceError()
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)
    }

    @Test
    fun `load transitions to Error when api fails`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(
            FeedRepository(errorApiClient()),
            this,
            FakeGroupOrderStore(),
            FakeGroupLastViewedStore(),
        )
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Error>(vm.state.value)
        assertEquals("Simulated network error", state.message)
    }

    @Test
    fun `refresh transitions through Refreshing to Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)

        vm.refresh()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)
    }

    @Test
    fun `refresh is a no-op when state is not Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        // State is Loading — refresh should silently do nothing
        vm.refresh()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `selectGroup filters feed to the chosen group`() = runTest(UnconfinedTestDispatcher()) {
        val group2 = Group(id = 11L, name = "Sock Society", permalink = "sock", forumId = 43L)
        var topicsForumId = 0L
        val vm = FeedViewModel(
            twoGroupRepo { topicsForumId = it },
            this,
            FakeGroupOrderStore(),
            FakeGroupLastViewedStore(),
        )
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
    fun `selectGroup switches to the new group immediately with blank loading content`() =
        runTest(UnconfinedTestDispatcher()) {
            // Issue #214: selecting a group snaps the chrome to it and clears the content
            // to empty while the new group loads — not the old group's topics under a
            // spinner. The parked forum-43 fetch lets us observe that intermediate state.
            val gate = CompletableDeferred<Unit>()
            val repo = FeedRepository(
                suspendableRoutingApiClient { url ->
                    val path = url.encodedPath
                    when {
                        path.contains("/current_user") -> CURRENT_USER_JSON
                        path.contains("memberships") -> """<html><body>
                            <a href="https://www.ravelry.com/groups/kal-hub">KAL Hub</a>
                            <a href="https://www.ravelry.com/groups/sock">Sock Society</a>
                            </body></html>"""
                        path.contains("/groups/search") -> """{"groups":[
                            {"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42},
                            {"id":11,"name":"Sock Society","permalink":"sock","forum_id":43}
                        ]}"""
                        path.contains("/forums/43/") -> { gate.await(); topicsJson(200L) }
                        path.contains("/forums/") -> topicsJson(100L)
                        path.contains("/topics/") -> topicDetailJson(
                            path.split("/topics/")[1].replace(".json", "").toLong(),
                        )
                        else -> error("Unexpected: $path")
                    }
                },
            )
            val group2 = Group(id = 11L, name = "Sock Society", permalink = "sock", forumId = 43L)
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedState.Loaded>(vm.state.value)

            vm.selectGroup(group2)
            // The forum-43 fetch is parked: the feed is already Refreshing on the new group
            // with no items (blank content), before any of group 2's topics have arrived.
            val switching = assertIs<FeedState.Refreshing>(vm.state.value)
            assertEquals(group2, switching.stale.selectedGroup)
            assertEquals(emptyList(), switching.stale.items)

            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)
            val loaded = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(group2, loaded.selectedGroup)
            assertEquals(1, loaded.items.size)
        }

    @Test
    fun `reorderGroups reorders the drawer and persists the new order`() =
        runTest(UnconfinedTestDispatcher()) {
            val store = FakeGroupOrderStore()
            val vm = FeedViewModel(twoGroupRepo(), this, store, FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(twoGroupRepo(), this, store, FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(successRepo(), this, store, FakeGroupLastViewedStore())
            vm.reorderGroups(listOf(group))
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedState.Loading>(vm.state.value)
            assertEquals(listOf(10L), store.stored)
        }

    @Test
    fun `selectGroup is a no-op when state is not Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.selectGroup(group)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `load signals sessionExpired and stays Loading when session expires`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(
                FeedRepository(sessionExpiredApiClient()),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
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
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(
                FeedRepository(nullMessageApiClient()),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Error>(vm.state.value)
            assertEquals("Failed to load feed", state.message)
        }

    @Test
    fun `refresh re-fetches with the current group filter`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(joinRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(
                FeedRepository(errorApiClient()),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
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
            val vm = FeedViewModel(
                FeedRepository(sessionExpiredApiClient()),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
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
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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

    /** Two-group memberships-then-leave repo: after a /leave POST, [remaining] permalinks stay. */
    private fun leaveRepo(remainingAfterLeave: List<String>, onLeave: () -> Unit = {}): FeedRepository {
        var left = false
        fun membershipsHtml(permalinks: List<String>) = "<html><body>" +
            permalinks.joinToString("") { "<a href=\"https://www.ravelry.com/groups/$it\">$it</a>" } +
            "</body></html>"
        return FeedRepository(routingApiClient { path ->
            when {
                path.endsWith("/leave") -> { left = true; onLeave(); "ok" }
                path.isEmpty() || path == "/" -> TOKEN_PAGE_HTML
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") ->
                    membershipsHtml(if (left) remainingAfterLeave else listOf("kal-hub", "sock"))
                path.contains("/groups/search") -> """{"groups":[
                    {"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42},
                    {"id":11,"name":"Sock Society","permalink":"sock","forum_id":43}
                ]}"""
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        })
    }

    @Test
    fun `leaveGroup drops the left group and falls back to default when it was the selected one`() =
        runTest(UnconfinedTestDispatcher()) {
            val sock = Group(id = 11L, name = "Sock Society", permalink = "sock", forumId = 43L)
            val vm = FeedViewModel(
                leaveRepo(remainingAfterLeave = listOf("kal-hub")),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            vm.selectGroup(sock)
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(11L, (vm.state.value as FeedState.Loaded).selectedGroup?.id)

            vm.leaveGroup(sock)
            awaitChildren(coroutineContext[Job]!!)
            val loaded = assertIs<FeedState.Loaded>(vm.state.value)
            assertTrue(loaded.groups.none { it.id == 11L }) // left group gone
            assertEquals(10L, loaded.selectedGroup?.id)      // fell back to the default group
            assertEquals(null, vm.leavingGroupId.value)      // spinner flag cleared
        }

    @Test
    fun `leaveGroup keeps the current selection when a different group is left`() =
        runTest(UnconfinedTestDispatcher()) {
            val kal = Group(id = 10L, name = "KAL Hub", permalink = "kal-hub", forumId = 42L)
            val sock = Group(id = 11L, name = "Sock Society", permalink = "sock", forumId = 43L)
            val vm = FeedViewModel(
                leaveRepo(remainingAfterLeave = listOf("sock")),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            vm.selectGroup(sock)
            awaitChildren(coroutineContext[Job]!!)

            vm.leaveGroup(kal)
            awaitChildren(coroutineContext[Job]!!)
            val loaded = assertIs<FeedState.Loaded>(vm.state.value)
            assertTrue(loaded.groups.none { it.id == 10L })
            assertEquals(11L, loaded.selectedGroup?.id) // still viewing Sock Society
        }

    @Test
    fun `leaveGroup is ignored when the feed is not loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.leaveGroup(Group(id = 1L, name = "x", permalink = "x", forumId = 1L))
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loading>(vm.state.value)
        assertEquals(null, vm.leavingGroupId.value)
    }

    @Test
    fun `leaveGroup clears the leaving flag when the leave fails`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FeedRepository(routingApiClient { path ->
            when {
                path.endsWith("/leave") -> error("boom")
                path.isEmpty() || path == "/" -> TOKEN_PAGE_HTML
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("/groups/search") -> GROUPS_JSON
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        })
        val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val group = (vm.state.value as FeedState.Loaded).groups.first()

        vm.leaveGroup(group)
        awaitChildren(coroutineContext[Job]!!)
        // The leave failed, so the group stays and the spinner flag still resets — but
        // unlike before issue #263, the failure is no longer silent: leaveError carries a
        // message the dialog can surface instead of auto-dismissing as if it succeeded.
        assertIs<FeedState.Loaded>(vm.state.value)
        assertEquals(null, vm.leavingGroupId.value)
        assertEquals("boom", vm.leaveError.value)
    }

    @Test
    fun `acknowledgeLeaveError clears a stale leave error`() = runTest(UnconfinedTestDispatcher()) {
        val repo = FeedRepository(routingApiClient { path ->
            when {
                path.endsWith("/leave") -> error("boom")
                path.isEmpty() || path == "/" -> TOKEN_PAGE_HTML
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("/groups/search") -> GROUPS_JSON
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        })
        val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val group = (vm.state.value as FeedState.Loaded).groups.first()

        vm.leaveGroup(group)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("boom", vm.leaveError.value)

        vm.acknowledgeLeaveError()
        assertEquals(null, vm.leaveError.value)
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
            val vm = FeedViewModel(twoPageRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertEquals(listOf(100L), state.items.map { it.id })
            assertTrue(state.hasMore)
        }

    @Test
    fun `loadMore appends the next page and clears hasMore once exhausted`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(twoPageRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
        val vm = FeedViewModel(successRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
        val vm = FeedViewModel(twoPageRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(gatedTwoPageRepo(gate), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(gatedTwoPageRepo(gate), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
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

    /**
     * Single-group repo whose one topic (id 100) reports [postsCount] total posts and a
     * read marker at [lastRead] — so the loaded card starts with a known unread badge.
     */
    private fun unreadRepo(postsCount: Int = 10, lastRead: Int = 0): FeedRepository =
        FeedRepository(routingApiClient { path ->
            when {
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("/groups/search") -> GROUPS_JSON
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") ->
                    topicDetailJson(100L, postsCount = postsCount, lastRead = lastRead)
                else -> error("Unexpected: $path")
            }
        })

    @Test
    fun `markTopicReadUpTo lowers the unread badge and moves the first-unread marker`() =
        runTest(UnconfinedTestDispatcher()) {
            // 10 posts, never read → the card starts fully unread (issue #185).
            val vm = FeedViewModel(unreadRepo(postsCount = 10), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val before = assertIs<FeedState.Loaded>(vm.state.value).items.single()
            assertEquals(10, before.unreadCount)

            // Reading up to post 6 leaves posts 7..10 unread, first unread post 7.
            vm.markTopicReadUpTo(100L, readUpTo = 6)
            val item = assertIs<FeedState.Loaded>(vm.state.value).items.single()
            assertEquals(4, item.unreadCount)
            assertEquals(7, item.firstUnreadPostNumber)
        }

    @Test
    fun `markTopicReadUpTo clears the badge and marker once the whole topic is read`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(unreadRepo(postsCount = 10), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)

            vm.markTopicReadUpTo(100L, readUpTo = 10)
            val item = assertIs<FeedState.Loaded>(vm.state.value).items.single()
            assertEquals(0, item.unreadCount)
            assertNull(item.firstUnreadPostNumber)
        }

    @Test
    fun `markTopicReadUpTo never raises the unread count`() =
        runTest(UnconfinedTestDispatcher()) {
            // Ravelry's marker only advances; a lower readUpTo than an earlier one must
            // not bump the badge back up.
            val vm = FeedViewModel(unreadRepo(postsCount = 10), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)

            vm.markTopicReadUpTo(100L, readUpTo = 6) // unread -> 4
            vm.markTopicReadUpTo(100L, readUpTo = 3) // would be 7 (>4): ignored
            val item = assertIs<FeedState.Loaded>(vm.state.value).items.single()
            assertEquals(4, item.unreadCount)
            assertEquals(7, item.firstUnreadPostNumber)
        }
    // ---- drawer dots: per-group activity (issue #350 part 3) ----

    private val july1 = "2026/07/01 12:00:00 +0000"
    private val july5 = "2026/07/05 12:00:00 +0000"
    private val july10 = "2026/07/10 12:00:00 +0000"
    private val july19 = "2026/07/19 12:00:00 +0000"

    /** Epoch millis for a Ravelry-format timestamp, so tests can express "before/after". */
    private fun ms(timestamp: String): Long =
        parseRavelryTimestamp(timestamp)!!.toEpochMilliseconds()

    /**
     * Two groups — KAL Hub (id 10, forum 42) and Sock Society (id 11, forum 43) — whose
     * topic lists report [repliedAtByForumId] as their newest reply, plus a
     * `filtered_topics?status=posting` leg serving [postingJson].
     *
     * KAL Hub is first in fetch order, so it is the default group `load()` opens (and
     * therefore marks viewed); Sock Society stays unvisited, which is what lets a test
     * observe a dot at all.
     *
     * `filtered_topics` is routed BEFORE the generic `/forums/` branch: it lives under
     * `/forums/` too, so the order is load-bearing.
     */
    private fun activityRepo(
        repliedAtByForumId: Map<Long, String>,
        postingJson: String = """{"topics":[]}""",
    ): FeedRepository = FeedRepository(routingApiClient { path ->
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
            path.contains("filtered_topics") -> postingJson
            path.contains("/forums/") -> {
                val forumId = path.split("/forums/")[1].split("/")[0].toLong()
                val repliedAt = repliedAtByForumId[forumId]
                """{"topics":[{"id":${forumId * 10},"title":"T","replied_at":${
                    if (repliedAt != null) "\"$repliedAt\"" else "null"
                }}]}"""
            }
            path.contains("/topics/") -> topicDetailJson(
                path.split("/topics/")[1].replace(".json", "").toLong()
            )
            else -> error("Unexpected: $path")
        }
    })

    @Test
    fun `a group with activity since it was last viewed gets a dot`() =
        runTest(UnconfinedTestDispatcher()) {
            // The headline of Option A: the dot means "something happened here since you
            // looked", derived from the group's newest post vs a stored last-viewed time —
            // NOT from read markers, which don't exist for topics nobody has opened.
            val lastViewed = FakeGroupLastViewedStore(mapOf(10L to ms(july5), 11L to ms(july5)))
            val vm = FeedViewModel(
                // Sock Society (forum 43) has a reply after the 5th; KAL Hub's is before it.
                activityRepo(mapOf(42L to july1, 43L to july10)),
                this,
                FakeGroupOrderStore(),
                lastViewed,
                now = { ms(july19) },
            )
            vm.load()
            // The real screen only refreshes once the feed is loaded (the per-group leg
            // needs the user's groups), so sequence it the same way here.
            awaitChildren(coroutineContext[Job]!!)
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(setOf(43L), vm.drawerUnread.value.unreadGroupForumIds)
        }

    @Test
    fun `opening a group clears its dot and records the visit`() =
        runTest(UnconfinedTestDispatcher()) {
            // The other half of Option A, and the whole point of it: under the old
            // read-marker rule the only way to clear a group's dot was to open EVERY unread
            // topic and read to the end, which in a busy group meant the dot never went out.
            val lastViewed = FakeGroupLastViewedStore(mapOf(10L to ms(july5), 11L to ms(july5)))
            val vm = FeedViewModel(
                activityRepo(mapOf(42L to july1, 43L to july10)),
                this,
                FakeGroupOrderStore(),
                lastViewed,
                now = { ms(july19) },
            )
            vm.load()
            // The real screen only refreshes once the feed is loaded (the per-group leg
            // needs the user's groups), so sequence it the same way here.
            awaitChildren(coroutineContext[Job]!!)
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(setOf(43L), vm.drawerUnread.value.unreadGroupForumIds)

            val sock = assertIs<FeedState.Loaded>(vm.state.value).groups.first { it.id == 11L }
            vm.selectGroup(sock)
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.unreadGroupForumIds.isEmpty())
            assertEquals(ms(july19), lastViewed.stored?.get(11L))
        }

    @Test
    fun `a re-check after opening a group does not re-light its dot`() =
        runTest(UnconfinedTestDispatcher()) {
            // The visit has to be PERSISTED, not just cleared in memory — otherwise the
            // next foreground refresh would compare against the old timestamp and the dot
            // would come straight back.
            val lastViewed = FakeGroupLastViewedStore(mapOf(10L to ms(july5), 11L to ms(july5)))
            val vm = FeedViewModel(
                activityRepo(mapOf(42L to july1, 43L to july10)),
                this,
                FakeGroupOrderStore(),
                lastViewed,
                now = { ms(july19) },
            )
            vm.load()
            // The real screen only refreshes once the feed is loaded (the per-group leg
            // needs the user's groups), so sequence it the same way here.
            awaitChildren(coroutineContext[Job]!!)
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)
            val sock = assertIs<FeedState.Loaded>(vm.state.value).groups.first { it.id == 11L }
            vm.selectGroup(sock)
            awaitChildren(coroutineContext[Job]!!)

            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.unreadGroupForumIds.isEmpty())
        }

    @Test
    fun `the group shown on load is not dotted for activity the user is looking at`() =
        runTest(UnconfinedTestDispatcher()) {
            // KAL Hub's newest reply (the 10th) is after its stored last view (the 1st),
            // but load() opens it, so the user is staring at that very activity.
            val lastViewed = FakeGroupLastViewedStore(mapOf(10L to ms(july1), 11L to ms(july19)))
            val vm = FeedViewModel(
                activityRepo(mapOf(42L to july10, 43L to july1)),
                this,
                FakeGroupOrderStore(),
                lastViewed,
                now = { ms(july19) },
            )
            vm.load()
            // The real screen only refreshes once the feed is loaded (the per-group leg
            // needs the user's groups), so sequence it the same way here.
            awaitChildren(coroutineContext[Job]!!)
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.unreadGroupForumIds.isEmpty())
            assertEquals(ms(july19), lastViewed.stored?.get(10L))
        }

    @Test
    fun `a first run seeds every group silently instead of lighting them all`() =
        runTest(UnconfinedTestDispatcher()) {
            // Nothing stored (fresh install). Both groups have recent activity, but a
            // drawer that lights up everywhere on first launch is noise, not signal.
            val lastViewed = FakeGroupLastViewedStore()
            val vm = FeedViewModel(
                activityRepo(mapOf(42L to july10, 43L to july10)),
                this,
                FakeGroupOrderStore(),
                lastViewed,
                now = { ms(july19) },
            )
            vm.load()
            // The real screen only refreshes once the feed is loaded (the per-group leg
            // needs the user's groups), so sequence it the same way here.
            awaitChildren(coroutineContext[Job]!!)
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.unreadGroupForumIds.isEmpty())
            assertEquals(mapOf(10L to ms(july19), 11L to ms(july19)), lastViewed.stored)
        }

    @Test
    fun `a group opened while a refresh is in flight keeps its cleared dot`() =
        runTest(UnconfinedTestDispatcher()) {
            // The refresh computes its last-viewed map from the snapshot it STARTED with,
            // and it is slow (1 + groups.size requests). If persisting that map plainly
            // overwrote the store, a group the user opened meanwhile would be rolled back
            // to its old timestamp and its dot would light straight back up. The persist
            // merges by taking the later timestamp per group, which this pins down.
            var clock = ms(july5)
            val gate = CompletableDeferred<Unit>()
            val groupTopicsGateMutex = Mutex()
            var groupTopicsSeen = 0
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
                        path.contains("filtered_topics") -> """{"topics":[]}"""
                        path.contains("/forums/43/") -> {
                            // Hold ONLY the refresh's fan-out request for forum 43 (the
                            // first one the feed load doesn't make), opening the window.
                            val hold = groupTopicsGateMutex.withLock {
                                (groupTopicsSeen == 0).also { groupTopicsSeen++ }
                            }
                            if (hold) gate.await()
                            """{"topics":[{"id":430,"title":"T","replied_at":"$july10"}]}"""
                        }
                        path.contains("/forums/") ->
                            """{"topics":[{"id":420,"title":"T","replied_at":"$july1"}]}"""
                        path.contains("/topics/") -> topicDetailJson(
                            path.split("/topics/")[1].replace(".json", "").toLong()
                        )
                        else -> error("Unexpected: $path")
                    }
                },
            )
            val lastViewed = FakeGroupLastViewedStore(mapOf(10L to ms(july5), 11L to ms(july5)))
            val vm = FeedViewModel(repo, this, FakeGroupOrderStore(), lastViewed, now = { clock })
            vm.load()
            // load() only fetches the default group (forum 42), so the gate can't hold it.
            awaitChildren(coroutineContext[Job]!!)

            // Refresh starts and blocks mid-fan-out on forum 43.
            vm.refreshDrawerUnread()
            // ...meanwhile the user opens Sock Society, at a LATER time than the snapshot.
            clock = ms(july19)
            val sock = assertIs<FeedState.Loaded>(vm.state.value).groups.first { it.id == 11L }
            vm.selectGroup(sock)
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            // Forum 43's activity (the 10th) is older than the visit (the 19th): no dot,
            // and the visit survived in the store.
            assertTrue(vm.drawerUnread.value.unreadGroupForumIds.isEmpty())
            assertEquals(ms(july19), lastViewed.stored?.get(11L))
        }

    @Test
    fun `a group whose activity fetch fails shows no dot`() =
        runTest(UnconfinedTestDispatcher()) {
            // No timestamp on any topic is the same "unknown" case as a failed request:
            // don't invent a dot out of missing data.
            val lastViewed = FakeGroupLastViewedStore(mapOf(10L to ms(july1), 11L to ms(july1)))
            val vm = FeedViewModel(
                activityRepo(repliedAtByForumId = emptyMap()),
                this,
                FakeGroupOrderStore(),
                lastViewed,
                now = { ms(july19) },
            )
            vm.load()
            // The real screen only refreshes once the feed is loaded (the per-group leg
            // needs the user's groups), so sequence it the same way here.
            awaitChildren(coroutineContext[Job]!!)
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.unreadGroupForumIds.isEmpty())
        }

    // ---- the "Your Posts" dot after reading (issue #350 part 2, as revised by part 3) ----

    /**
     * A feed repo whose `filtered_topics` route (the "Your Posts" leg and the My Posts
     * feed) serves whatever [payload] currently holds, so a test can change the answer
     * mid-test and detect from the resulting [FeedViewModel.drawerUnread] whether a
     * re-check actually re-fetched.
     *
     * [payload] is a [MutableStateFlow], not a plain `var`: MockEngine dispatches request
     * handling on [kotlinx.coroutines.Dispatchers.IO] regardless of the calling coroutine's
     * dispatcher (see [FakeFeedTokenStorage]'s KDoc), so the route body genuinely runs on
     * another thread — a StateFlow's volatile-backed value guarantees it observes the
     * test thread's write, where an unsynchronized field would be a CI-only flake.
     *
     * `filtered_topics` is routed BEFORE the generic `/forums/` branch: it lives under
     * `/forums/` too, so the order is load-bearing.
     */
    private fun drawerDotRepo(
        payload: MutableStateFlow<String>,
        postsCount: Int = 10,
    ): FeedRepository = FeedRepository(routingApiClient { path ->
        when {
            path.contains("/current_user") -> CURRENT_USER_JSON
            path.contains("memberships") -> MEMBERSHIPS_HTML
            path.contains("/groups/search") -> GROUPS_JSON
            path.contains("filtered_topics") -> payload.value
            path.contains("/forums/") -> topicsJson(100L)
            path.contains("/topics/") -> topicDetailJson(100L, postsCount = postsCount)
            else -> error("Unexpected: $path")
        }
    })

    /** One topic with unread replies in [forumId] — lights the "Your Posts" dot. */
    private fun unreadTopicJson(forumId: Long) =
        """{"topics":[{"id":1,"title":"T","forum_id":$forumId,"forum_posts_count":5,"last_read":3}]}"""

    /** One fully-read topic — lights nothing. */
    private val readTopicJson =
        """{"topics":[{"id":1,"title":"T","forum_id":42,"forum_posts_count":5,"last_read":5}]}"""

    @Test
    fun `refreshDrawerUnreadAfterReading re-checks the Your Posts dot from the My Posts feed`() =
        runTest(UnconfinedTestDispatcher()) {
            // The "Your Posts" dot stayed read-marker based under Option A, so reading IS
            // still the natural moment it can go out — and on the My Posts feed every topic
            // is by definition one the user posted in.
            val payload = MutableStateFlow(unreadTopicJson(forumId = 999L))
            val vm = FeedViewModel(
                drawerDotRepo(payload),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            vm.selectMyPosts()
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)
            assertTrue(vm.drawerUnread.value.yourPostsHasUnread)
            val item = assertIs<FeedState.Loaded>(vm.state.value).items.single()
            assertTrue(item.unreadCount > 0)

            // Ravelry now reports nothing unread; only an actual re-fetch can observe that.
            payload.value = readTopicJson
            vm.markTopicReadUpTo(item.id, readUpTo = item.postCount)
            vm.refreshDrawerUnreadAfterReading(item.id)
            awaitChildren(coroutineContext[Job]!!)

            assertFalse(vm.drawerUnread.value.yourPostsHasUnread)
        }

    @Test
    fun `refreshDrawerUnreadAfterReading skips the re-check when the Your Posts dot is dark`() =
        runTest(UnconfinedTestDispatcher()) {
            // Backing out of a topic is very common; with nothing lit there is nothing a
            // read could clear, so the request would be pure waste.
            val payload = MutableStateFlow(readTopicJson)
            val vm = FeedViewModel(
                drawerDotRepo(payload),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            vm.selectMyPosts()
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)
            assertFalse(vm.drawerUnread.value.yourPostsHasUnread)

            // If a re-check fired, it would pick this up and light the dot.
            payload.value = unreadTopicJson(forumId = 999L)
            val item = assertIs<FeedState.Loaded>(vm.state.value).items.single()
            vm.refreshDrawerUnreadAfterReading(item.id)
            awaitChildren(coroutineContext[Job]!!)

            assertFalse(vm.drawerUnread.value.yourPostsHasUnread)
        }

    @Test
    fun `refreshDrawerUnreadAfterReading skips the re-check when reading from a group feed`() =
        runTest(UnconfinedTestDispatcher()) {
            // Deliberate narrowing: outside the My Posts feed, whether the user posted in
            // the topic they just read isn't known without another fetch, and the dot is
            // lit often enough that guessing yes would make the gate near-unconditional.
            // It settles on the next foreground activation or drawer pull instead.
            val payload = MutableStateFlow(unreadTopicJson(forumId = 999L))
            val vm = FeedViewModel(
                drawerDotRepo(payload),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
            vm.load()
            // The real screen only refreshes once the feed is loaded (the per-group leg
            // needs the user's groups), so sequence it the same way here.
            awaitChildren(coroutineContext[Job]!!)
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)
            assertTrue(vm.drawerUnread.value.yourPostsHasUnread)

            // A group feed is showing, not My Posts, so this must not re-fetch — if it did,
            // it would observe the now-read payload and clear the dot.
            payload.value = readTopicJson
            vm.markTopicReadUpTo(100L, readUpTo = 10)
            vm.refreshDrawerUnreadAfterReading(100L)
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.yourPostsHasUnread)
        }

    @Test
    fun `reading a topic no longer touches the per-group dots`() =
        runTest(UnconfinedTestDispatcher()) {
            // Superseded by part 3: a group's dot is cleared by VISITING the group, not by
            // reading its topics. This pins down that the read path leaves them alone
            // rather than two mechanisms fighting over the same state.
            val lastViewed = FakeGroupLastViewedStore(mapOf(10L to ms(july5), 11L to ms(july5)))
            val vm = FeedViewModel(
                activityRepo(
                    mapOf(42L to july1, 43L to july10),
                    postingJson = unreadTopicJson(forumId = 999L),
                ),
                this,
                FakeGroupOrderStore(),
                lastViewed,
                now = { ms(july19) },
            )
            vm.load()
            // The real screen only refreshes once the feed is loaded (the per-group leg
            // needs the user's groups), so sequence it the same way here.
            awaitChildren(coroutineContext[Job]!!)
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(setOf(43L), vm.drawerUnread.value.unreadGroupForumIds)

            val topicId = assertIs<FeedState.Loaded>(vm.state.value).items.single().id
            vm.markTopicReadUpTo(topicId, readUpTo = 99)
            vm.refreshDrawerUnreadAfterReading(topicId)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(setOf(43L), vm.drawerUnread.value.unreadGroupForumIds)
        }

    @Test
    fun `refreshDrawerUnreadAfterReading is a no-op when the topic is no longer in the feed`() =
        runTest(UnconfinedTestDispatcher()) {
            // This runs on a callback fired when the read POST lands, so the feed can have
            // been reloaded or switched to another group in the meantime — the just-read
            // topic need not still be on screen. No re-fetch, and no crash.
            val payload = MutableStateFlow(unreadTopicJson(forumId = 999L))
            val vm = FeedViewModel(
                drawerDotRepo(payload),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            vm.selectMyPosts()
            vm.refreshDrawerUnread()
            awaitChildren(coroutineContext[Job]!!)
            assertTrue(vm.drawerUnread.value.yourPostsHasUnread)

            payload.value = readTopicJson
            vm.refreshDrawerUnreadAfterReading(999_999L)
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(vm.drawerUnread.value.yourPostsHasUnread)
        }

    @Test
    fun `refreshDrawerUnreadAfterReading is a no-op when the feed is not loaded`() =
        runTest(UnconfinedTestDispatcher()) {
            // Same callback timing: a session expiry or reset() can have dropped the feed
            // out of Loaded before the read POST landed.
            val vm = FeedViewModel(
                drawerDotRepo(MutableStateFlow(readTopicJson)),
                this,
                FakeGroupOrderStore(),
                FakeGroupLastViewedStore(),
            )

            vm.refreshDrawerUnreadAfterReading(100L)
            awaitChildren(coroutineContext[Job]!!)

            assertIs<FeedState.Loading>(vm.state.value)
            assertEquals(DrawerUnread(), vm.drawerUnread.value)
        }

    @Test
    fun `markTopicReadUpTo is a no-op when the feed is not loaded`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(unreadRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            // No load(): state is still Loading, so there is nothing to update.
            vm.markTopicReadUpTo(100L, readUpTo = 5)
            assertIs<FeedState.Loading>(vm.state.value)
        }

    @Test
    fun `markTopicReadUpTo is a no-op when the topic is not in the feed`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(unreadRepo(postsCount = 10), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            val before = vm.state.value

            vm.markTopicReadUpTo(999L, readUpTo = 5)
            assertEquals(before, vm.state.value)
        }

    /**
     * [successRepo] plus a filtered_topics route for the "My Posts" feed. Routed BEFORE
     * the generic `/forums/` branch — filtered_topics lives under `/forums/` too, so the
     * order is load-bearing. Each call returns the next page (300 then 301) so loadMore
     * can be exercised; page 1 of 2 → hasMore.
     */
    private fun myPostsRepo(): FeedRepository {
        var filteredCalls = 0
        return FeedRepository(routingApiClient { path ->
            when {
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("/groups/search") -> GROUPS_JSON
                path.contains("filtered_topics") -> {
                    filteredCalls++
                    val id = if (filteredCalls == 1) 300L else 301L
                    """{"topics":[{"id":$id,"title":"Topic $id","forum_id":42}],
                        "paginator":{"page":$filteredCalls,"page_count":2,"results":2}}"""
                }
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(
                    path.split("/topics/")[1].replace(".json", "").toLong(),
                )
                else -> error("Unexpected: $path")
            }
        })
    }

    @Test
    fun `selectMyPosts swaps to the cross-group feed and clears the group selection`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(myPostsRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)

            vm.selectMyPosts()
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertTrue(state.myPosts)
            assertNull(state.selectedGroup)
            assertEquals(listOf(300L), state.items.map { it.id })
            // Attributed to KAL Hub via the list entry's forum_id 42.
            assertEquals("KAL Hub", state.items.single().groupName)
            assertTrue(state.hasMore)
        }

    @Test
    fun `selectGroup leaves the my-posts view`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(myPostsRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        vm.selectMyPosts()
        awaitChildren(coroutineContext[Job]!!)

        vm.selectGroup(group)
        awaitChildren(coroutineContext[Job]!!)

        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertFalse(state.myPosts)
        assertEquals(group, state.selectedGroup)
        assertEquals(listOf(100L), state.items.map { it.id })
    }

    @Test
    fun `loadMore in the my-posts view appends the next cross-group page`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(myPostsRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            vm.selectMyPosts()
            awaitChildren(coroutineContext[Job]!!)

            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<FeedState.Loaded>(vm.state.value)
            assertTrue(state.myPosts)
            assertEquals(listOf(300L, 301L), state.items.map { it.id })
            assertFalse(state.hasMore)
        }

    @Test
    fun `refresh keeps the my-posts view`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(myPostsRepo(), this, FakeGroupOrderStore(), FakeGroupLastViewedStore())
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        vm.selectMyPosts()
        awaitChildren(coroutineContext[Job]!!)

        vm.refresh()
        awaitChildren(coroutineContext[Job]!!)

        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertTrue(state.myPosts)
        assertNull(state.selectedGroup)
    }

    @Test
    fun `joinSupportGroup keeps the my-posts view`() = runTest(UnconfinedTestDispatcher()) {
        // joinSupportGroup's reload threads `myPosts` through the same way refresh does
        // (see the test above) — this is the one other reload path that reads it.
        val vm = FeedViewModel(
            FeedRepository(routingApiClient { path ->
                when {
                    path.endsWith("/join") -> "ok"
                    path.isEmpty() || path == "/" -> TOKEN_PAGE_HTML
                    path.contains("/current_user") -> CURRENT_USER_JSON
                    path.contains("memberships") -> MEMBERSHIPS_HTML
                    path.contains("/groups/search") -> GROUPS_JSON
                    path.contains("filtered_topics") ->
                        """{"topics":[{"id":300,"title":"Topic 300","forum_id":42}]}"""
                    path.contains("/forums/") -> topicsJson(100L)
                    path.contains("/topics/") -> topicDetailJson(
                        path.split("/topics/")[1].replace(".json", "").toLong(),
                    )
                    else -> error("Unexpected: $path")
                }
            }),
            this,
            FakeGroupOrderStore(),
            FakeGroupLastViewedStore(),
        )
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        vm.selectMyPosts()
        awaitChildren(coroutineContext[Job]!!)

        vm.joinSupportGroup("fibersocial-app-support")
        awaitChildren(coroutineContext[Job]!!)

        assertIs<JoinState.Idle>(vm.joinState.value)
        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertTrue(state.myPosts)
        assertNull(state.selectedGroup)
    }
}
