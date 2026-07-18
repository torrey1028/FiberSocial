package com.myhobbyislearning.fibersocial.events

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.auth.SessionExpirySignal
import com.myhobbyislearning.fibersocial.feed.RavelryApiClient
import com.myhobbyislearning.fibersocial.notifications.NotificationStateStore
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** State of the new-event composer, driven by [NewEventViewModel]. */
sealed class NewEventState {
    /** The form's dropdown option lists (and CSRF token) are being fetched. */
    data object Loading : NewEventState()

    /**
     * Form context loaded; the composer is usable.
     * @property form Dropdown options plus the CSRF token/draft ID [NewEventViewModel.create] needs.
     * @property sending Whether a submission is in flight — fields stay editable-looking but disabled.
     * @property error A rejected submission's message, e.g. "City is required". Cleared on retry.
     */
    data class Ready(
        val form: NewEventForm,
        val sending: Boolean = false,
        val error: String? = null,
    ) : NewEventState()

    /**
     * Event created; UI should navigate into it.
     * @property permalink The new event's slug.
     * @property venueWarning Set when the event exists but its venue step was rejected
     *   ([EventVenueException]) — worth showing before navigating, because without a venue
     *   the event won't appear in the group's upcoming-events list. Deliberately not an
     *   [Ready.error]: re-submitting the form would create a duplicate event.
     */
    data class Created(val permalink: String, val venueWarning: String? = null) : NewEventState()

    /** Couldn't load the form context at all (e.g. no longer a moderator). */
    data class LoadError(val message: String) : NewEventState()
}

/**
 * Drives the new-event composer: loads the "New Event" form context for a group (a
 * moderator-only page — see [RavelryApiClient.getNewEventForm]), then submits it via
 * [RavelryApiClient.createEvent].
 *
 * @param apiClient Used to load the form context, look up states for a chosen country,
 *   and create the event.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 * @param notificationStateStore The event-notification sync's state store, used to
 *   pre-seed a just-created event as already-known so the next sync doesn't pop a
 *   "new event" notification at the very person who created it. Optional: without it
 *   creation still works, at the cost of that redundant notification.
 * @param nowEpochMs Clock for the known-event seen-timestamp; injectable for tests.
 */
class NewEventViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
    private val notificationStateStore: NotificationStateStore? = null,
    private val nowEpochMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow<NewEventState>(NewEventState.Loading)
    private val sessionExpirySignal = SessionExpirySignal()

    /** Observable composer state. */
    val state: StateFlow<NewEventState> = _state.asStateFlow()

    /** @see SessionExpirySignal.flow */
    val sessionExpired: Flow<Unit> = sessionExpirySignal.flow

    private val stateCache = mutableMapOf<Long, List<EventState>>()
    private val _states = MutableStateFlow<List<EventState>>(emptyList())

    /**
     * State/region options for the country most recently passed to [loadStates], for the
     * in-person location fields. Empty until a country with states is chosen.
     */
    val states: StateFlow<List<EventState>> = _states.asStateFlow()

    /** Loads the form context for [groupId]'s "New Event" form, replacing any previous state. */
    fun loadForm(groupId: Long) {
        _state.value = NewEventState.Loading
        _states.value = emptyList()
        scope.launch {
            println("FiberSocial: NewEventViewModel.loadForm($groupId)")
            _state.value = try {
                NewEventState.Ready(apiClient.getNewEventForm(groupId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: NewEventViewModel.loadForm session expired")
                sessionExpirySignal.signal()
                NewEventState.Loading
            } catch (e: Exception) {
                println("FiberSocial: NewEventViewModel.loadForm failed: ${e.message}")
                NewEventState.LoadError(e.message ?: "Couldn't load the new event form")
            }
        }
    }

    /**
     * Fetches the state/region options for [countryId], caching by country so re-picking
     * a previously chosen country doesn't re-fetch. A failed lookup degrades to an empty
     * list — states are an optional refinement of [NewEventInput.city]/[NewEventInput.countryId],
     * never worth blocking submission over.
     */
    fun loadStates(countryId: Long) {
        val cached = stateCache[countryId]
        if (cached != null) {
            _states.value = cached
            return
        }
        // The states endpoint needs the form's CSRF token (see getStatesForCountry);
        // states are only loadable once the form context is.
        val form = (_state.value as? NewEventState.Ready)?.form ?: return
        scope.launch {
            _states.value = try {
                apiClient.getStatesForCountry(countryId, form.authenticityToken)
                    .also { stateCache[countryId] = it }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("FiberSocial: NewEventViewModel.loadStates($countryId) failed: ${e.message}")
                emptyList()
            }
        }
    }

    /** Submits [input] using the currently loaded form context. No-op if the form isn't loaded or a submission is already in flight. */
    fun create(input: NewEventInput) {
        val ready = _state.value as? NewEventState.Ready ?: return
        if (ready.sending) return
        _state.value = ready.copy(sending = true, error = null)
        scope.launch {
            _state.value = try {
                val permalink = apiClient.createEvent(ready.form, input)
                println("FiberSocial: NewEventViewModel created event $permalink")
                markEventKnown(permalink)
                NewEventState.Created(permalink)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: NewEventViewModel.create session expired")
                sessionExpirySignal.signal()
                ready.copy(sending = false)
            } catch (e: EventVenueException) {
                // The event exists — landing back on the form would invite a duplicate
                // submission, so navigate into it but carry the warning along.
                println("FiberSocial: NewEventViewModel.create venue step failed: ${e.message}")
                markEventKnown(e.permalink)
                NewEventState.Created(e.permalink, venueWarning = e.message)
            } catch (e: Exception) {
                println("FiberSocial: NewEventViewModel.create failed: ${e.message}")
                ready.copy(sending = false, error = e.message ?: "Failed to create event")
            }
        }
    }

    /**
     * Records a just-created event in the notification sync's known-events map so the
     * next sync doesn't notify this user about their own event. Skipped before the
     * first-ever sync (null stored state — that sync treats everything as known and
     * doesn't notify anyway, and fabricating a state here would masquerade as a
     * completed sync). Best-effort: a failure costs one redundant notification, never
     * the creation itself.
     */
    private suspend fun markEventKnown(permalink: String) {
        val store = notificationStateStore ?: return
        try {
            val state = store.load() ?: return
            store.save(state.copy(knownEvents = state.knownEvents + (permalink to nowEpochMs())))
            println("FiberSocial: NewEventViewModel marked $permalink as known to notification sync")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("FiberSocial: NewEventViewModel.markEventKnown($permalink) failed: ${e.message}")
        }
    }

    /**
     * Resets to [NewEventState.Loading] when the composer closes, so the next open's
     * (fresh [loadForm] call) result isn't briefly preceded by a stale [NewEventState.Created]
     * or [NewEventState.LoadError] flash. No-op mid-send — the in-flight result still
     * needs somewhere to land.
     */
    fun reset() {
        val current = _state.value
        if (current is NewEventState.Ready && current.sending) return
        _state.value = NewEventState.Loading
        _states.value = emptyList()
    }
}
