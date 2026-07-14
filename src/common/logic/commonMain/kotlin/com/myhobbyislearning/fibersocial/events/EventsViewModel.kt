package com.myhobbyislearning.fibersocial.events

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.auth.SessionExpirySignal
import com.myhobbyislearning.fibersocial.feed.RavelryApiClient
import com.myhobbyislearning.fibersocial.feed.models.Group
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val sessionExpirySignal = SessionExpirySignal()

    /** Observable events state. */
    val state: StateFlow<EventsState> = _state.asStateFlow()

    /** @see SessionExpirySignal.flow */
    val sessionExpired: Flow<Unit> = sessionExpirySignal.flow

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
                println("FiberSocial: EventsViewModel.load session expired")
                sessionExpirySignal.signal()
                EventsState.Loading
            } catch (e: Exception) {
                println("FiberSocial: EventsViewModel.load failed: ${e.message}")
                EventsState.Error(e.message ?: "Couldn't load events")
            }
        }
    }

    /**
     * Locally adjusts the "N going" count for [permalink] after an RSVP change made
     * elsewhere in the app: +1 when the user now attends, -1 when they backed out.
     * The count came from a one-time scrape of the group pages, so without this the
     * list keeps showing the pre-RSVP number until a full reload. No-op while the
     * list isn't loaded or doesn't contain the event.
     */
    fun applyAttendanceChange(permalink: String, attending: Boolean) {
        val loaded = _state.value as? EventsState.Loaded ?: return
        val delta = if (attending) 1 else -1
        _state.value = EventsState.Loaded(
            loaded.events.map { groupEvent ->
                if (groupEvent.event.permalink != permalink) groupEvent
                else groupEvent.copy(
                    event = groupEvent.event.copy(
                        attendeeCount = (groupEvent.event.attendeeCount + delta).coerceAtLeast(0),
                    ),
                )
            },
        )
    }
}
