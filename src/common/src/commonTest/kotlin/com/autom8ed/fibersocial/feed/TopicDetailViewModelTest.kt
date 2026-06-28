package com.autom8ed.fibersocial.feed

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

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
}
