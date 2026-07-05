package com.autom8ed.fibersocial.feedback

import com.autom8ed.fibersocial.feed.FakeFeedTokenStorage
import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.feed.errorApiClient
import com.autom8ed.fibersocial.feed.routingApiClient
import com.autom8ed.fibersocial.feed.sessionExpiredApiClient
import com.autom8ed.fibersocial.feed.topicCreateResponseJson
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
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

@OptIn(ExperimentalCoroutinesApi::class)
class FeedbackViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    /** A client whose topic-create endpoint returns [status] with an empty body. */
    private fun statusApiClient(status: HttpStatusCode): RavelryApiClient {
        val engine = MockEngine { respond("", status, headersOf()) }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(client, FakeFeedTokenStorage())
    }

    @Test
    fun `initial state is Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedbackViewModel(routingApiClient { topicCreateResponseJson() }, this)
        assertIs<FeedbackState.Idle>(vm.state.value)
    }

    @Test
    fun `send posts feedback and transitions to Sent`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedbackViewModel(routingApiClient { topicCreateResponseJson(id = 8001L) }, this)
        vm.send("The feed scrolls to the top on refresh", deviceInfoSample())
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<FeedbackState.Sent>(vm.state.value)
        assertEquals(8001L, state.topic.id)
    }

    @Test
    fun `send targets the support forum with a derived title and body including details`() =
        runTest(UnconfinedTestDispatcher()) {
            var forumId: String? = null
            var title: String? = null
            var body: String? = null
            val engine = MockEngine { request ->
                val form = request.body as FormDataContent
                forumId = form.formData["forum_id"]
                title = form.formData["title"]
                body = form.formData["body"]
                respond(
                    topicCreateResponseJson(),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = FeedbackViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)

            vm.send("Crash on opening a photo\nSteps: tap any WIP post", "App: FiberSocial 1.0 (build 1)")
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(SupportGroup.FORUM_ID.toString(), forumId)
            assertEquals("Crash on opening a photo", title)
            assertTrue(body!!.startsWith("Crash on opening a photo"))
            assertTrue("App: FiberSocial 1.0 (build 1)" in body!!)
        }

    @Test
    fun `send ignores a blank description`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedbackViewModel(errorApiClient(), this)
        vm.send("   \n  ", "some details")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedbackState.Idle>(vm.state.value)
    }

    @Test
    fun `a 403 becomes NeedsMembership, not an error or login bounce`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedbackViewModel(statusApiClient(HttpStatusCode.Forbidden), this)
            vm.send("I can't post", "")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedbackState.NeedsMembership>(vm.state.value)
        }

    @Test
    fun `an unexpected failure becomes a recoverable Error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedbackViewModel(errorApiClient(), this)
        vm.send("something broke", "")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedbackState.Error>(vm.state.value)
        vm.reset()
        assertIs<FeedbackState.Idle>(vm.state.value)
    }

    @Test
    fun `session expiry signals sessionExpired and returns to Idle`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = FeedbackViewModel(sessionExpiredApiClient(), this)
            vm.send("hello", "")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<FeedbackState.Idle>(vm.state.value)
            assertEquals(Unit, vm.sessionExpired.first())
        }

    @Test
    fun `acknowledgeSent resets Sent back to Idle`() = runTest(UnconfinedTestDispatcher()) {
        val vm = FeedbackViewModel(routingApiClient { topicCreateResponseJson() }, this)
        vm.send("great app", "")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<FeedbackState.Sent>(vm.state.value)
        vm.acknowledgeSent()
        assertIs<FeedbackState.Idle>(vm.state.value)
    }

    @Test
    fun `feedbackTitle uses the first non-blank line`() {
        assertEquals("Real first line", feedbackTitle("\n  \nReal first line\nmore text"))
    }

    @Test
    fun `feedbackTitle falls back to a default for a blank description`() {
        assertEquals("App feedback", feedbackTitle("   \n\t "))
    }

    @Test
    fun `feedbackTitle clamps to Ravelry's limit`() {
        assertEquals(250, feedbackTitle("x".repeat(400)).length)
    }

    @Test
    fun `feedbackBody appends details under a divider`() {
        assertEquals("my report\n\n---\ncontext", feedbackBody("  my report  ", "context"))
    }

    @Test
    fun `feedbackBody omits the divider when there are no details`() {
        assertEquals("my report", feedbackBody("my report", "   "))
    }

    private fun deviceInfoSample() = "App: FiberSocial 1.0 (build 1)\nDevice: Test · Android 15"
}
