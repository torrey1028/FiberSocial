package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.Group
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class GroupPreviewViewModelTest {

    private suspend fun awaitChildren(job: Job) {
        do {
            job.children.toList().forEach { it.join() }
        } while (job.children.any { it.isActive })
    }

    private val lace = Group(id = 1L, name = "Lace Knitters", permalink = "lace-knitters", forumId = 11L)

    /** Topic list + per-topic detail, the two calls getFeedItemsPage makes. */
    private fun previewClient(topicIds: List<Long>, pageCount: Int = 1) = routingApiClient { path ->
        when {
            path.contains("/topics.json") -> """{"topics":[${
                topicIds.joinToString(",") { """{"id":$it,"title":"Topic $it"}""" }
            }],"paginator":{"page":1,"page_count":$pageCount}}"""
            else -> {
                val id = path.substringAfter("/topics/").substringBefore(".json")
                """{"topic":{"id":$id,"title":"Topic $id","posts_count":1,
                    "created_by":{"username":"knitwit"}}}"""
            }
        }
    }

    @Test
    fun `open loads the group's topics`() = runTest(UnconfinedTestDispatcher()) {
        val vm = GroupPreviewViewModel(FeedRepository(previewClient(listOf(100L, 101L))), this)
        vm.open(lace)
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<GroupPreviewState.Loaded>(vm.state.value)
        assertEquals(lace, state.group)
        assertEquals(setOf(100L, 101L), state.items.map { it.id }.toSet())
    }

    @Test
    fun `state starts hidden and returns to hidden on close`() = runTest(UnconfinedTestDispatcher()) {
        val vm = GroupPreviewViewModel(FeedRepository(previewClient(listOf(100L))), this)
        assertIs<GroupPreviewState.Hidden>(vm.state.value)
        vm.open(lace)
        awaitChildren(coroutineContext[Job]!!)
        vm.close()
        assertIs<GroupPreviewState.Hidden>(vm.state.value)
    }

    @Test
    fun `a load that lands after the preview closed is dropped`() = runTest(UnconfinedTestDispatcher()) {
        // Otherwise the screen reopens itself under a user who already backed out of it.
        val vm = GroupPreviewViewModel(FeedRepository(previewClient(listOf(100L))), this)
        vm.open(lace)
        vm.close()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<GroupPreviewState.Hidden>(vm.state.value)
    }

    @Test
    fun `loadMore appends the next page`() = runTest(UnconfinedTestDispatcher()) {
        val vm = GroupPreviewViewModel(FeedRepository(previewClient(listOf(100L), pageCount = 2)), this)
        vm.open(lace)
        awaitChildren(coroutineContext[Job]!!)
        assertTrue(assertIs<GroupPreviewState.Loaded>(vm.state.value).hasMore)
        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<GroupPreviewState.Loaded>(vm.state.value)
        assertEquals(2, state.page)
        // Same stub for both pages, so the ids repeat — what matters is that page 2 was
        // appended rather than replacing page 1.
        assertEquals(2, state.items.size)
    }

    @Test
    fun `loadMore is ignored on the last page`() = runTest(UnconfinedTestDispatcher()) {
        val vm = GroupPreviewViewModel(FeedRepository(previewClient(listOf(100L))), this)
        vm.open(lace)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadMore()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, assertIs<GroupPreviewState.Loaded>(vm.state.value).items.size)
    }

    @Test
    fun `a failure is retryable and keeps naming the group`() = runTest(UnconfinedTestDispatcher()) {
        var fail = true
        val vm = GroupPreviewViewModel(
            FeedRepository(
                routingApiClient { path ->
                    if (fail) throw RuntimeException("boom")
                    if (path.contains("/topics.json")) {
                        """{"topics":[{"id":100,"title":"T"}],"paginator":{"page":1,"page_count":1}}"""
                    } else {
                        """{"topic":{"id":100,"title":"T","posts_count":1,
                            "created_by":{"username":"knitwit"}}}"""
                    }
                },
            ),
            this,
        )
        vm.open(lace)
        awaitChildren(coroutineContext[Job]!!)
        val error = assertIs<GroupPreviewState.Error>(vm.state.value)
        assertEquals(lace, error.group)
        assertTrue(error.message.contains("Lace Knitters"))

        fail = false
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<GroupPreviewState.Loaded>(vm.state.value)
    }

    @Test
    fun `a group with no topics is Loaded and empty rather than an error`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = GroupPreviewViewModel(FeedRepository(previewClient(emptyList())), this)
            vm.open(lace)
            awaitChildren(coroutineContext[Job]!!)
            val state = assertIs<GroupPreviewState.Loaded>(vm.state.value)
            assertTrue(state.items.isEmpty())
        }

    @Test
    fun `retry from hidden does nothing`() = runTest(UnconfinedTestDispatcher()) {
        var calls = 0
        val vm = GroupPreviewViewModel(
            FeedRepository(routingApiClient { calls++; """{"topics":[]}""" }),
            this,
        )
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<GroupPreviewState.Hidden>(vm.state.value)
        assertEquals(0, calls)
    }

    @Test
    fun `retry from a loaded preview reloads the same group`() = runTest(UnconfinedTestDispatcher()) {
        val vm = GroupPreviewViewModel(FeedRepository(previewClient(listOf(100L))), this)
        vm.open(lace)
        awaitChildren(coroutineContext[Job]!!)
        vm.retry()
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<GroupPreviewState.Loaded>(vm.state.value)
        assertEquals(lace, state.group)
        assertEquals(1, state.page)
    }

    @Test
    fun `a load for a group the user navigated away from is dropped`() =
        runTest(UnconfinedTestDispatcher()) {
            // Opening a second group while the first is still loading must not let the
            // first one's topics land on top of the second.
            val socks = Group(id = 2L, name = "Sock Knitters", permalink = "socks", forumId = 22L)
            val gate = CompletableDeferred<Unit>()
            val vm = GroupPreviewViewModel(
                FeedRepository(
                    suspendableRoutingApiClient { url ->
                        val path = url.encodedPath
                        if (path.contains("/forums/11/")) gate.await()
                        if (path.contains("/topics.json")) {
                            """{"topics":[{"id":100,"title":"T"}],"paginator":{"page":1,"page_count":1}}"""
                        } else {
                            """{"topic":{"id":100,"title":"T","posts_count":1,
                                "created_by":{"username":"knitwit"}}}"""
                        }
                    },
                ),
                this,
            )
            vm.open(lace)
            // socks is not gated, so it lands while lace is still blocked — joining here
            // would deadlock on the gate, which is the point of releasing it after.
            vm.open(socks)
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(socks, assertIs<GroupPreviewState.Loaded>(vm.state.value).group)
        }

    @Test
    fun `a failed loadMore clears the spinner and keeps what was already there`() =
        runTest(UnconfinedTestDispatcher()) {
            var topicListCalls = 0
            val vm = GroupPreviewViewModel(
                FeedRepository(
                    routingApiClient { path ->
                        if (path.contains("/topics.json")) {
                            topicListCalls++
                            if (topicListCalls > 1) throw RuntimeException("page 2 boom")
                            """{"topics":[{"id":100,"title":"T"}],"paginator":{"page":1,"page_count":2}}"""
                        } else {
                            """{"topic":{"id":100,"title":"T","posts_count":1,
                                "created_by":{"username":"knitwit"}}}"""
                        }
                    },
                ),
                this,
            )
            vm.open(lace)
            awaitChildren(coroutineContext[Job]!!)
            val before = assertIs<GroupPreviewState.Loaded>(vm.state.value).items.size
            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)
            val after = assertIs<GroupPreviewState.Loaded>(vm.state.value)
            // The spinner has to clear or the footer spins forever on a transient failure.
            assertTrue(!after.loadingMore)
            assertEquals(before, after.items.size)
        }
}
