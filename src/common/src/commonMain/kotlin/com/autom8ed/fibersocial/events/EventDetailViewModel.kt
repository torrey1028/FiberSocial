package com.autom8ed.fibersocial.events

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.RavelryApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Represents the event detail screen state emitted by [EventDetailViewModel]. */
sealed class EventDetailState {
    /** The event page is being scraped. */
    data object Loading : EventDetailState()

    /** Event details loaded. */
    data class Loaded(val detail: EventDetail) : EventDetailState()

    /**
     * Loading failed (including a permalink that no longer resolves to an event page).
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : EventDetailState()
}

/**
 * Loads and exposes the details of a single event.
 *
 * @param apiClient Used to scrape the event page.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class EventDetailViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<EventDetailState>(EventDetailState.Loading)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    private val _attendees = MutableStateFlow<List<EventAttendee>?>(null)

    /** Observable event detail state. */
    val state: StateFlow<EventDetailState> = _state.asStateFlow()

    /**
     * People attending the loaded event, scraped from the people page in parallel with
     * [load] and refreshed after a successful RSVP toggle. `null` while loading;
     * attendees are decorative, so a failed scrape degrades to an empty list.
     */
    val attendees: StateFlow<List<EventAttendee>?> = _attendees.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /** Permalink of the currently loaded (or loading) event; used by [toggleAttendance]. */
    private var loadedPermalink: String? = null

    /** Scrapes the event page for [eventPermalink], replacing any previous state. */
    fun load(eventPermalink: String) {
        loadedPermalink = eventPermalink
        // Synchronously, not inside the coroutine: navigating to another event must not
        // flash the previous event's detail while the launch waits its turn.
        _state.value = EventDetailState.Loading
        _attendees.value = null
        scope.launch { refreshAttendees(eventPermalink) }
        scope.launch {
            println("FiberSocial: EventDetailViewModel.load($eventPermalink)")
            _state.value = try {
                val detail = apiClient.getEvent(eventPermalink)
                if (detail == null) {
                    EventDetailState.Error("This event page no longer exists.")
                } else {
                    EventDetailState.Loaded(detail)
                }
            } catch (e: SessionExpiredException) {
                println("FiberSocial: EventDetailViewModel.load session expired")
                _sessionExpired.trySend(Unit)
                EventDetailState.Loading
            } catch (e: Exception) {
                println("FiberSocial: EventDetailViewModel.load failed: ${e.message}")
                EventDetailState.Error(e.message ?: "Couldn't load event")
            }
        }
    }

    /**
     * Saves or un-saves the loaded event with an optimistic update: the button state
     * flips immediately and reverts if the site rejects the change. No-ops when no
     * event is loaded or the page carried no authenticity token.
     */
    fun toggleAttendance() {
        val permalink = loadedPermalink ?: return
        val current = _state.value as? EventDetailState.Loaded ?: return
        val token = current.detail.csrfToken
        if (token == null) {
            println("FiberSocial: EventDetailViewModel.toggleAttendance no csrf token — skipping")
            return
        }
        val original = current.detail
        val target = !original.attending
        val optimistic = EventDetailState.Loaded(original.copy(attending = target))
        _state.value = optimistic

        scope.launch {
            val accepted = try {
                apiClient.setEventAttendance(permalink, attending = target, csrfToken = token)
            } catch (e: SessionExpiredException) {
                println("FiberSocial: EventDetailViewModel.toggleAttendance session expired")
                _sessionExpired.trySend(Unit)
                false
            } catch (e: Exception) {
                println("FiberSocial: EventDetailViewModel.toggleAttendance error: ${e.message}")
                false
            }
            if (!accepted) {
                // Restore the pre-toggle snapshot — but only if this toggle's optimistic
                // state is still showing. If a newer load() or toggle has replaced it,
                // reverting would clobber that newer state with a stale event.
                _state.compareAndSet(optimistic, EventDetailState.Loaded(original))
            } else {
                // The user just joined or left; the people page is the source of truth.
                refreshAttendees(permalink)
            }
        }
    }

    private suspend fun refreshAttendees(eventPermalink: String) {
        val result = try {
            apiClient.getEventAttendees(eventPermalink)
        } catch (e: SessionExpiredException) {
            _sessionExpired.trySend(Unit)
            emptyList()
        } catch (e: Exception) {
            println("FiberSocial: EventDetailViewModel attendees failed: ${e.message}")
            emptyList()
        }
        // Publish only if this event is still the one on screen: an older fetch
        // resolving after a newer load() must not overwrite the newer list (or
        // fill in a list for an event the user already navigated away from).
        if (loadedPermalink == eventPermalink) {
            _attendees.value = result
        }
    }
}
