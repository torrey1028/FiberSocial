@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.feed

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.myhobbyislearning.fibersocial.feed.html.parseBodyDocument
import com.myhobbyislearning.fibersocial.feed.html.parseSummaryDocument
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.ui.Avatar
import com.myhobbyislearning.fibersocial.profile.UsernameLink
import com.myhobbyislearning.fibersocial.ui.DeleteConfirmDialog
import com.myhobbyislearning.fibersocial.ui.ErrorText
import com.myhobbyislearning.fibersocial.ui.MessageComposer
import com.myhobbyislearning.fibersocial.ui.SendingSpinner
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.feed.models.VoteType
import com.myhobbyislearning.fibersocial.feed.models.hasVoted
import com.myhobbyislearning.fibersocial.feed.models.voteCount
import com.myhobbyislearning.fibersocial.ui.PullToRefreshBox
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TopicDetailScreen(
    topic: FeedItem,
    postsState: TopicDetailState,
    onBack: () -> Unit,
    onVote: (Post, VoteType) -> Unit,
    currentUsername: String? = null,
    deleteState: DeleteState = DeleteState.Idle,
    onDeletePost: (Post) -> Unit = {},
    onDeleteErrorShown: () -> Unit = {},
    editState: EditState = EditState.Idle,
    onEditPost: (Post, String) -> Unit = { _, _ -> },
    onEditErrorShown: () -> Unit = {},
    replyState: ReplyState = ReplyState.Idle,
    onSendReply: (String) -> Unit = {},
    onReplySent: () -> Unit = {},
    onRefresh: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    // Loads forward pages until the given post number is present, so "jump to last read"
    // can reach a target deep in a long thread that isn't loaded yet (issue #205).
    onLoadUntil: (Int) -> Unit = {},
    // Called with the furthest post number the user scrolled to, as they leave the thread
    // (issue #206) — drives the read-marker sync and the feed card's unread badge.
    onMarkRead: (Int) -> Unit = {},
    attachment: ImageAttachmentState = ImageAttachmentState.Idle,
    onImagePicked: (String) -> Unit = {},
    onAttachmentInserted: () -> Unit = {},
    onPickFromProjects: (() -> Unit)? = null,
    // Where to seed the reply list's scroll position on (re)entry, and where to report it
    // as the user scrolls (issue #243). Sourced from TopicDetailViewModel, which survives
    // this composable being torn down and recomposed — e.g. while a tapped project link's
    // page is shown over it — unlike a plain `rememberLazyListState()` here would.
    initialScrollPosition: ScrollPosition = ScrollPosition.TOP,
    onScrollPositionChanged: (index: Int, offset: Int) -> Unit = { _, _ -> },
) {
    var pendingDelete by remember { mutableStateOf<Post?>(null) }
    pendingDelete?.let { post ->
        DeleteConfirmDialog(
            itemLabel = "post",
            container = "topic",
            onConfirm = { onDeletePost(post) },
            onDismiss = { pendingDelete = null },
        )
    }
    if (deleteState is DeleteState.Error) {
        PostActionErrorDialog(
            title = "Couldn't delete the post",
            message = deleteState.message,
            onDismiss = onDeleteErrorShown,
        )
    }
    // Furthest post number the user has scrolled to this visit (issue #206): the read
    // marker follows how far they actually got, not how many posts the app fetched. A
    // high-water mark, so scrolling down then back up still counts the deepest post seen.
    // Reset per topic so a new thread starts from nothing.
    var furthestSeen by remember(topic.id) { mutableStateOf(0) }
    // Set when the user taps "mark all as read" (issue #227): advances the marker to the
    // last post without scrolling, and hides the jump-to-last-read button afterwards.
    var markedAllRead by remember(topic.id) { mutableStateOf(false) }
    // Every way out of the thread (system back, top-bar arrow) syncs the marker first.
    val handleBack: () -> Unit = {
        onMarkRead(furthestSeen)
        onBack()
    }
    // The system back button must mirror the top-bar back arrow instead of
    // finishing the activity (issue #38).
    BackHandler(onBack = handleBack)

    // Pull-to-refresh calls onRefresh(), which re-triggers load(topic.id); that briefly
    // reports TopicDetailState.Loading again. Falling back to the last Loaded snapshot
    // while isRefreshing is in flight keeps the reply thread on screen under the compact
    // pull spinner instead of replacing it with the mid-list loading indicator.
    var isRefreshing by remember(topic.id) { mutableStateOf(false) }
    var lastLoaded by remember(topic.id) { mutableStateOf<TopicDetailState.Loaded?>(null) }
    LaunchedEffect(postsState) {
        if (postsState is TopicDetailState.Loaded) lastLoaded = postsState
        if (postsState !is TopicDetailState.Loading) isRefreshing = false
    }
    val displayState = if (postsState is TopicDetailState.Loading && lastLoaded != null) {
        lastLoaded!!
    } else {
        postsState
    }

    // The post currently being edited, if any. Drives the bottom edit bar (below) instead
    // of a modal dialog so the text field and its confirm/cancel controls sit directly
    // above the keyboard rather than being covered by it. Saveable as an ID so the bar
    // (and its draft) survives rotation; resolving through the loaded posts also
    // auto-dismisses it if the post vanishes. Reads displayState, not postsState — the
    // pull-to-refresh fallback above exists precisely so a transient Loading during a
    // refresh doesn't yank the loaded content (and here, the open edit bar) off screen.
    var editingPostId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Hoisted: the bottomBar swaps ReplyComposer out for the EditBar, and a branch
    // swap disposes composition state (rememberSaveable does not restore across
    // leave/re-enter) — an in-progress reply draft must survive a quick edit.
    var replyDraft by rememberSaveable { mutableStateOf("") }
    val editingPost = (displayState as? TopicDetailState.Loaded)
        ?.posts?.firstOrNull { it.id == editingPostId }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(topic.groupName) },
                navigationIcon = {
                    IconButton(onClick = handleBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Mark the whole topic read without scrolling to the bottom (issue #227).
                    // Only offered while something is still unread and not already cleared.
                    if (topic.unreadCount > 0 && !markedAllRead) {
                        var menuOpen by remember { mutableStateOf(false) }
                        IconButton(onClick = { menuOpen = true }) {
                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                        }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Mark all as read") },
                                onClick = {
                                    menuOpen = false
                                    onMarkRead(topic.postCount)
                                    markedAllRead = true
                                },
                            )
                        }
                    }
                },
            )
        },
        bottomBar = {
            val editing = editingPost
            if (editing != null) {
                EditBar(
                    post = editing,
                    editState = editState,
                    onSave = { newBody -> onEditPost(editing, newBody) },
                    onClose = {
                        onEditErrorShown()
                        editingPostId = null
                    },
                )
            } else {
                ReplyComposer(
                    text = replyDraft,
                    onTextChange = { replyDraft = it },
                    replyState = replyState,
                    onSend = onSendReply,
                    onSent = onReplySent,
                    attachment = attachment,
                    onImagePicked = onImagePicked,
                    onAttachmentInserted = onAttachmentInserted,
                    onPickFromProjects = onPickFromProjects,
                )
            }
        },
    ) { padding ->
        // Seeded from the ViewModel (issue #243) so a re-entry after the composable was torn
        // down (e.g. a project page shown over it) lands back where the user left off instead
        // of at the top. rememberLazyListState only reads these initial* values the first time
        // this composable enters composition, matching when they need to apply.
        val listState = rememberLazyListState(
            initialFirstVisibleItemIndex = initialScrollPosition.index,
            initialFirstVisibleItemScrollOffset = initialScrollPosition.offset,
        )
        LaunchedEffect(listState) {
            snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
                .collect { (index, offset) -> onScrollPositionChanged(index, offset) }
        }
        val jumpScope = rememberCoroutineScope()
        // "Jump to last read" targets the first unread post's number when there's a real
        // one to resume from (issue #185); it targets the very last post instead when
        // either nothing is unread, or NOTHING has ever been read (firstUnread == post 1,
        // where "resuming" at post 1 is meaningless — the user is already there, and the
        // genuinely useful jump is to the most recent activity). Same button, same label,
        // same behavior either way. The header is list index 0 and posts follow, so post
        // number N is list index N.
        val firstUnread = topic.firstUnreadPostNumber
        val jumpTarget = if (firstUnread == null || firstUnread <= 1) topic.postCount else firstUnread
        // Once the user has scrolled deeper than the original jumpTarget, that deeper point
        // (furthestSeen, below) becomes the more useful thing to jump back to — otherwise
        // scrolling back up from post 200 after reading past post 50's original marker finds
        // no way back to 200 (issue #257). Read directly inside showJump's derivedStateOf
        // below (not via this val) so its remember(jumpTarget)-cached calculation still
        // reacts to furthestSeen changing on its own. Safe to use here for the scroll
        // targets: unlike that calculation, these aren't cached across recompositions.
        val effectiveJumpTarget = maxOf(jumpTarget, furthestSeen)
        // A deep jump may have to wait for pages to load in before it can scroll there
        // (issue #205); while it does, the button shows a spinner and this stays true.
        var pendingJump by remember(topic.id) { mutableStateOf(false) }
        // The index of the last visible item — shared by the jump button's visibility gate,
        // the load-more trigger, and the furthest-seen high-water mark below, which each
        // independently recomputed this identically (issue #271).
        val lastVisibleIndex by remember {
            derivedStateOf { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
        }
        // Suppresses the furthestSeen tracker (below) while a programmatic jump is in
        // flight. scrollToItem/animateScrollToItem land the target at the TOP of the
        // viewport, transiently showing posts well past it at the bottom BEFORE
        // snapTargetToBottom corrects it — and furthestSeen is a high-water mark, so even
        // that one transient frame would latch it past the target permanently, immune to
        // the correction that follows (issue #257 follow-up, found on-device). The jump's
        // own completion explicitly sets furthestSeen once landed (see below) instead.
        var isJumping by remember(topic.id) { mutableStateOf(false) }
        // Show the jump button until the effective jump target is already visible on screen,
        // or while a deep jump is still loading the pages in between. Gated on jumpTarget,
        // not unconditionally on the topic's very last post (postCount): on a long-running
        // thread (hundreds of posts over months), postCount can sit far beyond the real
        // target, so the button would otherwise linger — and visually overlap content —
        // long after the user has already scrolled past what they needed to catch up on.
        // (issue #255's original complaint — "you can see the whole topic" — is still
        // covered: for a short, fully-read topic, jumpTarget IS postCount, so the two
        // checks agree there.)
        val showJump by remember(jumpTarget) {
            derivedStateOf {
                if (markedAllRead) return@derivedStateOf false
                if (pendingJump) return@derivedStateOf true
                lastVisibleIndex < maxOf(jumpTarget, furthestSeen)
            }
        }
        // Load the next page as the user nears the end of the thread (issue #202). The
        // ViewModel no-ops unless there's actually more to load and none is already in
        // flight, so firing a little early (within a few items) just keeps scrolling smooth.
        val loaded = displayState as? TopicDetailState.Loaded
        val shouldLoadMore by remember(loaded?.hasMore, loaded?.isLoadingMore) {
            derivedStateOf {
                if (loaded == null || !loaded.hasMore || loaded.isLoadingMore) return@derivedStateOf false
                val total = listState.layoutInfo.totalItemsCount
                total > 0 && lastVisibleIndex >= total - 3
            }
        }
        LaunchedEffect(shouldLoadMore) {
            if (shouldLoadMore) onLoadMore()
        }
        // Advance the furthest-seen high-water mark as the user scrolls (issue #206). The
        // header is index 0 and posts occupy 1..postCount, so a last-visible index of N
        // means post N was shown; clamp so the trailing footer row (index postCount+1)
        // doesn't over-count. rememberUpdatedState keeps the post count current as pages
        // load without restarting the snapshot collector.
        val postCount = (displayState as? TopicDetailState.Loaded)?.posts?.size ?: 0
        val postCountState = rememberUpdatedState(postCount)
        // A deep jump-to-newest may also have to wait for pages to load (mirrors
        // pendingJump above, but always loads to the true end rather than a specific
        // post number — see the LaunchedEffect below).
        var pendingJumpToNewest by remember(topic.id) { mutableStateOf(false) }
        // "Jump to newest" (issue #309 follow-up) is a distinct action from "jump to last
        // read": it's always available as a plain "scroll to the end" shortcut regardless
        // of read state — unlike showJump, NOT gated on markedAllRead, since skipping to
        // the end is a navigation aid, not a read-tracking one. Shown whenever the true
        // last post isn't on screen yet, including while further pages remain to load.
        // Keyed on loaded?.hasMore and postCount (mirrors shouldLoadMore below): both are
        // plain vals derived from displayState, not genuine State reads, so a key-less
        // remember would freeze this derivedStateOf's closure on whatever they were on the
        // FIRST composition — typically Loading, i.e. loaded=null/postCount=0 — and never
        // see the topic actually finish loading (found on-device: the button never
        // appeared at all).
        val showJumpToNewest by remember(loaded?.hasMore, postCount) {
            derivedStateOf {
                if (pendingJumpToNewest) return@derivedStateOf true
                loaded?.hasMore == true || lastVisibleIndex < postCount
            }
        }
        LaunchedEffect(listState, topic.id) {
            snapshotFlow { lastVisibleIndex }
                .collect { lastIndex ->
                    if (isJumping) return@collect
                    val seen = lastIndex.coerceIn(0, postCountState.value)
                    if (seen > furthestSeen) furthestSeen = seen
                }
        }
        // Finish a pending deep jump once the load-more driving it settles — reached the
        // target, hit the end of the thread, or errored (all clear isLoadingMore). Land on
        // the target (or as far as we got) and drop the spinner (issue #205). Uses
        // scrollToItem, not animateScrollToItem: animating across hundreds of posts would
        // compose and parse every one it flies past (janky); a jump should land instantly.
        LaunchedEffect(pendingJump, loaded?.isLoadingMore) {
            if (pendingJump && loaded?.isLoadingMore == false) {
                isJumping = true
                val target = effectiveJumpTarget.coerceIn(0, postCount)
                listState.scrollToItem(target)
                listState.snapTargetToBottom(target)
                if (target > furthestSeen) furthestSeen = target
                isJumping = false
                pendingJump = false
            }
        }
        // Mirrors the pendingJump effect above, but for jump-to-newest: onLoadUntil is
        // called with Int.MAX_VALUE (below) so TopicDetailViewModel.loadUntilPost's own
        // "while (loaded.hasMore) load another page" loop naturally keeps going until
        // hasMore is false — i.e. until the true end, whatever post number that turns out
        // to be — rather than stopping at a specific known target.
        LaunchedEffect(pendingJumpToNewest, loaded?.isLoadingMore) {
            if (pendingJumpToNewest && loaded?.isLoadingMore == false) {
                isJumping = true
                val target = postCount
                listState.scrollToItem(target.coerceIn(0, postCount))
                listState.snapTargetToBottom(target.coerceIn(0, postCount))
                if (target > furthestSeen) furthestSeen = target
                isJumping = false
                pendingJumpToNewest = false
            }
        }
        // Whether the FAB below is showing its two-way chooser (issue #309 follow-up:
        // "last read" and "newest" are distinct destinations, both potentially useful
        // regardless of read state — rather than two separately-floating buttons, which
        // read as competing/cluttered since "newest" differs from "last read" for the
        // WHOLE time you're catching up on a thread, not just occasionally, a single
        // entry point that expands into a choice keeps only one control on screen at once).
        var jumpMenuExpanded by remember(topic.id) { mutableStateOf(false) }
        val jumpToLastRead: () -> Unit = {
            if (!pendingJump) {
                val count = loaded?.posts?.size ?: 0
                if (count >= effectiveJumpTarget || loaded?.hasMore != true) {
                    // Target already loaded (or nothing more to load): scroll now.
                    jumpScope.launch {
                        isJumping = true
                        val target = effectiveJumpTarget.coerceIn(0, count)
                        listState.animateScrollToItem(target)
                        listState.snapTargetToBottom(target)
                        if (target > furthestSeen) furthestSeen = target
                        isJumping = false
                    }
                } else {
                    // Target is pages away: load forward, then the effect above scrolls.
                    pendingJump = true
                    onLoadUntil(effectiveJumpTarget)
                }
            }
        }
        val jumpToNewest: () -> Unit = {
            if (!pendingJumpToNewest) {
                if (loaded?.hasMore != true) {
                    // Already fully loaded: scroll now.
                    jumpScope.launch {
                        isJumping = true
                        val target = postCount
                        listState.animateScrollToItem(target.coerceIn(0, postCount))
                        listState.snapTargetToBottom(target.coerceIn(0, postCount))
                        if (target > furthestSeen) furthestSeen = target
                        isJumping = false
                    }
                } else {
                    // More pages remain: load everything, then the effect above scrolls.
                    // MAX_VALUE makes loadUntilPost's own loop run until hasMore is false
                    // rather than stopping at some specific (not yet known) post number.
                    pendingJumpToNewest = true
                    onLoadUntil(Int.MAX_VALUE)
                }
            }
        }
        Box(modifier = Modifier.padding(padding)) {
        PullToRefreshBox(
            refreshing = isRefreshing,
            onRefresh = { isRefreshing = true; onRefresh() },
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                // Extra bottom room while either jump FAB floats over the list (issue #255
                // follow-up): without it, the FAB visually sits on top of the last couple
                // of posts, and a scroll gesture starting on that overlapped area doesn't
                // reach the LazyColumn — it can register as a tap on the FAB instead,
                // firing an unwanted jump back to the target mid-scroll.
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 12.dp,
                    bottom = if (showJump || showJumpToNewest) 96.dp else 12.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                item(key = "header") {
                    Text(topic.title, style = MaterialTheme.typography.titleLarge)
                    // The topic's author-written summary belongs with the title.
                    if (topic.bodySummaryHtml.isNotBlank() || topic.bodySummary.isNotBlank()) {
                        Spacer(Modifier.height(8.dp))
                        PostBody(document = remember(topic) { topic.parseSummaryDocument() })
                    }
                    Spacer(Modifier.height(12.dp))
                    AuthorRow(user = topic.author, timestamp = topic.lastPostAt)
                    Spacer(Modifier.height(16.dp))
                    HorizontalDivider()
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = "💬 ${topic.postCount} ${if (topic.postCount == 1) "post" else "posts"}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                when (displayState) {
                    is TopicDetailState.Loading -> item(key = "loading") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 32.dp),
                            contentAlignment = Alignment.Center,
                        ) { CircularProgressIndicator() }
                    }
                    is TopicDetailState.Error -> item(key = "error") {
                        Text(
                            text = "Couldn't load replies. Check your connection and try again.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(vertical = 16.dp),
                        )
                    }
                    is TopicDetailState.Loaded -> items(
                        displayState.posts,
                        key = { it.id },
                    ) { post ->
                        val mine = currentUsername != null && post.user?.username == currentUsername
                        ReplyItem(
                            post = post,
                            onVote = { type -> onVote(post, type) },
                            canDelete = mine,
                            deleting = (deleteState as? DeleteState.Deleting)?.postId == post.id,
                            onDelete = { pendingDelete = post },
                            // Optimistic: show edit when Ravelry says editable OR hasn't decided
                            // yet (null on a just-created post). Only an explicit false hides it.
                            // See Post.editable and issue #82 for the non-editable-null 403 gap.
                            canEdit = mine && post.editable != false,
                            saving = (editState as? EditState.Saving)?.postId == post.id,
                            // One delete at a time (the ViewModel enforces it): while a
                            // delete is in flight, other posts' actions are disabled
                            // instead of silently dropping taps.
                            actionsEnabled = deleteState !is DeleteState.Deleting,
                            onEdit = {
                                // Opening (or switching) the bar consumes any stale error —
                                // post A's failure must not render inside post B's bar.
                                onEditErrorShown()
                                editingPostId = post.id
                            },
                        )
                        HorizontalDivider()
                    }
                }

                // Bottom-of-thread affordance (issue #202): a spinner while the next page
                // loads, then a one-time "all caught up" marker once the newest post is in.
                if (displayState is TopicDetailState.Loaded && displayState.posts.isNotEmpty()) {
                    item(key = "footer") {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 20.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            when {
                                displayState.isLoadingMore ->
                                    CircularProgressIndicator(modifier = Modifier.size(28.dp))
                                !displayState.hasMore -> Text(
                                    text = "You're all caught up",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        // Single entry point for both jump destinations (issue #309 follow-up): tapping it
        // opens a chooser rather than jumping directly, so only one control ever floats
        // over the list — "last read" and "newest" only ever differ for the WHOLE time
        // you're catching up on a thread, not occasionally, so two simultaneously-visible
        // buttons read as permanently cluttered/competing rather than clarifying anything.
        if (showJump || showJumpToNewest) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 16.dp),
            ) {
                val pending = pendingJump || pendingJumpToNewest
                FloatingActionButton(
                    onClick = { if (!pending) jumpMenuExpanded = true },
                ) {
                    if (pending) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    } else {
                        Icon(Icons.Default.KeyboardArrowDown, contentDescription = "Jump")
                    }
                }
                DropdownMenu(
                    expanded = jumpMenuExpanded,
                    onDismissRequest = { jumpMenuExpanded = false },
                    // DropdownMenu's default position starts flush with its anchor's LEFT
                    // edge and grows rightward, so anchored to the (56.dp-wide) FAB alone
                    // it renders well off to the right of it rather than centered. Nudges
                    // it left by roughly (typical menu width − FAB width) / 2 so it reads
                    // as centered under the arrow instead. Static/approximate rather than
                    // measured — the two menu items are fixed, known strings, not
                    // arbitrary-length content, so a tuned constant is a reasonable
                    // trade-off against the complexity of measuring the popup's actual
                    // rendered width. Re-tune if the item labels ever change meaningfully.
                    offset = DpOffset(x = (-40).dp, y = 0.dp),
                ) {
                    if (showJump) {
                        DropdownMenuItem(
                            text = { Text("Jump to last read") },
                            onClick = {
                                jumpMenuExpanded = false
                                jumpToLastRead()
                            },
                        )
                    }
                    if (showJumpToNewest) {
                        DropdownMenuItem(
                            text = { Text("Jump to newest") },
                            onClick = {
                                jumpMenuExpanded = false
                                jumpToNewest()
                            },
                        )
                    }
                }
            }
        }
        }
    }
}

/**
 * Nudges the list so [index] sits at the BOTTOM of the viewport, correcting a scroll that
 * already landed [index] at the top (`scrollToItem`/`animateScrollToItem`'s default).
 *
 * Landing the jump target at the top pulls a whole extra screenful of posts the user hasn't
 * actually read into view below it. [furthestSeen] tracks the LAST visible item, so those
 * freshly-visible-but-unread posts immediately count as "seen" too — advancing the read
 * marker past where the user actually left off, and making repeated jumps creep further and
 * further ahead each time (issue #257 follow-up, found on-device). Anchoring the target to
 * the bottom instead means nothing past it is visible until the user actually scrolls there.
 *
 * No-ops if [index] isn't currently laid out (shouldn't happen right after scrolling to it,
 * but layout info can theoretically lag a frame) or doesn't need to move (already at/above
 * the bottom edge — e.g. a target near the very end of a short thread).
 */
private suspend fun LazyListState.snapTargetToBottom(index: Int) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.find { it.index == index } ?: return
    // viewportEndOffset is the physical viewport's bottom edge — NOT reduced by
    // afterContentPadding, which only extends how far you can overscroll past the true
    // last item and has no bearing on a middle item's alignment. Subtracting it here would
    // align to a boundary that itself moves once the jump button's own reserved bottom
    // padding shrinks back down after showJump flips off below, reintroducing drift.
    val viewportEnd = info.viewportEndOffset
    // Positive when the item's bottom is above the viewport's bottom edge (the common case
    // right after landing it at the TOP) — closing that gap means bringing EARLIER items
    // into view above it, i.e. scrolling backward, hence the negated delta.
    val gap = viewportEnd - (item.offset + item.size)
    if (gap > 0) scrollBy(-gap.toFloat())
}

@Composable
internal fun ReplyItem(
    post: Post,
    onVote: (VoteType) -> Unit,
    canDelete: Boolean = false,
    deleting: Boolean = false,
    onDelete: () -> Unit = {},
    canEdit: Boolean = false,
    saving: Boolean = false,
    actionsEnabled: Boolean = true,
    onEdit: () -> Unit = {},
) {
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f)) {
                AuthorRow(user = post.user, timestamp = post.createdAt)
            }
            if (deleting || saving) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp))
            } else {
                if (canEdit) {
                    IconButton(onClick = onEdit, enabled = actionsEnabled) {
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit post",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (canDelete) {
                    IconButton(onClick = onDelete, enabled = actionsEnabled) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete post",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        if (post.body.isNotBlank() || post.bodyHtml.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            PostBody(document = remember(post) { post.parseBodyDocument() })
        }
        Spacer(Modifier.height(8.dp))
        VoteRow(post = post, onVote = onVote)
    }
}

/**
 * Edit bar that replaces the reply composer in the bottom bar while a post is being
 * edited, so its text field and confirm/cancel controls stay above the keyboard.
 * Auto-closes once the save for this post succeeds; on failure it stays open with the
 * edited text preserved and an inline error.
 */
@Composable
private fun EditBar(
    post: Post,
    editState: EditState,
    onSave: (String) -> Unit,
    onClose: () -> Unit,
) {
    var text by rememberSaveable(post.id) { mutableStateOf(post.body) }
    val saving = editState is EditState.Saving && editState.postId == post.id
    val error = editState is EditState.Error
    val focusRequester = remember { FocusRequester() }

    // Close only when this post's save completes (Saving -> Idle). An Error keeps
    // the bar open. Keyed by post: leftover true from post A's in-flight save would
    // otherwise instantly close post B's freshly-opened bar (and A's later completion
    // would close whatever bar is open).
    val wasSaving = remember(post.id) { mutableStateOf(false) }
    LaunchedEffect(saving, error) {
        if (wasSaving.value && !saving && !error) onClose()
        if (saving) wasSaving.value = true
    }
    LaunchedEffect(post.id) { runCatching { focusRequester.requestFocus() } }

    Surface(tonalElevation = 3.dp) {
        // imePadding: the bar requests focus on open, so the keyboard rises
        // immediately — without this the bar sits behind the very keyboard it
        // exists to stay above.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (error) {
                ErrorText(
                    text = (editState as? EditState.Error)?.message
                        ?.ifBlank { "Couldn't save your edit. Try again." }
                        ?: "Couldn't save your edit. Try again.",
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("Editing") },
                    enabled = !saving,
                    modifier = Modifier.weight(1f).focusRequester(focusRequester),
                    maxLines = 4,
                )
                Spacer(Modifier.width(4.dp))
                if (saving) {
                    SendingSpinner()
                } else {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel edit")
                    }
                    IconButton(
                        onClick = { onSave(text) },
                        // Also gated on the global Saving state: the ViewModel runs one
                        // edit at a time and would silently drop a second post's save.
                        enabled = text.isNotBlank() && text.trim() != post.body.trim() &&
                            editState !is EditState.Saving,
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Save edit")
                    }
                }
            }
        }
    }
}

/** One-shot modal for a failed post operation; shows the real failure reason. */
@Composable
private fun PostActionErrorDialog(title: String, message: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message.ifBlank { "Check your connection and try again." }) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}

@Composable
private fun VoteRow(post: Post, onVote: (VoteType) -> Unit) {
    // Declutter (issue #219): only reactions that already have votes are shown; the rest
    // hide behind a [+] that expands them so the user can add a new reaction type. Order
    // is always VoteType.entries, so the visible reactions keep their usual arrangement.
    // When every type already has votes there's nothing to hide, so no [+] appears.
    var expanded by remember(post.id) { mutableStateOf(false) }
    val hasHidden = VoteType.entries.any { post.voteCount(it) == 0 }
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        VoteType.entries.forEach { type ->
            val count = post.voteCount(type)
            if (count > 0 || expanded) {
                VoteButton(
                    emoji = VOTE_TYPE_EMOJI.getValue(type),
                    count = count,
                    voted = post.hasVoted(type),
                    onClick = {
                        onVote(type)
                        // A freshly added reaction becomes a visible one — collapse the
                        // picker so the row settles back to just the used reactions + [+].
                        if (count == 0) expanded = false
                    },
                )
            }
        }
        if (hasHidden) {
            AddReactionButton(expanded = expanded, onClick = { expanded = !expanded })
        }
    }
}

/** The [+]/[×] pill that reveals or hides the not-yet-used reactions (issue #219). */
@Composable
private fun AddReactionButton(expanded: Boolean, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Default.Close else Icons.Default.Add,
            contentDescription = if (expanded) "Hide reactions" else "Add a reaction",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
    }
}

@Composable
private fun VoteButton(emoji: String, count: Int, voted: Boolean, onClick: () -> Unit) {
    val background = if (voted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val contentColor = if (voted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(background)
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Text(text = emoji, style = MaterialTheme.typography.labelMedium)
        if (count > 0) {
            Spacer(Modifier.width(4.dp))
            Text(text = count.toString(), style = MaterialTheme.typography.labelSmall, color = contentColor)
        }
    }
}

@Composable
private fun AuthorRow(user: RavelryUser?, timestamp: String?) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Avatar(url = user?.avatarUrl, size = 32.dp)
        Spacer(Modifier.width(8.dp))
        Column {
            val username = user?.username
            if (username != null) {
                UsernameLink(username = username, style = MaterialTheme.typography.labelMedium)
            } else {
                Text(text = "@unknown", style = MaterialTheme.typography.labelMedium)
            }
            if (timestamp != null) {
                Text(
                    text = relativeTime(timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Text field + send button pinned below the reply thread. Keeps its text on failure
 * so nothing is lost; clears it only once the reply is confirmed [ReplyState.Sent].
 */
@Composable
internal fun ReplyComposer(
    replyState: ReplyState,
    onSend: (String) -> Unit,
    onSent: () -> Unit,
    // Hoisted by the screen so the draft survives the composer being swapped out
    // for the edit bar (a bottomBar branch swap disposes composition state).
    text: String,
    onTextChange: (String) -> Unit,
    attachment: ImageAttachmentState = ImageAttachmentState.Idle,
    onImagePicked: (String) -> Unit = {},
    onAttachmentInserted: () -> Unit = {},
    onPickFromProjects: (() -> Unit)? = null,
) {
    val sending = replyState is ReplyState.Sending

    LaunchedEffect(replyState) {
        if (replyState is ReplyState.Sent) {
            onTextChange("")
            onSent()
        }
    }

    InsertAttachmentEffect(
        attachment = attachment,
        onInsert = { onTextChange(appendImageMarkdown(text, it)) },
        onInserted = onAttachmentInserted,
    )

    Surface(tonalElevation = 3.dp) {
        MessageComposer(
            text = text,
            onTextChange = onTextChange,
            sending = sending,
            placeholder = "Write a reply…",
            sendContentDescription = "Send reply",
            onSend = { onSend(text) },
            // imePadding keeps the composer above the on-screen keyboard while typing.
            modifier = Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            errorTexts = listOfNotNull(
                (replyState as? ReplyState.Error)?.message?.ifBlank { "Couldn't post your reply. Try again." },
                (attachment as? ImageAttachmentState.Error)?.message,
            ),
            // Gated on the upload too: sending mid-upload would post without the image
            // and the markdown would land in the cleared draft.
            sendEnabled = text.isNotBlank() && attachment !is ImageAttachmentState.Uploading,
            leading = {
                AttachImageButton(
                    attachment = attachment,
                    enabled = !sending,
                    onImagePicked = onImagePicked,
                    onPickFromProjects = onPickFromProjects,
                )
            },
        )
    }
}
