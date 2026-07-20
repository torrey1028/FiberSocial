package com.myhobbyislearning.fibersocial.messages

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.relativeTimeSince
import com.myhobbyislearning.fibersocial.ui.PullToRefreshBox
import com.myhobbyislearning.fibersocial.ui.UserAvatar

/**
 * Shown for a thread whose [MessageThread.counterpart] is `null`.
 *
 * That is a real, expected state rather than a defect: both parties can be absent on a
 * system notice or where an account has been deleted, and `RavelryUser` carries no id and
 * no display name to synthesize a placeholder from (see `MessageThreads.kt`). Naming the
 * unknown is the only honest rendering — the alternative, a blank name, reads as a
 * layout bug and gives the row nothing to identify itself by.
 */
private const val UNKNOWN_COUNTERPART = "(unknown)"

/** Shown in place of a relative timestamp when a thread has no parseable `sent_at`. */
private const val UNKNOWN_TIME = "—"

/** How close to the end of the list the user must scroll before the next page is asked for. */
private const val LOAD_MORE_THRESHOLD = 3

/**
 * The Messages conversation list (issue #370, epic #365).
 *
 * Renders every [MessagesState] arm: a spinner while loading, "No messages yet" for an
 * empty mailbox, an error with a working retry, and the conversation list itself. All four
 * are wrapped in [PullToRefreshBox] so the pull gesture is available everywhere, including
 * on the error and empty screens — those two are `verticalScroll`ed for exactly that
 * reason, since pull-to-refresh only engages on a nested-scrolling child and neither fills
 * a screen on its own.
 *
 * @param state Current conversation-list state.
 * @param onRefresh Pull-to-refresh over loaded content.
 * @param onRetry Recovery from [MessagesState.Error]. Deliberately a SEPARATE callback
 *   from [onRefresh]: issue #330 is the open bug where the events screen wired its error
 *   recovery to a refresh that no-ops unless already loaded, stranding the user on
 *   "Couldn't load events" until the app was restarted. Both the Retry button and the
 *   error screen's pull gesture route here.
 * @param onLoadMore Requests the next page; fired by scroll proximity to the end.
 * @param onThreadClick Opens a conversation (the detail screen arrives with #371).
 * @param listState Hoisted so scroll position survives the caller recomposing.
 */
@Composable
fun MessagesScreen(
    state: MessagesState,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onThreadClick: (MessageThread) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    when (state) {
        MessagesState.Loading -> Box(
            modifier = modifier.fillMaxSize().testTag("MessagesLoading"),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator() }

        is MessagesState.Error -> PullToRefreshBox(
            refreshing = false,
            onRefresh = onRetry,
            modifier = modifier,
        ) { MessagesErrorState(rawMessage = state.message, onRetry = onRetry) }

        is MessagesState.Loaded -> PullToRefreshBox(
            refreshing = state.refreshing,
            onRefresh = onRefresh,
            modifier = modifier,
        ) {
            if (state.threads.isEmpty()) {
                MessagesEmptyState()
            } else {
                MessageThreadList(
                    threads = state.threads,
                    hasMore = state.hasMore,
                    loadingMore = state.loadingMore,
                    onLoadMore = onLoadMore,
                    onThreadClick = onThreadClick,
                    listState = listState,
                )
            }
        }
    }
}

/**
 * The scrolling list of conversations, with the feed's scroll-proximity paging (the same
 * `derivedStateOf` + `LaunchedEffect` shape `FeedList` uses, so both lists pre-fetch at the
 * same point rather than one waiting until the user hits the true end).
 */
@Composable
private fun MessageThreadList(
    threads: List<MessageThread>,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    onThreadClick: (MessageThread) -> Unit,
    listState: LazyListState,
    modifier: Modifier = Modifier,
) {
    val shouldLoadMore by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val lastVisibleIndex = layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            lastVisibleIndex >= layoutInfo.totalItemsCount - 1 - LOAD_MORE_THRESHOLD
        }
    }
    LaunchedEffect(shouldLoadMore, hasMore, loadingMore) {
        if (shouldLoadMore && hasMore && !loadingMore) onLoadMore()
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize().testTag("MessagesList"),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(threads, key = { it.rootId }) { thread ->
            MessageThreadRow(
                thread = thread,
                onClick = { onThreadClick(thread) },
                modifier = Modifier.padding(horizontal = 8.dp),
            )
        }
        if (loadingMore) {
            item(key = "messages-loading-more") {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp) }
            }
        }
    }
}

/**
 * One conversation: who it is with, what it is about, how the newest message starts, when
 * it was last active, and an unread dot when anything inbound is unread.
 *
 * The subject is emphasized on an unread thread — the dot alone is a 9dp target for
 * "there is something new here", and weight makes the row scannable at a glance.
 */
@Composable
internal fun MessageThreadRow(
    thread: MessageThread,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val counterpartName = thread.counterpart?.username?.takeIf { it.isNotBlank() }
        ?: UNKNOWN_COUNTERPART
    // lastActivityAt is Long? — null means NO message in the thread had a parseable
    // timestamp. Rendering that through an epoch-millis formatter would print "56y ago"
    // (i.e. 1970), so it gets its own placeholder instead.
    val timestamp = thread.lastActivityAt?.let { relativeTimeSince(it) } ?: UNKNOWN_TIME
    // The newest message is the last one: groupIntoThreads orders each thread oldest →
    // newest. Bodies are absent whenever the list call didn't carry them, which
    // messagePreviewText renders as "" and the row then omits entirely.
    val preview = remember(thread.rootId, thread.messages) {
        messagePreviewText(thread.messages.lastOrNull()?.contentHtml)
    }

    ElevatedCard(
        onClick = onClick,
        modifier = modifier.fillMaxWidth().testTag("MessageThreadRow-${thread.rootId}"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            UserAvatar(user = thread.counterpart, size = 40.dp)
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = counterpartName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (thread.hasUnread) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // The only weighted child, so a long name ellipsizes into the
                        // space the dot and timestamp don't need rather than pushing them
                        // off the row.
                        modifier = Modifier.weight(1f),
                    )
                    if (thread.hasUnread) {
                        Spacer(modifier = Modifier.width(6.dp))
                        UnreadDot()
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = timestamp,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = thread.subject.takeIf { it.isNotBlank() } ?: "(no subject)",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = if (thread.hasUnread) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (preview.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = preview,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/** Unread marker on a conversation row, matching the drawer's dot. */
@Composable
private fun UnreadDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = "Unread messages" },
    )
}

/**
 * Empty mailbox. Scrollable so the surrounding [PullToRefreshBox] keeps a nested-scrolling
 * child to engage on — a user with no messages is exactly the one who wants to pull for
 * new ones.
 */
@Composable
internal fun MessagesEmptyState(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("MessagesEmpty"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No messages yet",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
    }
}

/**
 * Messages failed to load, with a Retry that actually re-runs the load (see [MessagesScreen]
 * on issue #330). Scrollable for the same pull-to-refresh reason as [MessagesEmptyState].
 */
@Composable
internal fun MessagesErrorState(
    rawMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Mirrors FeedErrorState: an auth-shaped failure gets the "sign in again" wording,
    // everything else the connection wording. Note this is a DISPLAY heuristic on an error
    // string only — a 403 is never treated as session expiry in the data layer (issue #82),
    // where only SessionExpiredException logs the user out.
    val message = if (rawMessage.contains("403") || rawMessage.contains("401")) {
        "Session expired. Please log out and sign in again."
    } else {
        "Couldn't load your messages. Check your connection and try again."
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .testTag("MessagesError"),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = message,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
