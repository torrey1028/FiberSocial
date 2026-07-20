package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.auth.SessionExpirySignal
import com.myhobbyislearning.fibersocial.feed.RavelryApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The folders a conversation can span, and therefore the folders every page fetch covers.
 *
 * INBOX + SENT is the minimum that makes a conversation two-sided: `groupIntoThreads` is
 * folder-agnostic and groups exactly what it is handed, so fetching only the inbox would
 * hide every conversation the user started and render every remaining thread one-sided
 * (see `MessageThreads.kt`). ARCHIVED is deliberately excluded — Ravelry's archive is the
 * user's explicit "move this out of my inbox" action, so folding it back into the main
 * list would undo it. A conversation with archived messages simply shows the parts that
 * are still live.
 */
private val LISTED_FOLDERS = listOf(MessageFolder.INBOX, MessageFolder.SENT)

/**
 * Per-folder paging position. Each folder pages independently: `sent` routinely has far
 * fewer pages than `inbox`, and pinning them to one shared page number would either stop
 * paging the inbox as soon as `sent` ran out or keep requesting pages past the end of
 * `sent` forever.
 *
 * @property nextPage The 1-based page to request next.
 * @property hasMore Whether that page exists. Starts `true` so the first round runs.
 */
private data class FolderCursor(val nextPage: Int = 1, val hasMore: Boolean = true)

/** State of the Messages conversation list, emitted by [MessagesViewModel]. */
sealed class MessagesState {
    /** First page in flight; nothing to show yet. */
    data object Loading : MessagesState()

    /**
     * Conversations are available. An EMPTY [threads] with no error is the empty-inbox
     * state — a legitimately empty mailbox, not a failure — and the screen renders "No
     * messages yet" for it.
     *
     * @property threads Reconstructed conversations, newest activity first (the ordering
     *   [groupIntoThreads] applies; threads whose activity is entirely unknown sort last).
     * @property hasMore Whether any listed folder has a further page.
     * @property loadingMore Whether a [MessagesViewModel.loadMore] round is in flight.
     * @property refreshing Whether a pull-to-refresh is in flight over this content.
     */
    data class Loaded(
        val threads: List<MessageThread>,
        val hasMore: Boolean = false,
        val loadingMore: Boolean = false,
        val refreshing: Boolean = false,
    ) : MessagesState()

    /**
     * The first page failed. Recoverable via [MessagesViewModel.retry], which works from
     * this state (issue #330 is the same screen shape for events getting this wrong — its
     * retry called a refresh that no-ops unless already loaded, so the error screen could
     * never be left).
     *
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : MessagesState()
}

/**
 * Drives the Messages conversation list (issue #370, epic #365).
 *
 * Ravelry has no conversation object — see `MessageThreads.kt`. This fetches the flat
 * message lists, accumulates them across pages, and regroups the whole accumulation into
 * threads on every change. Regrouping from scratch rather than incrementally merging is
 * deliberate and cheap: [groupIntoThreads] is documented as idempotent and
 * order-independent precisely so a caller can do this, and an incrementally-merged thread
 * list would get the interesting cases wrong — a newly arrived parent has to *absorb* the
 * orphaned reply that already rooted itself in an earlier page, which is a re-group, not
 * an append.
 *
 * ## Getting preview bodies: `full = true`, no per-thread fallback
 *
 * Rows preview the newest message's body, and bodies only exist on the `full` shape.
 * `getMessages(full = true)` asks for them, but WHETHER THAT WORKS IS UNRESOLVED —
 * Ravelry's docs contradict themselves over `output_format` vs `format` and we have no
 * live token to settle it (the full analysis is on `RavelryApiClient.getMessages`).
 *
 * This does not assume it works. The risk there is documented as one-sided: if the
 * parameter name is wrong, bodies come back `null` and nothing errors. So the contract
 * here is that a null body is ROUTINE — [messagePreviewText] renders it as an empty
 * preview and the row still shows counterpart, subject, timestamp and unread dot. The
 * screen degrades by one line; it never breaks.
 *
 * The obvious fallback — `getMessage(id)` per thread, which always returns the full shape
 * — is deliberately NOT done. It costs one extra request per conversation on every page,
 * so a 25-message page becomes up to 26 requests, repeated on every pull-to-refresh, to
 * populate a single line of preview text. That is a bad trade against a cosmetic gain, and
 * it would fire on every user forever rather than only if the ambiguity resolves badly.
 * When someone settles the ambiguity with one authenticated call, either this keeps
 * working unchanged or the fix is a one-word change in the client, not here.
 *
 * @param apiClient Ravelry client used for the list calls.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class MessagesViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<MessagesState>(MessagesState.Loading)
    private val sessionExpirySignal = SessionExpirySignal()

    /** Observable conversation-list state. */
    val state: StateFlow<MessagesState> = _state.asStateFlow()

    /** @see SessionExpirySignal.flow */
    val sessionExpired: Flow<Unit> = sessionExpirySignal.flow

    /**
     * The signed-in user's username, supplied by the caller rather than fetched.
     *
     * The feed has already resolved the current user (`FeedState.Loaded.user`) by the time
     * Messages can be opened, so re-fetching it here would be a redundant request on every
     * entry. Retained across [refresh]/[loadMore]/[retry] so those never need it passed
     * again — notably [retry] must work from the error state, where the screen may have no
     * fresh value to hand back.
     *
     * A blank value is survivable rather than fatal: [groupIntoThreads] documents that it
     * makes every message look inbound, which can only over-report unread, never
     * under-report it.
     */
    private var currentUsername: String = ""

    /** Every message fetched so far, across folders and pages. Regrouped wholesale. */
    private var accumulated: List<Message> = emptyList()

    private var cursors: Map<MessageFolder, FolderCursor> = freshCursors()

    /**
     * Set when a [loadMore] round fails, and reported as "no more pages" until the next
     * [load]/[refresh]/[retry] clears it.
     *
     * Without this the screen hammers a failing endpoint: the list's scroll trigger fires
     * on `loadingMore` going back to false while `hasMore` is still true, so a failed
     * round immediately re-arms itself and loops. Suppressing paging leaves the loaded
     * conversations on screen — pull-to-refresh is the way back.
     */
    private var pagingBlocked: Boolean = false

    private var loadJob: Job? = null
    private var loadMoreJob: Job? = null

    /**
     * Loads the first page of every listed folder, replacing whatever was there.
     *
     * @param username the signed-in user's username; see [currentUsername].
     */
    fun load(username: String) {
        currentUsername = username
        startFirstPage(showRefreshing = false)
    }

    /** Pull-to-refresh: reloads page 1, keeping the current conversations on screen. */
    fun refresh() = startFirstPage(showRefreshing = true)

    /**
     * Re-runs the initial load from the error state — the error screen's Retry button.
     *
     * Separate from [refresh] on purpose: [refresh] wants to keep stale content visible,
     * which from [MessagesState.Error] there is none of. This always goes back to
     * [MessagesState.Loading] and so always visibly does something. It needs no username
     * argument because [currentUsername] is retained (issue #330's failure was a retry
     * that silently no-opped).
     */
    fun retry() = startFirstPage(showRefreshing = false)

    private fun startFirstPage(showRefreshing: Boolean) {
        loadJob?.cancel()
        loadMoreJob?.cancel()
        val stale = _state.value as? MessagesState.Loaded
        _state.value = if (showRefreshing && stale != null) {
            stale.copy(refreshing = true, loadingMore = false)
        } else {
            MessagesState.Loading
        }
        loadJob = scope.launch {
            println("FiberSocial: MessagesViewModel.load user=$currentUsername")
            _state.value = try {
                val (messages, updated) = fetchRound(freshCursors())
                // Committed only on success, so a failed refresh leaves the previous
                // conversations intact rather than blanking the screen.
                accumulated = messages
                cursors = updated
                pagingBlocked = false
                println("FiberSocial: MessagesViewModel loaded ${messages.size} messages")
                loadedState()
            } catch (e: SessionExpiredException) {
                // Signalled, never swallowed — the host logs the user out. A plain 403 is
                // NOT this (issue #82); only the client's own expiry exception is.
                println("FiberSocial: MessagesViewModel.load session expired")
                sessionExpirySignal.signal()
                MessagesState.Loading
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                println("FiberSocial: MessagesViewModel.load failed: ${e.message}")
                MessagesState.Error(e.message ?: "Couldn't load messages")
            }
        }
    }

    /**
     * Fetches the next page of every folder that still has one and APPENDS it to the
     * accumulation. No-op unless loaded, with more to fetch, and not already fetching.
     */
    fun loadMore() {
        val loaded = _state.value as? MessagesState.Loaded ?: return
        if (loaded.loadingMore || !loaded.hasMore) return
        _state.value = loaded.copy(loadingMore = true)
        loadMoreJob = scope.launch {
            _state.value = try {
                val (messages, updated) = fetchRound(cursors)
                // Append, never replace. Duplicate ids across overlapping pages collapse
                // in groupIntoThreads, so a message arriving twice is harmless.
                accumulated = accumulated + messages
                cursors = updated
                println("FiberSocial: MessagesViewModel loadMore added ${messages.size} messages")
                loadedState()
            } catch (e: SessionExpiredException) {
                println("FiberSocial: MessagesViewModel.loadMore session expired")
                sessionExpirySignal.signal()
                loadedState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Keep what is already on screen: losing a page-2 fetch is no reason to
                // throw away page 1. See [pagingBlocked] for why paging then stops.
                println("FiberSocial: MessagesViewModel.loadMore failed: ${e.message}")
                pagingBlocked = true
                loadedState()
            }
        }
    }

    /**
     * Requests one page from every folder in [from] that still has pages, in parallel, and
     * returns the messages plus the advanced cursors.
     *
     * The folders are fetched concurrently and awaited together, so a failure in EITHER
     * fails the round. That is the intended policy for this screen: silently succeeding on
     * the inbox alone would render every conversation one-sided with no indication
     * anything was missing, which is worse than an error the user can retry.
     */
    private suspend fun fetchRound(
        from: Map<MessageFolder, FolderCursor>,
    ): Pair<List<Message>, Map<MessageFolder, FolderCursor>> = coroutineScope {
        val pending = LISTED_FOLDERS.filter { from.getValue(it).hasMore }
        val pages = pending.map { folder ->
            async {
                folder to apiClient.getMessages(
                    folder = folder,
                    page = from.getValue(folder).nextPage,
                    // Ask for bodies inline; a null body is a routine degrade. See KDoc.
                    full = true,
                )
            }
        }.awaitAll()

        val updated = from.toMutableMap()
        pages.forEach { (folder, page) ->
            updated[folder] = FolderCursor(nextPage = page.page + 1, hasMore = page.hasMore)
        }
        pages.flatMap { (_, page) -> page.messages } to updated.toMap()
    }

    private fun loadedState(): MessagesState.Loaded = MessagesState.Loaded(
        threads = groupIntoThreads(accumulated, currentUsername),
        hasMore = !pagingBlocked && cursors.values.any { it.hasMore },
        loadingMore = false,
        refreshing = false,
    )

    private fun freshCursors(): Map<MessageFolder, FolderCursor> =
        LISTED_FOLDERS.associateWith { FolderCursor() }
}
