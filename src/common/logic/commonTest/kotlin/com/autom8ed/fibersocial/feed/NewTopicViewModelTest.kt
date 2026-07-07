package com.autom8ed.fibersocial.feed

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.forms.FormDataContent
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewTopicViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    @Test
    fun `initial state is Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewTopicViewModel(routingApiClient { topicCreateResponseJson() }, this)
        assertIs<NewTopicState.Idle>(vm.state.value)
    }

    @Test
    fun `create transitions to Created with the new topic`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewTopicViewModel(routingApiClient { topicCreateResponseJson(id = 7001L) }, this)
        vm.create(42L, "My new topic", "Opening post")
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<NewTopicState.Created>(vm.state.value)
        assertEquals(7001L, state.topic.id)
        assertEquals("My new topic", state.topic.title)
    }

    @Test
    fun `create forwards a trimmed summary to the api and drops a blank one`() = runTest(UnconfinedTestDispatcher()) {
        val sentSummaries = mutableListOf<String?>()
        val engine = MockEngine { request ->
            sentSummaries += (request.body as FormDataContent).formData["summary"]
            respond(
                content = topicCreateResponseJson(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = NewTopicViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)
        vm.create(42L, "title", "body", "  A short blurb  ")
        awaitChildren(coroutineContext[Job]!!)
        vm.create(42L, "title", "body", "   ")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(listOf("A short blurb", null), sentSummaries)
    }

    @Test
    fun `create ignores blank title or body`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewTopicViewModel(errorApiClient(), this)
        vm.create(42L, "   ", "body")
        vm.create(42L, "title", "  \n ")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<NewTopicState.Idle>(vm.state.value)
    }

    @Test
    fun `create clamps overlong titles to the Ravelry limit`() = runTest(UnconfinedTestDispatcher()) {
        var sentTitle: String? = null
        val engine = MockEngine { request ->
            sentTitle = (request.body as FormDataContent).formData["title"]
            respond(
                content = topicCreateResponseJson(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = NewTopicViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)
        vm.create(42L, "x".repeat(300), "body")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(NewTopicViewModel.MAX_TITLE_LENGTH, sentTitle!!.length)
    }

    @Test
    fun `double submits and mid-send resets are ignored while Sending`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        var requests = 0
        val engine = MockEngine { _ ->
            requests++
            gate.await()
            respond(
                content = topicCreateResponseJson(),
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = NewTopicViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)

        vm.create(42L, "title", "body")
        assertIs<NewTopicState.Sending>(vm.state.value)
        vm.create(42L, "another title", "another body")
        vm.reset()
        assertIs<NewTopicState.Sending>(vm.state.value)

        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<NewTopicState.Created>(vm.state.value)
        assertEquals(1, requests)
    }

    @Test
    fun `create transitions to Error when api fails and stays recoverable`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewTopicViewModel(errorApiClient(), this)
        vm.create(42L, "title", "body")
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<NewTopicState.Error>(vm.state.value)
        assertEquals("Simulated network error", state.message)
        // reset clears the error so a reopened composer starts clean.
        vm.reset()
        assertIs<NewTopicState.Idle>(vm.state.value)
    }

    @Test
    fun `create signals sessionExpired and returns to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewTopicViewModel(sessionExpiredApiClient(), this)
        vm.create(42L, "title", "body")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<NewTopicState.Idle>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `null exception messages fall back to a default`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewTopicViewModel(nullMessageApiClient(), this)
        vm.create(42L, "title", "body")
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<NewTopicState.Error>(vm.state.value)
        assertEquals("Failed to create topic", state.message)
    }

    @Test
    fun `acknowledgeCreated resets Created back to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewTopicViewModel(routingApiClient { topicCreateResponseJson() }, this)
        vm.create(42L, "title", "body")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<NewTopicState.Created>(vm.state.value)
        vm.acknowledgeCreated()
        assertIs<NewTopicState.Idle>(vm.state.value)
    }

    @Test
    fun `acknowledgeCreated does not clobber an Error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewTopicViewModel(errorApiClient(), this)
        vm.create(42L, "title", "body")
        awaitChildren(coroutineContext[Job]!!)
        vm.acknowledgeCreated()
        assertIs<NewTopicState.Error>(vm.state.value)
    }
}
