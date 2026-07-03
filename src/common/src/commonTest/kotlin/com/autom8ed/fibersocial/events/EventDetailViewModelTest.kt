package com.autom8ed.fibersocial.events

import com.autom8ed.fibersocial.feed.FakeFeedTokenStorage
import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.feed.errorApiClient
import com.autom8ed.fibersocial.feed.routingApiClient
import com.autom8ed.fibersocial.feed.sessionExpiredApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

private val MINIMAL_EVENT_PAGE = """
    <div class="page_title">Cozy Meetup</div>
    <div class="event__detail">
    <div class="event__type">Knitting/crochet group</div>
    <div class="event__dates">July  9, 2026 @ 6:00 PM</div>
    </div>
"""

private val EVENT_PAGE_WITH_TOKEN = """
    <meta content="test-csrf-token" id="authenticity-token" name="authenticity-token">
    <a id="attend_button"><span>save event</span></a>
""" + MINIMAL_EVENT_PAGE

/**
 * Client whose GETs serve an event page and whose POSTs (attend/unattend) respond with
 * [postStatus]; POST URLs are recorded in [postedUrls].
 */
private fun rsvpApiClient(
    postedUrls: MutableList<String>,
    postStatus: () -> HttpStatusCode = { HttpStatusCode.OK },
    pageHtml: () -> String = { EVENT_PAGE_WITH_TOKEN },
): RavelryApiClient {
    val engine = MockEngine { request ->
        if (request.method == HttpMethod.Post) {
            postedUrls.add(request.url.toString())
            respond("R.popover.close();", postStatus(),
                headersOf("Content-Type", "text/javascript"))
        } else {
            respond(pageHtml(), HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Text.Html.toString()))
        }
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, FakeFeedTokenStorage())
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventDetailViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    @Test
    fun `initial state is Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(routingApiClient { MINIMAL_EVENT_PAGE }, this)
        assertIs<EventDetailState.Loading>(vm.state.value)
    }

    @Test
    fun `load transitions to Loaded with the parsed event`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(routingApiClient { MINIMAL_EVENT_PAGE }, this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<EventDetailState.Loaded>(vm.state.value)
        assertEquals("Cozy Meetup", state.detail.title)
        assertEquals("Knitting/crochet group", state.detail.eventType)
    }

    @Test
    fun `a page that is not an event surfaces an error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(routingApiClient { "<html><body>gone</body></html>" }, this)
        vm.load("deleted-event")
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<EventDetailState.Error>(vm.state.value)
        assertEquals("This event page no longer exists.", state.message)
    }

    @Test
    fun `load transitions to Error when scraping fails`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(errorApiClient(), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Error>(vm.state.value)
    }

    @Test
    fun `session expiry emits the signal and stays in Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(sessionExpiredApiClient(), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        // Loading, not Error: the app is about to navigate to login, so an error
        // flash would be misleading (matches FeedViewModel/TopicDetailViewModel).
        assertIs<EventDetailState.Loading>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `load resets to Loading before each fetch`() = runTest(UnconfinedTestDispatcher()) {
        var respondEmpty = false
        val vm = EventDetailViewModel(
            routingApiClient { if (respondEmpty) "<html></html>" else MINIMAL_EVENT_PAGE },
            this,
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Loaded>(vm.state.value)

        respondEmpty = true
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Error>(vm.state.value)
    }
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventDetailViewModelToggleAttendanceTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    @Test
    fun `toggling posts to attend then unattend and flips state`() = runTest(UnconfinedTestDispatcher()) {
        val posted = mutableListOf<String>()
        val vm = EventDetailViewModel(rsvpApiClient(posted), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(true, assertIs<EventDetailState.Loaded>(vm.state.value).detail.attending)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(false, assertIs<EventDetailState.Loaded>(vm.state.value).detail.attending)

        assertEquals(
            listOf(
                "https://www.ravelry.com/events/cozy-meetup/attend?attending=1",
                "https://www.ravelry.com/events/cozy-meetup/unattend",
            ),
            posted,
        )
    }

    @Test
    fun `a rejected toggle reverts the optimistic flip`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(
            rsvpApiClient(mutableListOf(), postStatus = { HttpStatusCode.Forbidden }),
            this,
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(false, assertIs<EventDetailState.Loaded>(vm.state.value).detail.attending)
    }

    @Test
    fun `a rejected toggle does not clobber a newer load`() = runTest(UnconfinedTestDispatcher()) {
        // Park the attend POST until released, so its rejection arrives only after the
        // user has navigated to a different event.
        val releasePost = CompletableDeferred<Unit>()
        var pageTitle = "Cozy Meetup"
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                releasePost.await()
                respond("nope", HttpStatusCode.Forbidden, headersOf("Content-Type", "text/html"))
            } else {
                respond(EVENT_PAGE_WITH_TOKEN.replace("Cozy Meetup", pageTitle),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Text.Html.toString()))
            }
        }
        val client = RavelryApiClient(
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
            FakeFeedTokenStorage(),
        )
        val vm = EventDetailViewModel(client, this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()

        pageTitle = "Other Event"
        vm.load("other-event")
        releasePost.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)

        assertEquals("Other Event", assertIs<EventDetailState.Loaded>(vm.state.value).detail.title)
    }

    @Test
    fun `session expiry during toggle reverts and emits the signal`() = runTest(UnconfinedTestDispatcher()) {
        // Load with a working client, then swap behavior by loading state manually is not
        // possible — instead drive the whole flow against a client that throws on POST.
        val vm = EventDetailViewModel(sessionExpiredThenPageApiClient(), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Loaded>(vm.state.value)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(false, assertIs<EventDetailState.Loaded>(vm.state.value).detail.attending)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `toggle without a csrf token does not post`() = runTest(UnconfinedTestDispatcher()) {
        // MINIMAL_EVENT_PAGE has no authenticity-token meta.
        val vm = EventDetailViewModel(routingApiClient { MINIMAL_EVENT_PAGE }, this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(false, assertIs<EventDetailState.Loaded>(vm.state.value).detail.attending)
    }

    @Test
    fun `toggle before anything is loaded is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(rsvpApiClient(mutableListOf()), this)
        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Loading>(vm.state.value)
    }

    @Test
    fun `toggle in an error state is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val posted = mutableListOf<String>()
        val vm = EventDetailViewModel(rsvpApiClient(posted, pageHtml = { "<html>gone</html>" }), this)
        vm.load("deleted-event")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Error>(vm.state.value)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Error>(vm.state.value)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun `exception without a message falls back to a default error`() = runTest(UnconfinedTestDispatcher()) {
        val engine = MockEngine { throw RuntimeException() }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = EventDetailViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("Couldn't load event", assertIs<EventDetailState.Error>(vm.state.value).message)
    }
}

/** Serves the tokened event page on GET but throws [com.autom8ed.fibersocial.auth.SessionExpiredException] on POST. */
private fun sessionExpiredThenPageApiClient(): RavelryApiClient {
    val engine = MockEngine { request ->
        if (request.method == HttpMethod.Post) {
            throw com.autom8ed.fibersocial.auth.SessionExpiredException("Token expired")
        }
        respond(EVENT_PAGE_WITH_TOKEN, HttpStatusCode.OK,
            headersOf("Content-Type", ContentType.Text.Html.toString()))
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, FakeFeedTokenStorage())
}
