package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class FeedViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

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
    fun `load opens the stored order's first group, not the fetched order's`() =
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
}
