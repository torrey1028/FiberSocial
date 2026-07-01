package com.autom8ed.fibersocial.feed

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

    @Test
    fun `initial state is Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this)
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `load transitions to Loaded on success`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this)
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertEquals("yarnie", state.user.username)
        assertEquals(1, state.groups.size)
        assertEquals(1, state.items.size)
    }

    @Test
    fun `load sets selectedGroup to null on initial load`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this)
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertEquals(null, state.selectedGroup)
    }

    @Test
    fun `load transitions to Error when api fails`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(FeedRepository(errorApiClient()), this)
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Error>(vm.state.value)
        assertEquals("Simulated network error", state.message)
    }

    @Test
    fun `refresh transitions through Refreshing to Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this)
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)

        vm.refresh()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loaded>(vm.state.value)
    }

    @Test
    fun `refresh is a no-op when state is not Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this)
        // State is Loading — refresh should silently do nothing
        vm.refresh()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `selectGroup filters feed to the chosen group`() = runTest(UnconfinedTestDispatcher()) {
        val group2 = Group(id = 11L, name = "Sock Society", permalink = "sock", forumId = 43L)
        var topicsForumId = 0L
        val repo = FeedRepository(routingApiClient { path ->
            when {
                path.contains("/current_user") -> CURRENT_USER_JSON
                path.contains("memberships") ->
                    """<html><body>
                    <a href="https://www.ravelry.com/groups/kal-hub">KAL Hub</a>
                    <a href="https://www.ravelry.com/groups/sock">Sock Society</a>
                    </body></html>"""
                // return two groups
                path.contains("/groups/search") ->
                    """{"groups":[
                        {"id":10,"name":"KAL Hub","permalink":"kal-hub","forum_id":42},
                        {"id":11,"name":"Sock Society","permalink":"sock","forum_id":43}
                    ]}"""
                path.contains("/forums/") -> {
                    topicsForumId = path.split("/forums/")[1].split("/")[0].toLong()
                    topicsJson(if (topicsForumId == 42L) 100L else 200L)
                }
                path.contains("/topics/") -> topicDetailJson(
                    path.split("/topics/")[1].replace(".json", "").toLong()
                )
                else -> error("Unexpected: $path")
            }
        })
        val vm = FeedViewModel(repo, this)
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
    fun `selectGroup with null loads all groups`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this)
        vm.load()
        awaitChildren(coroutineContext[Job]!!)
        val loaded = assertIs<FeedState.Loaded>(vm.state.value)

        vm.selectGroup(null)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedState.Loaded>(vm.state.value)
        assertEquals(null, state.selectedGroup)
        assertEquals(loaded.groups, state.groups)
    }

    @Test
    fun `selectGroup is a no-op when state is not Loaded`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedViewModel(successRepo(), this)
        vm.selectGroup(group)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedState.Loading>(vm.state.value)
    }

    @Test
    fun `load signals sessionExpired and stays Loading when session expires`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedViewModel(FeedRepository(sessionExpiredApiClient()), this)
            vm.load()
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedState.Loading>(vm.state.value)
            // sessionExpired is filter{it}.map{} on a StateFlow, so first() returns
            // immediately without suspension when the flag is already true
            vm.sessionExpired.first()
        }
}
