@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.feed

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.ui.GroupBadge

/**
 * Browse and search Ravelry's group directory, and join from the results (issue #232).
 *
 * Replaces the link-out to Ravelry's website that "Find groups" used to be. Native rather
 * than a web view on purpose: the search and the join both already have first-class calls
 * behind them, so a web view would only add a second visual language and a second session
 * to keep straight — and on iOS, sending people to a browser for this class of task is
 * what App Review rejected under guideline 4 (issue #481).
 *
 * State and callbacks rather than the ViewModel itself, matching ProjectPageScreen and
 * the rest of the screens here: it keeps this renderable from a Robolectric test without
 * standing up an HTTP stack, and the composeApp test source set has no ktor mock anyway.
 *
 * @param joinedPermalinks Groups joined during this visit — Ravelry's results carry no
 *   membership flag, so this is the only thing that can flip a row to "Joined".
 * @param onOpenGroup Opens a group's page. Offered for every result, joined or not,
 *   because Ravelry group pages are readable before joining.
 */
@Composable
fun GroupSearchScreen(
    state: GroupSearchState,
    query: String,
    onQueryChange: (String) -> Unit,
    onBack: () -> Unit,
    joiningPermalink: String? = null,
    joinedPermalinks: Set<String> = emptySet(),
    joinError: String? = null,
    onRetry: () -> Unit = {},
    onLoadMore: () -> Unit = {},
    onJoin: (Group) -> Unit = {},
    onDismissJoinError: () -> Unit = {},
    onOpenGroup: (Group) -> Unit = {},
) {
    BackHandler(onBack = onBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Find groups") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                label = { Text("Search groups") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .testTag("GroupSearchField"),
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
            when (val s = state) {
                is GroupSearchState.Loading -> CenteredBox { CircularProgressIndicator() }

                is GroupSearchState.Error -> CenteredBox {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(s.message, style = MaterialTheme.typography.bodyLarge)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = onRetry) { Text("Try again") }
                    }
                }

                is GroupSearchState.Loaded -> if (s.groups.isEmpty()) {
                    CenteredBox {
                        Text(
                            // Only reachable with a query: the browse listing is never
                            // empty, so naming the query is always the useful message.
                            text = "No groups match \"${s.query}\".",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(s.groups, key = { it.id }) { group ->
                            GroupResultRow(
                                group = group,
                                isJoining = joiningPermalink == group.permalink,
                                isJoined = group.permalink in joinedPermalinks,
                                onJoin = { onJoin(group) },
                                onOpen = { onOpenGroup(group) },
                            )
                            HorizontalDivider()
                        }
                        if (s.hasMore) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(16.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    if (s.loadingMore) {
                                        CircularProgressIndicator(Modifier.size(24.dp))
                                    } else {
                                        TextButton(onClick = onLoadMore) {
                                            Text("Load more")
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
}

@Composable
private fun GroupResultRow(
    group: Group,
    isJoining: Boolean,
    isJoined: Boolean,
    onJoin: () -> Unit,
    onOpen: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen, onClickLabel = "Open ${group.name}", role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GroupBadge(group, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            group.shortDescription?.takeIf { it.isNotBlank() }?.let { description ->
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        when {
            // Ravelry's results carry no membership flag, so "Joined" only reflects joins
            // made here. A group the user was already in still shows Join; tapping it is
            // harmless (Ravelry treats a repeat join as a no-op) and beats claiming a
            // membership state the API never told us about.
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
                onClick = onJoin,
                modifier = Modifier.testTag("Join-${group.permalink}"),
            ) { Text("Join") }
        }
    }
}

@Composable
private fun CenteredBox(content: @Composable () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) { content() }
    }
}
