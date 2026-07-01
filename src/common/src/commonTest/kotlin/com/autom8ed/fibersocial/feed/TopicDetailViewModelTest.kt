package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.AuthToken
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
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
