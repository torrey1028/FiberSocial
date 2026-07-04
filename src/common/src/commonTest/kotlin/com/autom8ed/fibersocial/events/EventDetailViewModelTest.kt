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
import kotlinx.coroutines.withTimeoutOrNull
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

private fun peoplePage(vararg usernames: String) = buildString {
    append("""<div class="event__user_cards">""")
    usernames.forEach { name ->
        append("""<div class="user_card"><div class="details"><a class="login" href="/people/$name">$name</a></div></div>""")
    }
    append("</div>")
}

/**
 * Client whose GETs serve an event page (or a people page for `…/people` paths) and
 * whose POSTs (attend/unattend) respond with [postStatus]; POST URLs are recorded in
 * [postedUrls].
 */
private fun rsvpApiClient(
    postedUrls: MutableList<String>,
    postStatus: () -> HttpStatusCode = { HttpStatusCode.OK },
    pageHtml: () -> String = { EVENT_PAGE_WITH_TOKEN },
    peopleHtml: () -> String = { peoplePage("Megannnnn") },
): RavelryApiClient {
    val engine = MockEngine { request ->
        if (request.method == HttpMethod.Post) {
            postedUrls.add(request.url.toString())
            respond("R.popover.close();", postStatus(),
                headersOf("Content-Type", "text/javascript"))
        } else {
            val body = if (request.url.encodedPath.endsWith("/people")) peopleHtml() else pageHtml()
            respond(body, HttpStatusCode.OK,
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
    fun `an accepted toggle emits an attendance change with the new state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(rsvpApiClient(mutableListOf()), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(AttendanceChange("cozy-meetup", attending = true), vm.attendanceChanged.first())

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(AttendanceChange("cozy-meetup", attending = false), vm.attendanceChanged.first())
    }

    @Test
    fun `a rejected toggle emits no attendance change`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(
            rsvpApiClient(mutableListOf(), postStatus = { HttpStatusCode.Forbidden }),
            this,
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(null, withTimeoutOrNull(1_000) { vm.attendanceChanged.first() })
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
    fun `a successful toggle invokes onAttendanceChanged`() = runTest(UnconfinedTestDispatcher()) {
        var changes = 0
        val vm = EventDetailViewModel(rsvpApiClient(mutableListOf()), this, onAttendanceChanged = { changes++ })
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, changes)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(2, changes)
    }

    @Test
    fun `a rejected toggle does not invoke onAttendanceChanged`() = runTest(UnconfinedTestDispatcher()) {
        var changes = 0
        val vm = EventDetailViewModel(
            rsvpApiClient(mutableListOf(), postStatus = { HttpStatusCode.Forbidden }),
            this,
            onAttendanceChanged = { changes++ },
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(0, changes)
    }

    @Test
    fun `a toggle without a csrf token does not invoke onAttendanceChanged`() = runTest(UnconfinedTestDispatcher()) {
        var changes = 0
        val vm = EventDetailViewModel(
            routingApiClient { MINIMAL_EVENT_PAGE },
            this,
            onAttendanceChanged = { changes++ },
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)

        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(0, changes)
    }

    @Test
    fun `toggle before anything is loaded is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(rsvpApiClient(mutableListOf()), this)
        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Loading>(vm.state.value)
    }

    @Test
    fun `load also fetches the attendee list`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(rsvpApiClient(mutableListOf()), this)
        assertEquals(null, vm.attendees.value)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(listOf("Megannnnn"), vm.attendees.value?.map { it.username })
    }

    @Test
    fun `a successful toggle refreshes the attendee list`() = runTest(UnconfinedTestDispatcher()) {
        var people = peoplePage("Megannnnn")
        val vm = EventDetailViewModel(
            rsvpApiClient(mutableListOf(), peopleHtml = { people }),
            this,
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, vm.attendees.value?.size)

        people = peoplePage("Megannnnn", "torrey1028")
        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(listOf("Megannnnn", "torrey1028"), vm.attendees.value?.map { it.username })
    }

    @Test
    fun `a failed refresh keeps the last good attendee list`() = runTest(UnconfinedTestDispatcher()) {
        var failPeople = false
        val vm = EventDetailViewModel(
            rsvpApiClient(mutableListOf(), peopleHtml = {
                if (failPeople) error("transient network error") else peoplePage("Megannnnn")
            }),
            this,
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, vm.attendees.value?.size)

        failPeople = true
        vm.toggleAttendance()
        awaitChildren(coroutineContext[Job]!!)
        // The RSVP succeeded but the follow-up scrape failed: the populated list
        // must survive rather than the whole "Going" section vanishing.
        assertEquals(listOf("Megannnnn"), vm.attendees.value?.map { it.username })
    }

    @Test
    fun `a failed refresh via reload of the same event keeps the last good attendee list`() = runTest(UnconfinedTestDispatcher()) {
        // Regression: load() used to null out attendees unconditionally before the
        // refresh even started, so refreshAttendees()'s own "keep the last good list"
        // fallback always read null and degraded to empty — exactly what pull-to-refresh
        // triggers (a second load() of the same event), silently emptying the list.
        var failPeople = false
        val vm = EventDetailViewModel(
            rsvpApiClient(mutableListOf(), peopleHtml = {
                if (failPeople) error("transient network error") else peoplePage("Megannnnn")
            }),
            this,
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, vm.attendees.value?.size)

        failPeople = true
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(listOf("Megannnnn"), vm.attendees.value?.map { it.username })
    }

    @Test
    fun `loading a different event does not carry over the previous event's attendee list on failure`() = runTest(UnconfinedTestDispatcher()) {
        var currentEvent = "cozy-meetup"
        val vm = EventDetailViewModel(
            rsvpApiClient(mutableListOf(), peopleHtml = {
                if (currentEvent == "cozy-meetup") peoplePage("Megannnnn") else error("transient network error")
            }),
            this,
        )
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, vm.attendees.value?.size)

        currentEvent = "other-event"
        vm.load("other-event")
        awaitChildren(coroutineContext[Job]!!)
        // The new event's own attendee fetch failed — this must not resolve to
        // "cozy-meetup"'s people just because that's what _attendees.value held before.
        assertTrue(vm.attendees.value.isNullOrEmpty())
    }

    @Test
    fun `a stale attendee fetch does not overwrite a newer one`() = runTest(UnconfinedTestDispatcher()) {
        // Park the load-time people fetch (a pre-toggle snapshot) until released,
        // so it resolves only after the post-toggle refresh. firstFetchStarted
        // guarantees the parked request really is the load-time one: the test
        // waits for it to reach the engine before toggling.
        val firstFetchStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var peopleCalls = 0
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Post) {
                respond("R.popover.close();", HttpStatusCode.OK,
                    headersOf("Content-Type", "text/javascript"))
            } else if (request.url.encodedPath.endsWith("/people")) {
                peopleCalls++
                if (peopleCalls == 1) {
                    firstFetchStarted.complete(Unit)
                    releaseFirst.await()
                    respond(peoplePage("Megannnnn"), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Text.Html.toString()))
                } else {
                    respond(peoplePage("Megannnnn", "torrey1028"), HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Text.Html.toString()))
                }
            } else {
                respond(EVENT_PAGE_WITH_TOKEN, HttpStatusCode.OK,
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
        firstFetchStarted.await()
        // The event page loads independently of the parked people fetch; toggling
        // is a no-op until the state is Loaded.
        vm.state.first { it is EventDetailState.Loaded }

        vm.toggleAttendance()
        // Wait for the post-toggle refresh to publish, THEN release the stale
        // fetch — the guard is now the only thing preventing the overwrite.
        vm.attendees.first { it?.size == 2 }
        releaseFirst.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)

        // The post-toggle refresh (fetch #2) is newer; the parked load-time fetch
        // (#1) resolving late must not revert the list to the pre-toggle snapshot.
        assertEquals(listOf("Megannnnn", "torrey1028"), vm.attendees.value?.map { it.username })
    }

    @Test
    fun `session expiry emits the signal once, not once per parallel fetch`() = runTest(UnconfinedTestDispatcher()) {
        // load() fires two parallel scrapes (event page + people page); if both
        // emitted, the second buffered Unit would instantly log out the next session.
        val vm = EventDetailViewModel(sessionExpiredApiClient(), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(Unit, vm.sessionExpired.first())
        assertEquals(null, withTimeoutOrNull(1_000) { vm.sessionExpired.first() })
    }

    @Test
    fun `a failed attendee scrape degrades to an empty list`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(errorApiClient(), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(emptyList(), vm.attendees.value)
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
