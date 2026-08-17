package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.auth.SessionExpirySignal
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Group
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the group preview is showing. */
sealed class GroupPreviewState {
    /** No group is being previewed. */
    object Hidden : GroupPreviewState()

    /** [group]'s topics are loading. */
    data class Loading(val group: Group) : GroupPreviewState()

    /**
     * [group]'s topics are on screen.
     *
     * @property page Highest page loaded.
     * @property hasMore Whether another page exists.
     * @property loadingMore Whether that next page is in flight.
     */
    data class Loaded(
        val group: Group,
        val items: List<FeedItem>,
        val page: Int,
        val hasMore: Boolean,
        val loadingMore: Boolean = false,
    ) : GroupPreviewState()

    /** Loading [group] failed; [message] is safe to show. */
    data class Error(val group: Group, val message: String) : GroupPreviewState()
}

/**
 * Reads a group the user has **not** joined — opened from a group-search result so a
 * group can be looked at before committing to it (issue #232).
 *
 * Deliberately separate from [FeedViewModel] rather than routing a non-member group
 * through `selectGroup`. The feed is membership-shaped: `fetchFeed` re-reads the user's
 * groups and resolves the selected one against that list, so a non-member group would
 * disappear on the next refresh; `selectGroup` also marks the group viewed and persists it
 * as the relaunch destination, neither of which is true of a group being browsed. Topic
 * fetching itself is shared — this calls the same [FeedRepository.getFeedItemsPage] the
 * feed uses, so a previewed group renders exactly like a joined one.
 */
class GroupPreviewViewModel(
    private val repository: FeedRepository,
    private val scope: CoroutineScope,
    private val sessionExpirySignal: SessionExpirySignal = SessionExpirySignal(),
) {
    private val _state = MutableStateFlow<GroupPreviewState>(GroupPreviewState.Hidden)

    /** Observable preview state. */
    val state: StateFlow<GroupPreviewState> = _state.asStateFlow()

    /** @see SessionExpirySignal.flow */
    val sessionExpired: Flow<Unit> = sessionExpirySignal.flow

    /** Opens [group] and loads its first page of topics. */
    fun open(group: Group) {
        _state.value = GroupPreviewState.Loading(group)
        scope.launch { load(group, page = 1) }
    }

    /** Retries the first page after an error. */
    fun retry() {
        val group = when (val s = _state.value) {
            is GroupPreviewState.Error -> s.group
            is GroupPreviewState.Loading -> s.group
            is GroupPreviewState.Loaded -> s.group
            GroupPreviewState.Hidden -> return
        }
        _state.value = GroupPreviewState.Loading(group)
        scope.launch { load(group, page = 1) }
    }

    private suspend fun load(group: Group, page: Int) {
        try {
            val result = repository.getFeedItemsPage(group, page)
            // Dropped if the user closed the preview (or opened another group) while this
            // was in flight — landing it would reopen a screen they already left.
            val current = _state.value
            val stillHere = current is GroupPreviewState.Loading && current.group.id == group.id
            if (!stillHere) return
            _state.value = GroupPreviewState.Loaded(
                group = group,
                items = result.items,
                page = page,
                hasMore = result.hasMore,
            )
        } catch (e: SessionExpiredException) {
            sessionExpirySignal.signal()
        } catch (e: Exception) {
            println("FiberSocial: group preview ${group.permalink} failed: ${e.message}")
            _state.value = GroupPreviewState.Error(
                group,
                "Couldn't load ${group.name}. Check your connection and try again.",
            )
        }
    }

    /** Appends the next page. Ignored unless there is one and none is already loading. */
    fun loadMore() {
        val loaded = _state.value as? GroupPreviewState.Loaded ?: return
        if (!loaded.hasMore || loaded.loadingMore) return
        _state.value = loaded.copy(loadingMore = true)
        scope.launch {
            try {
                val next = repository.getFeedItemsPage(loaded.group, loaded.page + 1)
                // Re-read: the user may have closed the preview or opened a different
                // group while this was in flight, and appending then would splice one
                // group's topics onto another's.
                val current = _state.value as? GroupPreviewState.Loaded ?: return@launch
                if (current.group.id != loaded.group.id) return@launch
                _state.value = current.copy(
                    items = current.items + next.items,
                    page = loaded.page + 1,
                    hasMore = next.hasMore,
                    loadingMore = false,
                )
            } catch (e: SessionExpiredException) {
                sessionExpirySignal.signal()
            } catch (e: Exception) {
                println("FiberSocial: group preview page ${loaded.page + 1} failed: ${e.message}")
                val current = _state.value as? GroupPreviewState.Loaded ?: return@launch
                _state.value = current.copy(loadingMore = false)
            }
        }
    }

    /** Closes the preview. */
    fun close() {
        _state.value = GroupPreviewState.Hidden
    }
}
