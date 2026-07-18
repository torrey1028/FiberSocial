package com.myhobbyislearning.fibersocial.events

import com.myhobbyislearning.fibersocial.feed.routingApiClient
import com.myhobbyislearning.fibersocial.notifications.NotificationState
import com.myhobbyislearning.fibersocial.notifications.NotificationStateStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

private class InMemoryNotificationStateStore(var state: NotificationState? = null) : NotificationStateStore {
    override suspend fun load(): NotificationState? = state
    override suspend fun save(state: NotificationState) { this.state = state }
}

/** Minimal parseable new-event form page (see NewEventFormParser). */
private val FORM_HTML = """
    <form id="new_event" action="https://www.ravelry.com/events" method="post">
    <input name="authenticity_token" type="hidden" value="TOK">
    <input id="event_creation_id" name="event[creation_id]" type="hidden" value="9">
    </form>
"""

/** A successful creation response: the venue-step edit form for the new permalink. */
private val CREATE_SUCCESS_HTML = """
    <form id="edit_event_1" action="/events/my-event" method="post">
    <input name="_method" type="hidden" value="put" />
    <input name="authenticity_token" type="hidden" value="TOK2" />
    <input id="step" name="step" type="hidden" value="venue" />
    </form>
"""

private val VENUE_REJECTED_HTML =
    """<ul class="brief_error_messages"><li>State can't be blank</li></ul>"""

private fun onlineInput() = NewEventInput(
    groupId = 1L,
    name = "Test",
    online = true,
    categoryId = 16L,
    startDate = "2026-07-20",
    startTime = "04:00 PM",
)

private fun inPersonInput() = NewEventInput(
    groupId = 1L,
    name = "Test",
    online = false,
    categoryId = 16L,
    startDate = "2026-07-20",
    startTime = "04:00 PM",
    countryId = 229L,
    city = "Redmond",
    venueName = "Venue",
    address = "1 Main St",
)

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NewEventViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    @Test
    fun `created event is pre-seeded into the notification sync's known events`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            if (path.endsWith("/events/new")) FORM_HTML else CREATE_SUCCESS_HTML
        }
        val store = InMemoryNotificationStateStore(NotificationState())
        val vm = NewEventViewModel(client, this, store, nowEpochMs = { 1234L })

        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.create(onlineInput())
        awaitChildren(coroutineContext[Job]!!)

        assertEquals("my-event", assertIs<NewEventState.Created>(vm.state.value).permalink)
        assertEquals(mapOf("my-event" to 1234L), assertNotNull(store.state).knownEvents)
    }

    @Test
    fun `event whose venue step was rejected is still created, warned about, and pre-seeded`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            when {
                path.endsWith("/events/new") -> FORM_HTML
                path.endsWith("/events") -> CREATE_SUCCESS_HTML
                else -> VENUE_REJECTED_HTML // the venue PUT to /events/my-event
            }
        }
        val store = InMemoryNotificationStateStore(NotificationState())
        val vm = NewEventViewModel(client, this, store, nowEpochMs = { 1234L })

        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.create(inPersonInput())
        awaitChildren(coroutineContext[Job]!!)

        val created = assertIs<NewEventState.Created>(vm.state.value)
        assertEquals("my-event", created.permalink)
        assertNotNull(created.venueWarning)
        assertEquals(mapOf("my-event" to 1234L), assertNotNull(store.state).knownEvents)
    }

    @Test
    fun `loadStates fetches per country and serves repeat picks from the cache`() = runTest(UnconfinedTestDispatcher()) {
        var statesFetches = 0
        val client = routingApiClient { path ->
            when {
                path.endsWith("/events/new") -> FORM_HTML
                path.endsWith("/locations/states") -> {
                    statesFetches++
                    """Element.update("state_options", "<option value=\"3651\">Washington</option>");"""
                }
                else -> CREATE_SUCCESS_HTML
            }
        }
        val vm = NewEventViewModel(client, this)

        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadStates(229L)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(listOf(EventState(3651L, "Washington")), vm.states.value)

        vm.loadStates(229L)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(1, statesFetches)
        assertEquals(listOf(EventState(3651L, "Washington")), vm.states.value)
    }

    @Test
    fun `loadStates failure degrades to an empty list`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            when {
                path.endsWith("/events/new") -> FORM_HTML
                path.endsWith("/locations/states") -> throw RuntimeException("network unreachable")
                else -> CREATE_SUCCESS_HTML
            }
        }
        val vm = NewEventViewModel(client, this)

        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadStates(229L)
        awaitChildren(coroutineContext[Job]!!)

        assertEquals(emptyList(), vm.states.value)
    }

    @Test
    fun `loadStates before the form context is loaded is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        // The states endpoint needs the form's CSRF token, so there is nothing to send yet.
        val vm = NewEventViewModel(routingApiClient { FORM_HTML }, this)
        vm.loadStates(229L)
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(emptyList(), vm.states.value)
        assertIs<NewEventState.Loading>(vm.state.value)
    }

    @Test
    fun `reset returns the composer to Loading and clears loaded states`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            when {
                path.endsWith("/events/new") -> FORM_HTML
                path.endsWith("/locations/states") ->
                    """Element.update("s", "<option value=\"3651\">Washington</option>");"""
                else -> CREATE_SUCCESS_HTML
            }
        }
        val vm = NewEventViewModel(client, this)
        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.loadStates(229L)
        awaitChildren(coroutineContext[Job]!!)

        vm.reset()

        assertIs<NewEventState.Loading>(vm.state.value)
        assertEquals(emptyList(), vm.states.value)
    }

    @Test
    fun `loadForm failure lands in LoadError`() = runTest(UnconfinedTestDispatcher()) {
        val vm = NewEventViewModel(routingApiClient { "<html><body>not the form</body></html>" }, this)
        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        assertIs<NewEventState.LoadError>(vm.state.value)
    }

    @Test
    fun `rejected creation surfaces the validation message and stays on the form`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            if (path.endsWith("/events/new")) {
                FORM_HTML
            } else {
                """<ul class="brief_error_messages"><li>City is required</li></ul>"""
            }
        }
        val vm = NewEventViewModel(client, this)
        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.create(onlineInput())
        awaitChildren(coroutineContext[Job]!!)

        val ready = assertIs<NewEventState.Ready>(vm.state.value)
        assertEquals("City is required", ready.error)
        assertEquals(false, ready.sending)
    }

    @Test
    fun `creation succeeds without a notification store configured`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            if (path.endsWith("/events/new")) FORM_HTML else CREATE_SUCCESS_HTML
        }
        val vm = NewEventViewModel(client, this)
        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.create(onlineInput())
        awaitChildren(coroutineContext[Job]!!)
        assertIs<NewEventState.Created>(vm.state.value)
    }

    @Test
    fun `a failing notification store never blocks the creation`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            if (path.endsWith("/events/new")) FORM_HTML else CREATE_SUCCESS_HTML
        }
        val store = object : NotificationStateStore {
            override suspend fun load(): NotificationState = NotificationState()
            override suspend fun save(state: NotificationState) = throw RuntimeException("disk full")
        }
        val vm = NewEventViewModel(client, this, store)
        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.create(onlineInput())
        awaitChildren(coroutineContext[Job]!!)
        assertIs<NewEventState.Created>(vm.state.value)
    }

    @Test
    fun `pre-seeding is skipped before the first-ever sync`() = runTest(UnconfinedTestDispatcher()) {
        // A null stored state means no sync has ever completed; that first sync treats
        // everything as known, and fabricating a state here would masquerade as one.
        val client = routingApiClient { path ->
            if (path.endsWith("/events/new")) FORM_HTML else CREATE_SUCCESS_HTML
        }
        val store = InMemoryNotificationStateStore(state = null)
        val vm = NewEventViewModel(client, this, store, nowEpochMs = { 1234L })

        vm.loadForm(1L)
        awaitChildren(coroutineContext[Job]!!)
        vm.create(onlineInput())
        awaitChildren(coroutineContext[Job]!!)

        assertIs<NewEventState.Created>(vm.state.value)
        assertNull(store.state)
    }
}
