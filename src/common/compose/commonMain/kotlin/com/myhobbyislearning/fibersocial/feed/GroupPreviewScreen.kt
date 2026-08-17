@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.ui.GroupBadge

/**
 * A group the user has not joined, shown the way a joined one is — its topic list,
 * rendered with the same [TopicCard] the feed uses — with a Join button on top
 * (issue #232).
 *
 * Reading before joining is the point: Ravelry group pages are public, and deciding
 * whether to join is much easier from the actual conversations than from a name and a
 * one-line description. Opening a topic from here works exactly as it does in the feed —
 * the host renders the topic detail over this screen.
 *
 * @param isJoined Whether the user joined during this visit. Ravelry's search results
 *   carry no membership flag, so this only reflects joins made here.
 * @param onOpenInBrowser Escape hatch to the real Ravelry page — the only place left that
 *   can show group rules, moderators and the member list, none of which the topic list
 *   carries.
 */
@Composable
fun GroupPreviewScreen(
    state: GroupPreviewState,
    onBack: () -> Unit,
    onTopicClick: (FeedItem) -> Unit = {},
    isJoining: Boolean = false,
    isJoined: Boolean = false,
    joinError: String? = null,
    onJoin: (Group) -> Unit = {},
    onDismissJoinError: () -> Unit = {},
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onOpenInBrowser: (Group) -> Unit = {},
) {
    val group = when (state) {
        is GroupPreviewState.Loading -> state.group
        is GroupPreviewState.Loaded -> state.group
        is GroupPreviewState.Error -> state.group
        GroupPreviewState.Hidden -> null
    } ?: return

    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(group.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(onClick = { onOpenInBrowser(group) }) { Text("On Ravelry") }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            JoinBar(
                group = group,
                isJoining = isJoining,
                isJoined = isJoined,
                onJoin = onJoin,
            )
            joinError?.let { message ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismissJoinError) { Text("Dismiss") }
                }
            }
            HorizontalDivider()
            when (state) {
                is GroupPreviewState.Hidden -> Unit

                is GroupPreviewState.Loading -> Centered { CircularProgressIndicator() }

                is GroupPreviewState.Error -> Centered {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(state.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRetry) { Text("Try again") }
                    }
                }

                is GroupPreviewState.Loaded -> if (state.items.isEmpty()) {
                    Centered {
                        Text(
                            "No topics in this group yet.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp),
                    ) {
                        items(state.items, key = { it.id }) { item ->
                            TopicCard(
                                item = item,
                                onClick = { onTopicClick(item) },
                                modifier = Modifier.padding(horizontal = 16.dp),
                            )
                        }
                        if (state.hasMore) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (state.loadingMore) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    } else {
                                        TextButton(onClick = onLoadMore) { Text("Load more") }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun JoinBar(
    group: Group,
    isJoining: Boolean,
    isJoined: Boolean,
    onJoin: (Group) -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GroupBadge(group, size = 40.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                group.shortDescription?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            when {
                isJoined -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("Joined", style = MaterialTheme.typography.labelLarge)
                }

                isJoining -> CircularProgressIndicator(Modifier.size(20.dp))

                else -> Button(
                    onClick = { onJoin(group) },
                    modifier = Modifier.testTag("PreviewJoin"),
                ) { Text("Join") }
            }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) { content() }
}
