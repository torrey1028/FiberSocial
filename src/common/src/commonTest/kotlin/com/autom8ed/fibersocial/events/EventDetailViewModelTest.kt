package com.autom8ed.fibersocial.events

import com.autom8ed.fibersocial.feed.errorApiClient
import com.autom8ed.fibersocial.feed.routingApiClient
import com.autom8ed.fibersocial.feed.sessionExpiredApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

private val MINIMAL_EVENT_PAGE = """
    <div class="page_title">Cozy Meetup</div>
    <div class="event__detail">
    <div class="event__type">Knitting/crochet group</div>
    <div class="event__dates">July  9, 2026 @ 6:00 PM</div>
    </div>
"""

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
    fun `session expiry emits the signal and surfaces an error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventDetailViewModel(sessionExpiredApiClient(), this)
        vm.load("cozy-meetup")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventDetailState.Error>(vm.state.value)
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
