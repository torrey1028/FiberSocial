package com.autom8ed.fibersocial.feed

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(viewModel: FeedAndroidViewModel) {
    val state by viewModel.feed.state.collectAsState()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val title = when (val s = state) {
        is FeedState.Loaded -> s.selectedGroup?.name ?: "All Groups"
        is FeedState.Refreshing -> s.stale.selectedGroup?.name ?: "All Groups"
        else -> "FiberSocial"
    }

    val groups = when (val s = state) {
        is FeedState.Loaded -> s.groups
        is FeedState.Refreshing -> s.stale.groups
        else -> emptyList()
    }

    val selectedGroup = when (val s = state) {
        is FeedState.Loaded -> s.selectedGroup
        is FeedState.Refreshing -> s.stale.selectedGroup
        else -> null
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GroupDrawer(
                groups = groups,
                selectedGroup = selectedGroup,
                onGroupSelected = { group ->
                    scope.launch { drawerState.close() }
                    viewModel.feed.selectGroup(group)
                },
            )
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(Icons.Default.Menu, contentDescription = "Select group")
                        }
                    },
                )
            },
        ) { padding ->
            when (val s = state) {
                FeedState.Loading -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                is FeedState.Error -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) {
                    val message = if (s.message.contains("403") || s.message.contains("401")) {
                        "Session expired. Please log out and sign in again."
                    } else {
                        "Couldn't load the feed. Check your connection and try again."
                    }
                    Text(message, color = MaterialTheme.colorScheme.error)
                }

                is FeedState.Loaded -> FeedList(s.items, Modifier.padding(padding))
                is FeedState.Refreshing -> FeedList(s.stale.items, Modifier.padding(padding))
            }
        }
    }
}

@Composable
private fun GroupDrawer(
    groups: List<Group>,
    selectedGroup: Group?,
    onGroupSelected: (Group?) -> Unit,
) {
    ModalDrawerSheet {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "Your Groups",
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        )
        NavigationDrawerItem(
            label = { Text("All Groups") },
            selected = selectedGroup == null,
            onClick = { onGroupSelected(null) },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        if (groups.isNotEmpty()) {
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp, horizontal = 12.dp))
            groups.forEach { group ->
                NavigationDrawerItem(
                    label = { Text(group.name) },
                    selected = selectedGroup?.id == group.id,
                    onClick = { onGroupSelected(group) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun FeedList(items: List<FeedItem>, modifier: Modifier = Modifier) {
    val renderable = items.filterIsInstance<FeedItem.DiscussionPost>()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(renderable, key = { it.id }) { item ->
            DiscussionPostCard(
                item = item,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
