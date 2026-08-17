package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.auth.SessionExpirySignal
import com.myhobbyislearning.fibersocial.feed.models.Group
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** How Ravelry orders the directory when the user has not typed a query. */
private const val BROWSE_SORT = "favorites"

/**
 * How long to wait after a keystroke before searching. Long enough that typing a word
 * costs one request rather than one per letter, short enough not to feel laggy.
 */
internal const val GROUP_SEARCH_DEBOUNCE_MS = 350L

/** What the group browser is showing. */
sealed class GroupSearchState {
    /** First load (or a fresh query) is in flight — nothing to show yet. */
    object Loading : GroupSearchState()

    /**
     * Results are on screen.
     *
     * @property groups Everything loaded so far, across pages.
     * @property query The query these results are for; blank means the browse listing.
     * @property page Highest page loaded.
     * @property hasMore Whether another page exists.
     * @property loadingMore Whether that next page is in flight.
     */
    data class Loaded(
        val groups: List<Group>,
        val query: String,
        val page: Int,
        val hasMore: Boolean,
        val loadingMore: Boolean = false,
    ) : GroupSearchState()

    /** The search failed; [message] is safe to show. */
    data class Error(val message: String) : GroupSearchState()
}

/**
 * Backs the in-app group browser (issue #232).
 *
 * The app used to answer "find new groups" by opening Ravelry's website in a browser.
 * That is a poor experience — it leaves the app, loses the session's context, and (on
 * iOS) is the pattern App Review rejected under guideline 4 for sign-in flows (#481).
 * Everything needed to do it natively already existed: `/groups/search.json` was already
 * being called to resolve permalinks, and [FeedRepository.joinGroup] already joins.
 *
 * With no query this browses the directory ([BROWSE_SORT]) rather than showing an empty
 * screen, so the browser is useful before the user knows what to search for — which is
 * what issue #232 asked for.
 *
 * @param onGroupsChanged Invoked after a successful join so the host can re-read
 *   membership and light the group up in the drawer.
 */
class GroupSearchViewModel(
    private val repository: FeedRepository,
    private val scope: CoroutineScope,
    private val sessionExpirySignal: SessionExpirySignal = SessionExpirySignal(),
    private val onGroupsChanged: suspend () -> Unit = {},
) {
    private val _state = MutableStateFlow<GroupSearchState>(GroupSearchState.Loading)

    /** Observable browser state. */
    val state: StateFlow<GroupSearchState> = _state.asStateFlow()

    /** @see SessionExpirySignal.flow */
    val sessionExpired: Flow<Unit> = sessionExpirySignal.flow

    private val _query = MutableStateFlow("")

    /** The text in the search field — the ViewModel owns it so a rotation can't lose it. */
    val query: StateFlow<String> = _query.asStateFlow()

    private val _joiningPermalink = MutableStateFlow<String?>(null)

    /** Permalink of the group being joined, or null — drives that row's spinner. */
    val joiningPermalink: StateFlow<String?> = _joiningPermalink.asStateFlow()

    private val _joinedPermalinks = MutableStateFlow<Set<String>>(emptySet())

    /**
     * Groups joined during this session of the browser. Ravelry's search results carry no
     * "am I a member" flag, so this is what flips a row's button to "Joined" — without it
     * a successful join looks like nothing happened.
     */
    val joinedPermalinks: StateFlow<Set<String>> = _joinedPermalinks.asStateFlow()

    private val _joinError = MutableStateFlow<String?>(null)

    /** Message from the most recent failed join, or null. Cleared by [dismissJoinError]. */
    val joinError: StateFlow<String?> = _joinError.asStateFlow()

    private var searchJob: Job? = null

    /** Loads the browse listing. Safe to call on every entry to the screen. */
    fun start() {
        if (searchJob == null) search(_query.value)
    }

    /**
     * Records a keystroke and schedules a search for it.
     *
     * The pending search is cancelled on each keystroke, so a burst of typing costs one
     * request. Cancelling also means an in-flight request for an older query cannot land
     * after a newer one and overwrite it with stale results.
     */
    fun onQueryChanged(query: String) {
        _query.value = query
        search(query, debounce = true)
    }

    /** Retries after an error, or forces a search without waiting for the debounce. */
    fun retry() = search(_query.value)

    private fun search(query: String, debounce: Boolean = false) {
        searchJob?.cancel()
        searchJob = scope.launch {
            if (debounce) delay(GROUP_SEARCH_DEBOUNCE_MS)
            _state.value = GroupSearchState.Loading
            try {
                val page = repository.searchGroups(query = query, sort = sortFor(query))
                _state.value = GroupSearchState.Loaded(
                    groups = page.groups,
                    query = query,
                    page = page.page,
                    hasMore = page.hasMore,
                )
            } catch (e: SessionExpiredException) {
                sessionExpirySignal.signal()
            } catch (e: Exception) {
                println("FiberSocial: searchGroups(\"$query\") failed: ${e.message}")
                _state.value = GroupSearchState.Error(
                    "Couldn't search groups. Check your connection and try again.",
                )
            }
        }
    }

    /** Appends the next page. Ignored unless there is one and none is already loading. */
    fun loadMore() {
        val loaded = _state.value as? GroupSearchState.Loaded ?: return
        if (!loaded.hasMore || loaded.loadingMore) return
        _state.value = loaded.copy(loadingMore = true)
        scope.launch {
            try {
                val next = repository.searchGroups(
                    query = loaded.query,
                    sort = sortFor(loaded.query),
                    page = loaded.page + 1,
                )
                // Re-read rather than closing over `loaded`: a query change while this was
                // in flight has already replaced the state, and appending to the old list
                // would resurrect results for a query the user has moved on from.
                val current = _state.value as? GroupSearchState.Loaded ?: return@launch
                if (current.query != loaded.query) return@launch
                _state.value = current.copy(
                    groups = current.groups + next.groups,
                    page = next.page,
                    hasMore = next.hasMore,
                    loadingMore = false,
                )
            } catch (e: SessionExpiredException) {
                sessionExpirySignal.signal()
            } catch (e: Exception) {
                println("FiberSocial: searchGroups page ${loaded.page + 1} failed: ${e.message}")
                val current = _state.value as? GroupSearchState.Loaded ?: return@launch
                _state.value = current.copy(loadingMore = false)
            }
        }
    }

    /**
     * Joins [group], then asks the host to re-read membership so the drawer catches up.
     * Double-taps are ignored while one is in flight.
     */
    fun join(group: Group) {
        if (_joiningPermalink.value != null) return
        if (group.permalink in _joinedPermalinks.value) return
        _joiningPermalink.value = group.permalink
        _joinError.value = null
        scope.launch {
            try {
                repository.joinGroup(group.permalink)
                _joinedPermalinks.value = _joinedPermalinks.value + group.permalink
                onGroupsChanged()
            } catch (e: SessionExpiredException) {
                sessionExpirySignal.signal()
            } catch (e: Exception) {
                println("FiberSocial: joinGroup(${group.permalink}) failed: ${e.message}")
                _joinError.value = "Couldn't join ${group.name}. Please try again."
            } finally {
                _joiningPermalink.value = null
            }
        }
    }

    /** Dismisses the join error surfaced by [joinError]. */
    fun dismissJoinError() {
        _joinError.value = null
    }

    // Relevance only makes sense once there is something to be relevant to; with no query
    // Ravelry's "best" is arbitrary, so the directory is browsed most-favorited first.
    private fun sortFor(query: String) = if (query.isBlank()) BROWSE_SORT else ""
}
