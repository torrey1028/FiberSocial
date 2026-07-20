package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.auth.ForbiddenException
import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.auth.SessionExpirySignal
import com.myhobbyislearning.fibersocial.feed.RavelryApiClient
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.feed.parseRavelryTimestamp
import kotlin.time.Clock
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
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
 * The conversation the user has open, emitted by [MessagesViewModel.openThread] (issue
 * #371). `null` on the flow means no conversation is open — the list is what's showing.
 *
 * There is no `Loading` arm and no `Error` arm, because opening a thread never starts
 * from nothing: the conversation is already in hand from the list, so the screen can
 * render its participants, subject, order and every body the list happened to carry
 * immediately. The only thing that can still be in flight is *bodies*, which is a partial
 * degrade rather than a failure — hence [loadingBodies]/[bodyError] rather than a state
 * that can replace the content.
 *
 * @property thread The conversation, oldest → newest. Re-derived from the accumulated
 *   messages whenever they change, so a mark-read or a body fetch is reflected here and
 *   in the list row from the same regrouping.
 * @property loadingBodies Whether the per-message body backfill is in flight. Only ever
 *   true when at least one message actually lacked a body.
 * @property bodyError Set when that backfill failed. The thread stays fully readable —
 *   this only explains why some messages show no text.
 */
data class OpenThreadState(
    val thread: MessageThread,
    val loadingBodies: Boolean = false,
    val bodyError: String? = null,
)

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
 * ## The detail screen lives here too, not in a ViewModel of its own (#371)
 *
 * [openThread] and [OpenThreadState] deliberately sit on this class rather than a
 * separate thread ViewModel, because reading a conversation MUTATES THE LIST: marking a
 * message read has to clear the row's unread dot, and backfilling a body has to reach the
 * row's preview. Both of those are edits to [accumulated], the one list every thread is
 * regrouped from. A separate ViewModel would need either its own copy of that state (two
 * sources of truth for the same messages, guaranteed to diverge the moment one is
 * refreshed) or a write-back channel into this one, which is just this class with an
 * extra hop. Here, one edit to [accumulated] plus one regroup updates the open thread AND
 * the list row together, by construction.
 *
 * ## Composing and replying live here too, for the same reason (#374)
 *
 * A sent message has to APPEAR — in the conversation the user is looking at and in the list
 * row behind it — and the only way to do that without a refetch is to append it to
 * [accumulated] and regroup, exactly as mark-read and the body backfill already do. So
 * [sendNewMessage] and [sendReply] sit here rather than in a composer ViewModel of their
 * own, and the "did it work" state they expose is about the send, not about the mailbox.
 *
 * @param apiClient Ravelry client used for the list calls.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 * @param now Wall clock, injectable for tests. Used ONLY to stamp a just-sent message whose
 *   response carried no usable `sent_at` — see [ravelryTimestamp].
 */
class MessagesViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
    private val now: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _state = MutableStateFlow<MessagesState>(MessagesState.Loading)
    private val _openThread = MutableStateFlow<OpenThreadState?>(null)
    private val _recipientSearch = MutableStateFlow<RecipientSearchState>(RecipientSearchState.Idle)
    private val _sendState = MutableStateFlow<SendMessageState>(SendMessageState.Idle)
    private val sessionExpirySignal = SessionExpirySignal()

    /** Observable conversation-list state. */
    val state: StateFlow<MessagesState> = _state.asStateFlow()

    /** The open conversation, or `null` when the list is what's showing. See [openThread]. */
    val openThread: StateFlow<OpenThreadState?> = _openThread.asStateFlow()

    /** Recipient picker state for the new-message composer. See [searchRecipients]. */
    val recipientSearch: StateFlow<RecipientSearchState> = _recipientSearch.asStateFlow()

    /** State of the in-flight send, for both a new conversation and a reply. */
    val sendState: StateFlow<SendMessageState> = _sendState.asStateFlow()

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
    private var searchJob: Job? = null

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

    /**
     * Opens the conversation rooted at [rootId] and reads it (issue #371).
     *
     * Three things happen, in this order, and the order is the point:
     *
     * 1. The thread is published SYNCHRONOUSLY from the already-loaded list, so the screen
     *    renders on the same frame as the tap. No spinner, no fetch to wait on.
     * 2. Every unread INBOUND message is marked read, one POST at a time (see
     *    [markThreadRead]), and the local state is updated so the list row's dot clears.
     * 3. Missing bodies are backfilled (see [backfillBodies]).
     *
     * Mark-read runs BEFORE the body backfill rather than after or alongside it: it is the
     * user-visible obligation of opening a thread, and it must not be queued behind — or
     * lost to a failure in — a cosmetic fetch.
     *
     * No-ops when the list is not loaded or [rootId] names no thread, which is only
     * reachable if a refresh dropped the conversation between render and tap.
     *
     * @param rootId [MessageThread.rootId] of the tapped conversation.
     * @param onMarkedRead Invoked once, on the ViewModel's scope, AFTER every mark-read
     *   POST has returned and only when at least one actually succeeded.
     *
     *   **THIS IS THE MARK-READ RACE HOOK, and it exists so callers cannot get the order
     *   wrong.** The drawer's Messages dot is refreshed by a GET
     *   ([FeedViewModel.refreshMessagesUnreadAfterReading]) which, if issued next to these
     *   POSTs rather than after them, can win and observe the pre-POST state — re-lighting
     *   the very dot reading the thread just cleared. Both
     *   `RavelryApiClient.markMessageRead` and `FeedViewModel.refreshDrawerUnreadAfterReading`
     *   document this trap. Sequencing it here rather than at the call site means the call
     *   site has no ordering left to get wrong; it just hands over a lambda.
     *
     *   Not invoked when nothing was marked, so backing out of an already-read
     *   conversation costs no request at all.
     */
    fun openThread(rootId: Long, onMarkedRead: () -> Unit = {}) {
        val thread = (_state.value as? MessagesState.Loaded)
            ?.threads?.firstOrNull { it.rootId == rootId }
        if (thread == null) {
            println("FiberSocial: MessagesViewModel.openThread($rootId) — no such thread")
            return
        }
        println("FiberSocial: MessagesViewModel.openThread($rootId)")
        _openThread.value = OpenThreadState(
            thread = thread,
            loadingBodies = thread.messages.any { it.contentHtml.isNullOrBlank() },
        )
        // Deliberately NOT cancelling a previous open's job, and deliberately not
        // cancelled by closeThread: a mark-read POST that the user outran by tapping Back
        // must still land, or the dot they just cleared comes back. Late results can't
        // leak into the wrong thread because every write below re-checks the open rootId.
        scope.launch {
            markThreadRead(thread, onMarkedRead)
            backfillBodies(rootId)
        }
    }

    /** Returns to the conversation list. In-flight mark-read work continues — see [openThread]. */
    fun closeThread() {
        _openThread.value = null
    }

    /**
     * POSTs `mark_read` for each unread INBOUND message in [thread], then updates local
     * state and finally invokes [onMarkedRead].
     *
     * INBOUND ONLY, and unread only. An outbound message's [Message.readMessage] describes
     * whether the *recipient* read it — flipping that would be a lie about someone else's
     * mailbox — and [MessageDirection.UNKNOWN] messages are excluded because their
     * direction is a genuine unknown rather than a default. Already-read messages are
     * skipped so re-opening a conversation is free.
     *
     * Sequential rather than parallel: a thread holds a handful of messages, so there is
     * nothing to win, and a serial loop keeps the "every POST has returned" guarantee
     * [onMarkedRead] depends on trivially true.
     *
     * A failure NEVER wedges the screen: each POST is caught individually, whatever
     * succeeded is still applied locally, and the thread stays open and readable. The
     * unmarked messages simply stay unread and get another chance next time.
     */
    private suspend fun markThreadRead(thread: MessageThread, onMarkedRead: () -> Unit) {
        val unreadInbound = thread.messages.filter {
            !it.readMessage &&
                messageDirection(it, currentUsername) == MessageDirection.INBOUND
        }
        if (unreadInbound.isEmpty()) return

        val marked = mutableSetOf<Long>()
        for (message in unreadInbound) {
            try {
                apiClient.markMessageRead(message.id)
                marked += message.id
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                // Signalled, never swallowed — and the loop stops, since every remaining
                // POST would fail the same way.
                println("FiberSocial: MessagesViewModel mark-read session expired")
                sessionExpirySignal.signal()
                break
            } catch (e: Exception) {
                println("FiberSocial: mark-read failed for message ${message.id}: ${e.message}")
            }
        }
        if (marked.isEmpty()) return

        applyLocalRead(marked)
        println("FiberSocial: marked ${marked.size} message(s) read in thread ${thread.rootId}")
        // Strictly after the loop above: see the race note on [openThread]'s onMarkedRead.
        onMarkedRead()
    }

    /**
     * Flips [Message.readMessage] on [ids] in [accumulated] and republishes both the list
     * and the open thread from one regrouping, so the row's dot and the thread agree.
     */
    private fun applyLocalRead(ids: Set<Long>) {
        accumulated = accumulated.map {
            if (it.id in ids) it.copy(readMessage = true) else it
        }
        republish()
    }

    /**
     * Fetches the full shape for every message in the open thread that has no body, and
     * merges the bodies in.
     *
     * ## The cost, and why this is where it is paid
     *
     * `getMessages(full = true)` already asks for bodies inline, but whether that works is
     * the unresolved `output_format`-vs-`format` ambiguity documented on this class and on
     * `RavelryApiClient.getMessages`. So this is the FALLBACK the client's KDoc names:
     * [RavelryApiClient.getMessage] always returns the full shape.
     *
     * It costs up to one extra GET per message in the opened thread — and ZERO when the
     * list already carried bodies, because only body-less messages are fetched. That is
     * why the backfill lives on thread-open and not on the list: doing it there would cost
     * an extra request per *conversation in the mailbox*, on every page and every
     * pull-to-refresh, to fill a one-line preview (see this class's KDoc for why that was
     * rejected). Here it is bounded by one conversation the user explicitly asked to read,
     * a handful of messages, once per open — and it is what makes the detail screen
     * correct regardless of how the ambiguity resolves. If `full = true` turns out to
     * work, this quietly becomes a no-op.
     *
     * Fetched in parallel, unlike the mark-read loop: nothing sequences after this, and a
     * user staring at a blank body is waiting on the slowest one.
     *
     * MERGES ONLY THE BODY FIELDS, rather than replacing the message wholesale. Mark-read
     * has already run by this point and the server's copy of `read_message` may or may not
     * reflect it yet; taking the fetched message whole could resurrect the unread dot this
     * open just cleared.
     */
    private suspend fun backfillBodies(rootId: Long) {
        val open = _openThread.value?.takeIf { it.thread.rootId == rootId } ?: return
        val missing = open.thread.messages.filter { it.contentHtml.isNullOrBlank() }
        if (missing.isEmpty()) {
            updateOpen(rootId) { it.copy(loadingBodies = false) }
            return
        }
        try {
            val fetched = coroutineScope {
                missing.map { async { apiClient.getMessage(it.id) } }.awaitAll()
            }.associateBy { it.id }
            accumulated = accumulated.map { existing ->
                val full = fetched[existing.id] ?: return@map existing
                existing.copy(
                    contentHtml = full.contentHtml ?: existing.contentHtml,
                    content = full.content ?: existing.content,
                    folderName = full.folderName ?: existing.folderName,
                )
            }
            republish()
            updateOpen(rootId) { it.copy(loadingBodies = false, bodyError = null) }
            println("FiberSocial: backfilled ${fetched.size} message body/bodies in thread $rootId")
        } catch (e: SessionExpiredException) {
            println("FiberSocial: MessagesViewModel body backfill session expired")
            sessionExpirySignal.signal()
            updateOpen(rootId) { it.copy(loadingBodies = false) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // The thread stays open with whatever bodies it had: a failed backfill is a
            // partially-blank conversation, never a dead screen.
            println("FiberSocial: MessagesViewModel body backfill failed: ${e.message}")
            updateOpen(rootId) {
                it.copy(loadingBodies = false, bodyError = e.message ?: "Couldn't load messages")
            }
        }
    }

    /**
     * Re-emits the list and the open thread from the current [accumulated], via a single
     * [groupIntoThreads] pass so the two can never disagree.
     *
     * The list is only rewritten while it is [MessagesState.Loaded] — a regroup must not
     * turn an error or a first-load spinner into content behind the user's back — but the
     * open thread is updated either way, since it is showing regardless of what the list
     * behind it is doing.
     */
    private fun republish() {
        val regrouped = groupIntoThreads(accumulated, currentUsername)
        (_state.value as? MessagesState.Loaded)?.let { _state.value = it.copy(threads = regrouped) }
        _openThread.value?.let { open ->
            val refreshed = regrouped.firstOrNull { it.rootId == open.thread.rootId } ?: return
            _openThread.value = open.copy(thread = refreshed)
        }
    }

    /** Applies [transform] to the open thread, but only while [rootId] is still the open one. */
    private fun updateOpen(rootId: Long, transform: (OpenThreadState) -> OpenThreadState) {
        val open = _openThread.value ?: return
        if (open.thread.rootId != rootId) return
        _openThread.value = transform(open)
    }

    /**
     * Debounced recipient search for the new-message composer (issue #374).
     *
     * Cancelling the previous job IS the debounce: each keystroke starts a coroutine that
     * sleeps [RECIPIENT_SEARCH_DEBOUNCE_MS] before calling out, and the next keystroke kills
     * it while it is still sleeping. That also removes the out-of-order problem a plain
     * throttle leaves behind — a cancelled job cannot publish a stale result over a newer
     * one, because there is only ever one job alive.
     *
     * Queries shorter than [MIN_RECIPIENT_QUERY_LENGTH] never reach the network and reset
     * the picker to [RecipientSearchState.Idle]: a one-letter global search matches
     * thousands of people and answers nothing.
     *
     * @param query Raw text from the "To" field; trimmed here so the caller need not.
     */
    fun searchRecipients(query: String) {
        searchJob?.cancel()
        val term = query.trim()
        if (term.length < MIN_RECIPIENT_QUERY_LENGTH) {
            _recipientSearch.value = RecipientSearchState.Idle
            return
        }
        // Set BEFORE the delay, not inside the job after it: the spinner should appear on
        // the keystroke, not 300ms later, or the picker looks inert while it waits.
        _recipientSearch.value = RecipientSearchState.Searching
        searchJob = scope.launch {
            delay(RECIPIENT_SEARCH_DEBOUNCE_MS)
            _recipientSearch.value = try {
                val users = apiClient.searchUsers(term)
                println("FiberSocial: MessagesViewModel recipient search '$term' -> ${users.size}")
                RecipientSearchState.Results(users)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: MessagesViewModel recipient search session expired")
                sessionExpirySignal.signal()
                RecipientSearchState.Idle
            } catch (e: Exception) {
                println("FiberSocial: MessagesViewModel recipient search failed: ${e.message}")
                RecipientSearchState.Error("Couldn't search for people. Check your connection.")
            }
        }
    }

    /**
     * Sends a NEW private message, starting a conversation (issue #374).
     *
     * Goes through `create.json`. [sendReply] is what every answer to an existing message
     * must use instead — see its KDoc, and
     * [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.replyToMessage].
     *
     * @param recipientUsername The COMMITTED recipient's handle, from a picker selection.
     *   The composer never passes raw free text: a mistyped username fails server-side with
     *   an error that reads like a general failure.
     * @param subject Subject line, user-authored.
     * @param content Message body, user-authored.
     */
    fun sendNewMessage(recipientUsername: String, subject: String, content: String) {
        if (_sendState.value is SendMessageState.Sending) return
        val to = recipientUsername.trim()
        val trimmedSubject = subject.trim()
        val trimmedContent = content.trim()
        val problem = when {
            to.isEmpty() -> "Choose who this message is going to."
            else -> validationProblem(trimmedSubject, trimmedContent)
        }
        if (problem != null) {
            _sendState.value = SendMessageState.Error(problem)
            return
        }
        _sendState.value = SendMessageState.Sending
        scope.launch {
            runSend(parentId = null, fallbackCounterpart = RavelryUser(username = to)) {
                apiClient.sendMessage(to, trimmedSubject, trimmedContent)
            }
        }
    }

    /**
     * Replies within the conversation rooted at [rootId] (issue #374).
     *
     * ## Always `reply.json`, and never `create.json`
     *
     * This calls [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.replyToMessage] and
     * nothing else. `reply.json` is the only endpoint that sets `parent_message_id`, and that
     * pointer is the entire basis of [groupIntoThreads]. A reply routed through `create.json`
     * delivers, shows the right subject, and looks correct everywhere — until the list is
     * regrouped and the conversation has silently split in two, unfixably, because messages
     * cannot be edited. There is deliberately NO fallback to [sendNewMessage] on any path
     * here, including the one below where a reply is impossible.
     *
     * ## Which message it replies TO
     *
     * The newest INBOUND message. Ravelry requires the signed-in user to be the recipient of
     * the message being replied to — replying to one's own sent message is rejected — so an
     * arbitrary "last message in the thread" would 4xx exactly when the user's own message is
     * the newest, which is most of the time right after sending one.
     *
     * A thread with NO inbound message at all (the user opened it and nobody has answered)
     * therefore has nothing to reply to, and this reports that rather than falling back to
     * `create.json`. Following up on your own unanswered message is a genuine Ravelry
     * limitation, not something we can work around without fragmenting the thread.
     *
     * The subject comes from [replySubject] over the thread's ROOT subject, so it cannot
     * accrete `Re:` prefixes.
     *
     * @param rootId [MessageThread.rootId] of the conversation being replied to.
     * @param content Reply body, user-authored.
     */
    fun sendReply(rootId: Long, content: String) {
        if (_sendState.value is SendMessageState.Sending) return
        // Prefer the open thread: a reply is sent from inside one, and it is the copy that
        // is guaranteed current even if a refresh dropped the row from the list underneath.
        val thread = _openThread.value?.thread?.takeIf { it.rootId == rootId }
            ?: (_state.value as? MessagesState.Loaded)?.threads?.firstOrNull { it.rootId == rootId }
        if (thread == null) {
            println("FiberSocial: MessagesViewModel.sendReply($rootId) — no such thread")
            _sendState.value = SendMessageState.Error("That conversation is no longer open.")
            return
        }
        val trimmedContent = content.trim()
        val problem = validationProblem(subject = replySubject(thread.subject), content = trimmedContent)
        if (problem != null) {
            _sendState.value = SendMessageState.Error(problem)
            return
        }
        val target = thread.messages.lastOrNull {
            messageDirection(it, currentUsername) == MessageDirection.INBOUND
        }
        if (target == null) {
            // "the other person" rather than "they": the sentence below reads
            // "once <name> writes back", which needs a singular noun phrase.
            val name = thread.counterpart?.username?.takeIf { it.isNotBlank() }
                ?: "the other person"
            _sendState.value = SendMessageState.Error(
                "Ravelry only lets you reply to a message someone sent you, and this " +
                    "conversation has none yet — you can reply once $name writes back.",
            )
            return
        }
        _sendState.value = SendMessageState.Sending
        scope.launch {
            runSend(parentId = target.id, fallbackCounterpart = thread.counterpart) {
                apiClient.replyToMessage(target.id, replySubject(thread.subject), trimmedContent)
            }
        }
    }

    /**
     * Clears a finished send and the recipient picker when the composer opens or closes.
     * No-op mid-send: an in-flight result still needs somewhere to land.
     */
    fun resetCompose() {
        searchJob?.cancel()
        _recipientSearch.value = RecipientSearchState.Idle
        if (_sendState.value !is SendMessageState.Sending) _sendState.value = SendMessageState.Idle
    }

    /** Resets [sendState] from [SendMessageState.Sent] once the UI has navigated away. */
    fun acknowledgeSent() {
        if (_sendState.value is SendMessageState.Sent) _sendState.value = SendMessageState.Idle
    }

    /** Blank-field validation, shared by [sendNewMessage] and [sendReply]. `null` means valid. */
    private fun validationProblem(subject: String, content: String): String? = when {
        subject.isEmpty() -> "Add a subject before sending."
        content.isEmpty() -> "Write a message before sending."
        else -> null
    }

    /**
     * Runs [call], files the result into [accumulated], and translates every failure.
     *
     * ## The sent message reaches the screen WITHOUT a refetch
     *
     * The created message is appended to [accumulated] and everything is regrouped, so the
     * open thread and the list row behind it update from the same pass — the same mechanism
     * mark-read and the body backfill use, and the reason this lives on this class at all.
     * A blind refresh instead would re-request every page, throw away the accumulation,
     * and still be racing Ravelry's own indexing for a message sent milliseconds earlier.
     *
     * ## A 403 IS NOT A SESSION EXPIRY (issue #82)
     *
     * [ForbiddenException] is caught ahead of the generic arm and turned into an ordinary
     * composer error carrying the client's own both-causes copy. It NEVER signals
     * [sessionExpired], so it can never log the user out. Its two causes — the recipient (or
     * sender) having messaging disabled, and a token minted before `message-write` joined
     * `SCOPE` — are indistinguishable by status code; see
     * [com.myhobbyislearning.fibersocial.feed.RavelryApiClient] for the full analysis and
     * `NewMessageScreen` for why we show copy naming both rather than probing to tell them
     * apart.
     */
    private suspend fun runSend(
        parentId: Long?,
        fallbackCounterpart: RavelryUser?,
        call: suspend () -> Message,
    ) {
        _sendState.value = try {
            val sent = normalizeSent(call(), parentId, fallbackCounterpart)
            accumulated = accumulated + sent
            republish()
            println("FiberSocial: MessagesViewModel sent message ${sent.id} parent=${sent.parentMessageId}")
            SendMessageState.Sent(sent)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            // The genuine expiry, and the only failure here that logs anyone out.
            println("FiberSocial: MessagesViewModel send session expired")
            sessionExpirySignal.signal()
            SendMessageState.Idle
        } catch (e: ForbiddenException) {
            println("FiberSocial: MessagesViewModel send forbidden: ${e.message}")
            SendMessageState.Error(
                message = e.message ?: "Ravelry wouldn't deliver this message.",
                messagingBlocked = true,
            )
        } catch (e: Exception) {
            println("FiberSocial: MessagesViewModel send failed: ${e.message}")
            SendMessageState.Error(e.message ?: "Couldn't send your message.")
        }
    }

    /**
     * Fills in what Ravelry's create/reply response may leave out, so the message groups and
     * renders correctly the instant it is appended.
     *
     * Every field here is a REPAIR, never an override — a value Ravelry supplied always
     * wins, and the next refresh replaces all of this with the server's own copy:
     *
     *  - A null `sender` would make [messageDirection] report [MessageDirection.UNKNOWN],
     *    which the detail screen renders as *received* — attributing the user's own words to
     *    the other party.
     *  - A null `recipient` on a new conversation would leave the thread with no counterpart,
     *    so the list row would render "(unknown)" for someone the user just picked by name.
     *  - An absent or unparseable `sent_at` sorts the thread LAST (see [ravelryTimestamp]).
     *  - A null `parent_message_id` on a reply would root it as its own thread locally, which
     *    is the fragmented-conversation symptom [sendReply] exists to prevent — here it
     *    would be a purely local illusion, but an alarming one.
     */
    private fun normalizeSent(
        sent: Message,
        parentId: Long?,
        fallbackCounterpart: RavelryUser?,
    ): Message = sent.copy(
        sender = sent.sender ?: currentUsername.takeIf { it.isNotBlank() }?.let { RavelryUser(it) },
        recipient = sent.recipient ?: fallbackCounterpart,
        sentAt = sent.sentAt?.takeIf { parseRavelryTimestamp(it) != null } ?: ravelryTimestamp(now()),
        parentMessageId = sent.parentMessageId ?: parentId,
    )

    private fun loadedState(): MessagesState.Loaded = MessagesState.Loaded(
        threads = groupIntoThreads(accumulated, currentUsername),
        hasMore = !pagingBlocked && cursors.values.any { it.hasMore },
        loadingMore = false,
        refreshing = false,
    )

    private fun freshCursors(): Map<MessageFolder, FolderCursor> =
        LISTED_FOLDERS.associateWith { FolderCursor() }
}
