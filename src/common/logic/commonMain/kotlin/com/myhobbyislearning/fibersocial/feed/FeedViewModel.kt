package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.auth.SessionExpirySignal
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
     * @property selectedGroup The group whose topics are being shown. `null` when the
     *   user belongs to no groups — there is no "all groups" view (issue #97) — or when
     *   [myPosts] is showing (that feed spans all groups, so no single group is selected).
     * @property myPosts Whether the cross-group "My Posts" feed (topics the user has
     *   posted in) is being shown instead of a group's topics. Mutually exclusive with a
     *   non-null [selectedGroup].
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
        val myPosts: Boolean = false,
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
    private val sessionExpirySignal = SessionExpirySignal()

    /** Observable feed state. */
    val state: StateFlow<FeedState> = _state.asStateFlow()

    /** @see SessionExpirySignal.flow */
    val sessionExpired: Flow<Unit> = sessionExpirySignal.flow

    private val _joinState = MutableStateFlow<JoinState>(JoinState.Idle)

    /** Observable state of a "join the support group" action (see [joinSupportGroup]). */
    val joinState: StateFlow<JoinState> = _joinState.asStateFlow()

    private val _leavingGroupId = MutableStateFlow<Long?>(null)

    /** Id of the group currently being left (see [leaveGroup]), or null — drives the
     *  leave confirmation's spinner (issue #231). */
    val leavingGroupId: StateFlow<Long?> = _leavingGroupId.asStateFlow()

    private val _leaveError = MutableStateFlow<String?>(null)

    /**
     * Human-readable message from the most recent failed [leaveGroup] call, or null.
     * A non-session failure previously left the confirmation dialog auto-dismissing as
     * if the leave had succeeded, with the group silently staying in the drawer and no
     * indication anything went wrong (issue #263) — this lets the dialog surface the
     * failure and offer a retry instead.
     */
    val leaveError: StateFlow<String?> = _leaveError.asStateFlow()

    private val _drawerUnread = MutableStateFlow(DrawerUnread())
    private var drawerUnreadJob: Job? = null

    /**
     * Whether the drawer's group rows and "Your Posts" row should show an unread dot
     * (unread indicators). Updated by [refreshDrawerUnread] — see its doc for when that
     * fires; a fetch failure leaves the previous value rather than clearing the dots.
     */
    val drawerUnread: StateFlow<DrawerUnread> = _drawerUnread.asStateFlow()

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
                // flips to "Send feedback"; keep whatever feed the user was viewing
                // (their group, or the My Posts view).
                val viewing = (_state.value as? FeedState.Loaded)
                    ?: (_state.value as? FeedState.Refreshing)?.stale
                _state.value = fetchFeed(
                    selectedGroup = viewing?.selectedGroup,
                    myPosts = viewing?.myPosts ?: false,
                )
                _joinState.value = JoinState.Idle
            } catch (e: SessionExpiredException) {
                println("FiberSocial: joinSupportGroup session expired")
                _joinState.value = JoinState.Idle
                sessionExpirySignal.signal()
            } catch (e: Exception) {
                println("FiberSocial: joinSupportGroup error: ${e.message}")
                _joinState.value = JoinState.Error(e.message ?: "Couldn't join the group")
            }
        }
    }

    /**
     * Leaves [group] (issue #231), then re-scrapes memberships so it drops out of the
     * drawer. If the user was viewing the group they left, falls back to the default group;
     * otherwise the current selection is kept. No-ops if the feed isn't loaded.
     */
    fun leaveGroup(group: Group) {
        val current = _state.value as? FeedState.Loaded ?: return
        if (_leavingGroupId.value != null) return
        _leaveError.value = null
        _leavingGroupId.value = group.id
        scope.launch {
            try {
                repository.leaveGroup(group.permalink)
                val newSelection = if (current.selectedGroup?.id == group.id) null else current.selectedGroup
                _state.value = fetchFeed(selectedGroup = newSelection, myPosts = current.myPosts)
            } catch (e: SessionExpiredException) {
                println("FiberSocial: leaveGroup session expired")
                sessionExpirySignal.signal()
            } catch (e: Exception) {
                println("FiberSocial: leaveGroup error: ${e.message}")
                _leaveError.value = e.message ?: "Couldn't leave the group"
            } finally {
                _leavingGroupId.value = null
            }
        }
    }

    /** Clears a stale [leaveError] (e.g. when the leave dialog is canceled/dismissed). */
    fun acknowledgeLeaveError() {
        _leaveError.value = null
    }

    /** Clears a stale [JoinState.Error] (e.g. when the drawer reopens). No-op mid-join. */
    fun acknowledgeJoinError() {
        if (_joinState.value is JoinState.Error) _joinState.value = JoinState.Idle
    }

    /** Sets the session-expired signal. Call only from platform debug tooling. */
    fun forceSessionExpiry() { sessionExpirySignal.signal() }

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
     * Best-effort refresh of the drawer's unread dots ([drawerUnread]). Driven by the UI
     * (on first feed display and on drawer pull-to-refresh) rather than folded into
     * [load]/[refresh], so it stays an independent, non-blocking side channel: a failure
     * just keeps the previous value so a transient error can't wrongly clear the dots, and
     * it never disrupts the feed itself. Session expiry IS still signalled from here,
     * though — this can be the only in-flight request (e.g. a drawer pull-to-refresh while
     * otherwise idle on an already-loaded feed), so it can't assume some other fetch will
     * always notice first.
     *
     * A new call cancels any still-in-flight previous one before starting, so responses
     * can never land out of order and let a slow, stale result overwrite a fresher one —
     * true last-*call*-wins, not just last-to-*complete*-wins.
     */
    fun refreshDrawerUnread() {
        drawerUnreadJob?.cancel()
        drawerUnreadJob = scope.launch {
            try {
                _drawerUnread.value = repository.getDrawerUnread()
                println(
                    "FiberSocial: drawer unread -> ${_drawerUnread.value.unreadGroupForumIds.size} " +
                        "group(s), yourPosts=${_drawerUnread.value.yourPostsHasUnread}",
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: drawer unread refresh session expired")
                sessionExpirySignal.signal()
            } catch (e: Exception) {
                println("FiberSocial: drawer unread refresh failed: ${e.message}")
            }
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
            _state.value = fetchFeed(selectedGroup = current.selectedGroup, myPosts = current.myPosts)
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
     *
     * Only touches the in-memory card. Re-checking the drawer's dots after a read is
     * [refreshDrawerUnreadAfterReading], which has to run *after* Ravelry's marker has
     * actually advanced — see its doc.
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
     * Re-checks the drawer's unread dots after [topicId] has been read (issue #350 part 2).
     *
     * Reading is the only natural moment a dot can go *out* — [markTopicReadUpTo] updates
     * just the in-memory card, and drawer-open deliberately doesn't refresh — so without
     * this a group whose every topic has been read keeps its dot lit until a manual pull.
     *
     * **Call this only once Ravelry's read marker has actually advanced**
     * ([TopicDetailViewModel.markRead]'s `onMarked`), never alongside the POST that
     * advances it: [FeedRepository.getDrawerUnread] is a GET that races such a POST, and
     * a GET that wins reports the pre-read state — re-lighting the very dot this exists
     * to clear.
     *
     * Gated by [shouldRefreshDrawerUnreadAfterReading] so the common case (backing out of
     * a topic with no relevant dot lit) costs nothing.
     */
    fun refreshDrawerUnreadAfterReading(topicId: Long) {
        val current = _state.value as? FeedState.Loaded ?: return
        val target = current.items.firstOrNull { it.id == topicId } ?: return
        if (shouldRefreshDrawerUnreadAfterReading(current, target)) refreshDrawerUnread()
    }

    /**
     * Whether reading [target] in [current] could have turned a currently-lit drawer dot
     * off, and is therefore worth paying a [refreshDrawerUnread] for. True when either:
     *
     * - the topic's own group row has a dot — its forum is in
     *   [DrawerUnread.unreadGroupForumIds] (matched via the loaded [Group.forumId], since
     *   [FeedItem] carries the group id, not the forum id); or
     * - the "Your Posts" dot is lit *and* the My Posts feed is what's showing — there,
     *   every topic is by definition one the user posted in, so reading one can clear it.
     *
     * Reading from a *group* feed is deliberately not treated as a "Your Posts" candidate:
     * whether the user has posted in that topic isn't known without another fetch, and the
     * "Your Posts" dot is lit often enough that guessing yes would make the gate almost
     * unconditional — exactly the cost this avoids. That dot instead settles on the next
     * foreground activation or drawer pull-to-refresh.
     *
     * When no relevant dot is lit there is nothing to clear, so the refresh would be pure
     * waste and is skipped.
     */
    private fun shouldRefreshDrawerUnreadAfterReading(
        current: FeedState.Loaded,
        target: FeedItem,
    ): Boolean {
        val unread = _drawerUnread.value
        val forumId = current.groups.firstOrNull { it.id == target.groupId }?.forumId
        if (forumId != null && forumId in unread.unreadGroupForumIds) return true
        return unread.yourPostsHasUnread && current.myPosts
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
                myPosts = false,
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
                sessionExpirySignal.signal()
                current
            } catch (e: Exception) {
                println("FiberSocial: selectGroup error: ${e.message}")
                current
            }
        }
    }

    /**
     * Shows the cross-group "My Posts" feed — topics the user has posted in, across all
     * groups (the drawer item above the group list). Mirrors [selectGroup]'s
     * switch-immediately shape: chrome flips to My Posts on this frame with a loading
     * content area, then the fetch fills it in. No-ops if the feed is not in
     * [FeedState.Loaded] or My Posts is already showing.
     */
    fun selectMyPosts() {
        val current = _state.value as? FeedState.Loaded ?: return
        if (current.myPosts) return
        println("FiberSocial: FeedViewModel.selectMyPosts()")
        _state.value = FeedState.Refreshing(
            current.copy(
                selectedGroup = null,
                myPosts = true,
                items = emptyList(),
                hasMore = false,
                loadingMore = false,
                nextPage = 2,
            ),
        )
        scope.launch {
            _state.value = try {
                val page = repository.getMyPostsPage(current.groups, page = 1)
                println("FiberSocial: selectMyPosts loaded ${page.items.size} items")
                FeedState.Loaded(
                    user = current.user,
                    groups = current.groups,
                    selectedGroup = null,
                    myPosts = true,
                    items = page.items,
                    hasMore = page.hasMore,
                )
            } catch (e: SessionExpiredException) {
                println("FiberSocial: selectMyPosts session expired")
                sessionExpirySignal.signal()
                current
            } catch (e: Exception) {
                println("FiberSocial: selectMyPosts error: ${e.message}")
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
        val group = current.selectedGroup
        if (group == null && !current.myPosts) return
        val loading = current.copy(loadingMore = true)
        _state.value = loading
        scope.launch {
            println("FiberSocial: FeedViewModel.loadMore() page=${current.nextPage}")
            val next = try {
                val page = if (group != null) {
                    repository.getFeedItemsPage(group, page = current.nextPage)
                } else {
                    repository.getMyPostsPage(current.groups, page = current.nextPage)
                }
                println("FiberSocial: loadMore appended ${page.items.size} items, hasMore=${page.hasMore}")
                loading.copy(
                    items = loading.items + page.items,
                    hasMore = page.hasMore,
                    loadingMore = false,
                    nextPage = loading.nextPage + 1,
                )
            } catch (e: SessionExpiredException) {
                println("FiberSocial: loadMore session expired")
                sessionExpirySignal.signal()
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
     *   only while the user still belongs to that group. Ignored when [myPosts] is set.
     * @param myPosts Keep showing the cross-group "My Posts" feed instead of a group
     *   (a refresh while it's selected).
     */
    private suspend fun fetchFeed(selectedGroup: Group?, myPosts: Boolean = false): FeedState = try {
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
        if (myPosts) {
            val page = repository.getMyPostsPage(groups, page = 1)
            println("FiberSocial: fetched ${page.items.size} my-posts items")
            FeedState.Loaded(
                user = user,
                groups = groups,
                selectedGroup = null,
                myPosts = true,
                items = page.items,
                hasMore = page.hasMore,
            )
        } else {
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
        }
    } catch (e: SessionExpiredException) {
        println("FiberSocial: fetchFeed session expired — navigating to login")
        sessionExpirySignal.signal()
        FeedState.Loading
    } catch (e: Exception) {
        println("FiberSocial: fetchFeed error: ${e.message}")
        FeedState.Error(e.message ?: "Failed to load feed")
    }
}
