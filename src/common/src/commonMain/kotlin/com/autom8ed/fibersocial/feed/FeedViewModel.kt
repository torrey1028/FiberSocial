package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.RavelryUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Represents the group feed screen state emitted by [FeedViewModel].
 */
sealed class FeedState {
    /** Initial load in progress; no data is available yet. */
    object Loading : FeedState()

    /**
     * Feed data is available and up to date.
     *
     * @property user The authenticated Ravelry user.
     * @property groups All groups the user is a member of (used to populate the filter drawer).
     * @property selectedGroup The group currently being filtered to, or `null` for "All Groups".
     * @property items Feed items for the selected group(s), sorted newest-reply-first.
     */
    data class Loaded(
        val user: RavelryUser,
        val groups: List<Group>,
        val selectedGroup: Group?,
        val items: List<FeedItem>,
    ) : FeedState()

    /**
     * A refresh is in progress; [stale] holds the last known good data for display.
     *
     * @property stale The previously loaded state shown while new data is fetched.
     */
    data class Refreshing(val stale: Loaded) : FeedState()

    /**
     * A load or refresh failed.
     *
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : FeedState()
}

/**
 * Platform-agnostic ViewModel that drives the group feed screen.
 *
 * Exposes a [state] flow consumed by platform ViewModels (e.g. `FeedAndroidViewModel`).
 *
 * @param repository Feed data source.
 * @param scope Coroutine scope tied to the ViewModel's lifecycle.
 */
class FeedViewModel(
    private val repository: FeedRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<FeedState>(FeedState.Loading)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable feed state. */
    val state: StateFlow<FeedState> = _state.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /** Sets the session-expired signal. Call only from platform debug tooling. */
    fun forceSessionExpiry() { _sessionExpired.trySend(Unit) }

    /** Performs an initial full load: user → groups → feed items. */
    fun load() {
        scope.launch {
            println("FiberSocial: FeedViewModel.load() starting")
            _state.value = FeedState.Loading
            _state.value = fetchFeed(selectedGroup = null)
            println("FiberSocial: FeedViewModel.load() -> ${_state.value::class.simpleName}")
        }
    }

    /**
     * Reloads the feed while keeping the current group filter.
     * No-ops if the feed is not in [FeedState.Loaded].
     */
    fun refresh() {
        val current = _state.value as? FeedState.Loaded ?: return
        scope.launch {
            println("FiberSocial: FeedViewModel.refresh()")
            _state.value = FeedState.Refreshing(current)
            _state.value = fetchFeed(selectedGroup = current.selectedGroup)
            println("FiberSocial: FeedViewModel.refresh() -> ${_state.value::class.simpleName}")
        }
    }

    /**
     * Filters the feed to [group], or clears the filter when [group] is `null`.
     * No-ops if the feed is not in [FeedState.Loaded].
     *
     * @param group The group to show, or `null` to show all groups.
     */
    fun selectGroup(group: Group?) {
        val current = _state.value as? FeedState.Loaded ?: return
        println("FiberSocial: FeedViewModel.selectGroup(${group?.name ?: "All Groups"})")
        scope.launch {
            _state.value = FeedState.Refreshing(current)
            val groups = current.groups
            val filtered = if (group == null) groups else listOf(group)
            _state.value = try {
                val items = repository.getFeedItems(filtered)
                println("FiberSocial: selectGroup loaded ${items.size} items")
                FeedState.Loaded(
                    user = current.user,
                    groups = groups,
                    selectedGroup = group,
                    items = items,
                )
            } catch (e: SessionExpiredException) {
                println("FiberSocial: selectGroup session expired")
                _sessionExpired.trySend(Unit)
                current
            } catch (e: Exception) {
                println("FiberSocial: selectGroup error: ${e.message}")
                current
            }
        }
    }

    private suspend fun fetchFeed(selectedGroup: Group?): FeedState = try {
        val user = repository.getCurrentUser()
        println("FiberSocial: fetched user=${user.username}")
        val groups = repository.getUserGroups(user.username)
        println("FiberSocial: fetched ${groups.size} groups: ${groups.map { it.name }}")
        val filtered = if (selectedGroup == null) groups else listOf(selectedGroup)
        val items = repository.getFeedItems(filtered)
        println("FiberSocial: fetched ${items.size} feed items")
        FeedState.Loaded(
            user = user,
            groups = groups,
            selectedGroup = selectedGroup,
            items = items,
        )
    } catch (e: SessionExpiredException) {
        println("FiberSocial: fetchFeed session expired — navigating to login")
        _sessionExpired.trySend(Unit)
        FeedState.Loading
    } catch (e: Exception) {
        println("FiberSocial: fetchFeed error: ${e.message}")
        FeedState.Error(e.message ?: "Failed to load feed")
    }
}
