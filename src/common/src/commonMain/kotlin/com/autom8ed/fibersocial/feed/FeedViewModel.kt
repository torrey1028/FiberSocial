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
     * @property groups All groups the user is a member of, in the user's stored display
     *   order (see [GroupOrderStore]); populates the group drawer.
     * @property selectedGroup The group whose topics are being shown. `null` only when
     *   the user belongs to no groups — there is no "all groups" view (issue #97).
     * @property items Feed items for the selected group, sorted newest-reply-first.
     * @property hasMore Whether [selectedGroup] has further pages of topics beyond
     *   [items] (issue #106). Always `false` when [selectedGroup] is `null`.
     * @property loadingMore Whether a [FeedViewModel.loadMore] fetch is in flight — the
     *   feed screen shows a footer spinner while this is `true`.
     * @property nextPage The page [FeedViewModel.loadMore] will request next.
     */
    data class Loaded(
        val user: RavelryUser,
        val groups: List<Group>,
        val selectedGroup: Group?,
        val items: List<FeedItem>,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        val nextPage: Int = 2,
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

/** State of an in-flight "join the support group" action (the drawer's feedback button). */
sealed class JoinState {
    /** No join in flight. */
    object Idle : JoinState()

    /** A join request is in flight. */
    object Joining : JoinState()

    /**
     * The join failed.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : JoinState()
}

/**
 * Platform-agnostic ViewModel that drives the group feed screen.
 *
 * Exposes a [state] flow consumed by platform ViewModels (e.g. `FeedAndroidViewModel`).
 *
 * @param repository Feed data source.
 * @param scope Coroutine scope tied to the ViewModel's lifecycle.
 * @param groupOrderStore Persisted group display order; its first group is the default
 *   group opened on load (issue #97).
 */
class FeedViewModel(
    private val repository: FeedRepository,
    private val scope: CoroutineScope,
    private val groupOrderStore: GroupOrderStore,
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

    private val _joinState = MutableStateFlow<JoinState>(JoinState.Idle)

    /** Observable state of a "join the support group" action (see [joinSupportGroup]). */
    val joinState: StateFlow<JoinState> = _joinState.asStateFlow()

    /**
     * Joins the current user to the group at [permalink] (the app support group, from the
     * drawer's "Join feedback group" button), then reloads so the drawer reflects the new
     * membership. Double-taps are ignored. A session expiry routes to login like any other
     * feed action; other failures land in [JoinState.Error] for the button to surface.
     */
    fun joinSupportGroup(permalink: String) {
        if (_joinState.value is JoinState.Joining) return
        _joinState.value = JoinState.Joining
        scope.launch {
            try {
                repository.joinGroup(permalink)
                // Re-scrape memberships so the just-joined group appears and the button
                // flips to "Send feedback"; keep whatever group the user was viewing.
                val selected = (_state.value as? FeedState.Loaded)?.selectedGroup
                    ?: (_state.value as? FeedState.Refreshing)?.stale?.selectedGroup
                _state.value = fetchFeed(selectedGroup = selected)
                _joinState.value = JoinState.Idle
            } catch (e: SessionExpiredException) {
                println("FiberSocial: joinSupportGroup session expired")
                _joinState.value = JoinState.Idle
                _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: joinSupportGroup error: ${e.message}")
                _joinState.value = JoinState.Error(e.message ?: "Couldn't join the group")
            }
        }
    }

    /** Clears a stale [JoinState.Error] (e.g. when the drawer reopens). No-op mid-join. */
    fun acknowledgeJoinError() {
        if (_joinState.value is JoinState.Error) _joinState.value = JoinState.Idle
    }

    /** Sets the session-expired signal. Call only from platform debug tooling. */
    fun forceSessionExpiry() { _sessionExpired.trySend(Unit) }

    /** Drops the feed into [FeedState.Error]. Call only from platform debug tooling. */
    fun forceError() {
        _state.value = FeedState.Error("Forced from the debug panel")
    }

    /** Performs an initial full load: user → groups → feed items. */
    /**
     * Clears any loaded feed back to [FeedState.Loading]. Call on sign-out so a
     * subsequent login can't flash the previous account's feed, groups, or
     * profile row before its own load() lands.
     */
    fun reset() {
        _state.value = FeedState.Loading
    }

    fun load() {
        scope.launch {
            println("FiberSocial: FeedViewModel.load() starting")
            _state.value = FeedState.Loading
            _state.value = fetchFeed(selectedGroup = null) // null: fall back to the default group
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
     * Updates [topicId]'s unread badge in the loaded feed to reflect that the user has
     * read up to post number [readUpTo] (issue #185). Viewing a topic only loads its
     * first page of posts, and Ravelry's marker is advanced to exactly that — so the
     * card should show the *remaining* unread (postCount − readUpTo), not zero, the
     * moment the user backs out, without waiting for a refetch. Only ever lowers the
     * count (Ravelry's marker also only advances), and no-ops if the feed isn't loaded
     * or nothing would change.
     */
    fun markTopicReadUpTo(topicId: Long, readUpTo: Int) {
        val current = _state.value as? FeedState.Loaded ?: return
        val target = current.items.firstOrNull { it.id == topicId } ?: return
        val newUnread = (target.postCount - readUpTo).coerceAtLeast(0)
        if (newUnread >= target.unreadCount) return
        _state.value = current.copy(
            items = current.items.map { item ->
                if (item.id == topicId) {
                    item.copy(
                        unreadCount = newUnread,
                        firstUnreadPostNumber = if (newUnread > 0) readUpTo + 1 else null,
                    )
                } else {
                    item
                }
            },
        )
    }

    /**
     * Shows [group]'s topics. No-ops if the feed is not in [FeedState.Loaded].
     */
    fun selectGroup(group: Group) {
        val current = _state.value as? FeedState.Loaded ?: return
        println("FiberSocial: FeedViewModel.selectGroup(${group.name})")
        // Switch to the new group immediately (issue #214): show it selected in the chrome
        // with a blank, loading content area rather than leaving the old group's topics
        // under a spinner. Set synchronously (before launching the fetch) so the chrome —
        // which reads title/drawer selection from stale — updates on this same frame; the
        // empty items make the content show a spinner until the fetch lands.
        _state.value = FeedState.Refreshing(
            current.copy(
                selectedGroup = group,
                items = emptyList(),
                hasMore = false,
                loadingMore = false,
                nextPage = 2,
            ),
        )
        scope.launch {
            _state.value = try {
                val page = repository.getFeedItemsPage(group, page = 1)
                println("FiberSocial: selectGroup loaded ${page.items.size} items")
                FeedState.Loaded(
                    user = current.user,
                    groups = current.groups,
                    selectedGroup = group,
                    items = page.items,
                    hasMore = page.hasMore,
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

    /**
     * Fetches the next page of topics for the currently selected group and appends it to
     * [FeedState.Loaded.items] (issue #106 — infinite scroll). No-ops if the feed isn't
     * [FeedState.Loaded], there's no selected group, a fetch is already in flight, or
     * [FeedState.Loaded.hasMore] is already `false`.
     */
    fun loadMore() {
        val current = _state.value as? FeedState.Loaded ?: return
        if (!current.hasMore || current.loadingMore) return
        val group = current.selectedGroup ?: return
        val loading = current.copy(loadingMore = true)
        _state.value = loading
        scope.launch {
            println("FiberSocial: FeedViewModel.loadMore() page=${current.nextPage}")
            val next = try {
                val page = repository.getFeedItemsPage(group, page = current.nextPage)
                println("FiberSocial: loadMore appended ${page.items.size} items, hasMore=${page.hasMore}")
                loading.copy(
                    items = loading.items + page.items,
                    hasMore = page.hasMore,
                    loadingMore = false,
                    nextPage = loading.nextPage + 1,
                )
            } catch (e: SessionExpiredException) {
                println("FiberSocial: loadMore session expired")
                _sessionExpired.trySend(Unit)
                loading.copy(loadingMore = false)
            } catch (e: Exception) {
                println("FiberSocial: loadMore error: ${e.message}")
                loading.copy(loadingMore = false)
            }
            // The state may have moved on (group switch, refresh, another loadMore) while
            // this fetch was in flight — reference equality against the exact snapshot this
            // call installed catches ANY intervening change, not just a different group id.
            if (_state.value === loading) _state.value = next
        }
    }

    /**
     * Applies a new drawer display order chosen by the user (drag-and-drop, issue #97)
     * and persists it — the first group of [orderedGroups] becomes the default group on
     * the next app open. The selection and items are untouched; this only reorders the
     * drawer. No-ops if the feed is not in [FeedState.Loaded], or if [orderedGroups]
     * isn't a permutation of the current groups (a caller bug — applying it anyway
     * could silently drop groups from the drawer and persist a corrupt order).
     */
    fun reorderGroups(orderedGroups: List<Group>) {
        val current = _state.value as? FeedState.Loaded ?: return
        val sameSize = orderedGroups.size == current.groups.size
        if (!sameSize || orderedGroups.map { it.id }.toSet() != current.groups.map { it.id }.toSet()) {
            println("FiberSocial: FeedViewModel.reorderGroups ignored — not a permutation of the current groups")
            return
        }
        println("FiberSocial: FeedViewModel.reorderGroups(${orderedGroups.map { it.name }})")
        _state.value = current.copy(groups = orderedGroups)
        scope.launch { groupOrderStore.save(orderedGroups.map { it.id }) }
    }

    /**
     * @param selectedGroup Group to keep showing (a refresh), or `null` to open the
     *   default group — the first in the stored order. A remembered selection survives
     *   only while the user still belongs to that group.
     */
    private suspend fun fetchFeed(selectedGroup: Group?): FeedState = try {
        val user = repository.getCurrentUser()
        println("FiberSocial: fetched user=${user.username}")
        val fetched = repository.getUserGroups(user.username)
        println("FiberSocial: fetched ${fetched.size} groups: ${fetched.map { it.name }}")
        val storedOrder = groupOrderStore.load()
        val groups = reconcileGroupOrder(fetched, storedOrder)
        // Persisting the reconciled result seeds the order on first run and applies the
        // issue #97 maintenance rules (joined groups appended, left groups pruned).
        val reconciledIds = groups.map { it.id }
        if (reconciledIds != storedOrder) groupOrderStore.save(reconciledIds)
        val selected = selectedGroup?.let { s -> groups.firstOrNull { it.id == s.id } }
            ?: groups.firstOrNull()
        val page = selected?.let { repository.getFeedItemsPage(it, page = 1) }
        val items = page?.items ?: emptyList()
        println("FiberSocial: fetched ${items.size} feed items")
        FeedState.Loaded(
            user = user,
            groups = groups,
            selectedGroup = selected,
            items = items,
            hasMore = page?.hasMore ?: false,
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
