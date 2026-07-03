package com.autom8ed.fibersocial.events

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.feed.models.Group
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** An upcoming event paired with the group whose page listed it. */
data class GroupEvent(val group: Group, val event: EventSummary)

/** Represents the events screen state emitted by [EventsViewModel]. */
sealed class EventsState {
    /** Events are being scraped from the group pages. */
    data object Loading : EventsState()

    /**
     * Events loaded across all groups.
     * @property events Soonest first; events with unparseable dates sort last.
     */
    data class Loaded(val events: List<GroupEvent>) : EventsState()

    /**
     * Loading failed.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : EventsState()
}

/**
 * Platform-agnostic ViewModel that drives the events screen.
 *
 * Aggregates the "upcoming events" boxes of the given groups (scraped in parallel via
 * [RavelryApiClient.getGroupEvents]) into one chronological list.
 *
 * @param apiClient Used to scrape group pages.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class EventsViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<EventsState>(EventsState.Loading)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable events state. */
    val state: StateFlow<EventsState> = _state.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /**
     * Scrapes upcoming events for every group in [groups], replacing any previous state.
     * The caller supplies the groups (the feed has already resolved them).
     */
    fun load(groups: List<Group>) {
        scope.launch {
            println("FiberSocial: EventsViewModel.load(${groups.size} groups)")
            _state.value = EventsState.Loading
            _state.value = try {
                val perGroup = coroutineScope {
                    groups.map { group ->
                        async { group to apiClient.getGroupEvents(group.permalink) }
                    }.awaitAll()
                }
                val events = perGroup
                    .flatMap { (group, events) -> events.map { GroupEvent(group, it) } }
                    .distinctBy { it.event.permalink }
                    .sortedWith(compareBy(nullsLast()) { it.event.startsAt })
                println("FiberSocial: EventsViewModel loaded ${events.size} events")
                EventsState.Loaded(events)
            } catch (e: SessionExpiredException) {
                _sessionExpired.trySend(Unit)
                EventsState.Error("Session expired")
            } catch (e: Exception) {
                println("FiberSocial: EventsViewModel.load failed: ${e.message}")
                EventsState.Error(e.message ?: "Couldn't load events")
            }
        }
    }
}
