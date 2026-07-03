package com.autom8ed.fibersocial.events

import com.autom8ed.fibersocial.feed.errorApiClient
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.routingApiClient
import com.autom8ed.fibersocial.feed.sessionExpiredApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDateTime

private fun group(permalink: String, name: String = permalink) =
    Group(id = permalink.hashCode().toLong(), name = name, permalink = permalink, forumId = 1L)

private fun eventBox(vararg events: Triple<String, String, String>) = buildString {
    append("""<div id="upcoming_events"><div id="events">""")
    events.forEach { (slug, title, whenText) ->
        append(
            """<div class="event">
               <div class="what"><a href="https://www.ravelry.com/events/$slug">$title</a></div>
               <div class="when">$whenText</div>
               <div class="who"><a href="https://www.ravelry.com/events/$slug/people">1 person</a></div>
               </div>"""
        )
    }
    append("</div></div>")
}

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class EventsViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    @Test
    fun `initial state is Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventsViewModel(routingApiClient { eventBox() }, this)
        assertIs<EventsState.Loading>(vm.state.value)
    }

    @Test
    fun `load merges events across groups sorted by start time`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            when {
                path.contains("knitters") -> eventBox(
                    Triple("late-event", "Late", "August 1, 2026 @ 1:00 PM"),
                )
                else -> eventBox(
                    Triple("early-event", "Early", "July 3, 2026 @ 9:00 AM"),
                )
            }
        }
        val vm = EventsViewModel(client, this)
        vm.load(listOf(group("knitters"), group("spinners")))
        awaitChildren(coroutineContext[Job]!!)

        val state = assertIs<EventsState.Loaded>(vm.state.value)
        assertEquals(listOf("early-event", "late-event"), state.events.map { it.event.permalink })
        assertEquals("spinners", state.events[0].group.permalink)
        assertEquals(LocalDateTime(2026, 7, 3, 9, 0), state.events[0].event.startsAt)
    }

    @Test
    fun `events with unparseable dates sort last`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient {
            eventBox(
                Triple("festival", "Fiber Festival", "October 3-5, 2026"),
                Triple("meetup", "Meetup", "July 3, 2026 @ 9:00 AM"),
            )
        }
        val vm = EventsViewModel(client, this)
        vm.load(listOf(group("g")))
        awaitChildren(coroutineContext[Job]!!)

        val state = assertIs<EventsState.Loaded>(vm.state.value)
        assertEquals(listOf("meetup", "festival"), state.events.map { it.event.permalink })
    }

    @Test
    fun `an event listed by two groups appears once`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient {
            eventBox(Triple("shared-event", "Shared", "July 3, 2026 @ 9:00 AM"))
        }
        val vm = EventsViewModel(client, this)
        vm.load(listOf(group("a"), group("b")))
        awaitChildren(coroutineContext[Job]!!)

        val state = assertIs<EventsState.Loaded>(vm.state.value)
        assertEquals(1, state.events.size)
    }

    @Test
    fun `load with no groups yields an empty Loaded state`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventsViewModel(routingApiClient { eventBox() }, this)
        vm.load(emptyList())
        awaitChildren(coroutineContext[Job]!!)
        assertTrue(assertIs<EventsState.Loaded>(vm.state.value).events.isEmpty())
    }

    @Test
    fun `load transitions to Error when scraping fails`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventsViewModel(errorApiClient(), this)
        vm.load(listOf(group("g")))
        awaitChildren(coroutineContext[Job]!!)
        assertIs<EventsState.Error>(vm.state.value)
    }

    @Test
    fun `session expiry emits the signal and stays in Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = EventsViewModel(sessionExpiredApiClient(), this)
        vm.load(listOf(group("g")))
        awaitChildren(coroutineContext[Job]!!)
        // Loading, not Error: the app is about to navigate to login, so an error
        // flash would be misleading (matches FeedViewModel/TopicDetailViewModel).
        assertIs<EventsState.Loading>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }
}
