package com.autom8ed.fibersocial.feed

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.debug.DebugPanel
import com.autom8ed.fibersocial.events.EventDetailScreen
import com.autom8ed.fibersocial.events.EventsScreen
import com.autom8ed.fibersocial.events.GroupEvent
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(viewModel: FeedAndroidViewModel) {
    val state by viewModel.feed.state.collectAsState()
    val topicDetailState by viewModel.topicDetail.state.collectAsState()
    val eventsState by viewModel.events.state.collectAsState()
    val eventDetailState by viewModel.eventDetail.state.collectAsState()
    var selectedTopic by remember { mutableStateOf<FeedItem.DiscussionTopic?>(null) }
    var selectedEvent by remember { mutableStateOf<GroupEvent?>(null) }
    var showEvents by remember { mutableStateOf(false) }

    val groups = when (val s = state) {
        is FeedState.Loaded -> s.groups
        is FeedState.Refreshing -> s.stale.groups
        else -> emptyList()
    }

    if (selectedTopic != null) {
        TopicDetailScreen(
            topic = selectedTopic!!,
            postsState = topicDetailState,
            onBack = { selectedTopic = null },
            onVote = { post, type -> viewModel.topicDetail.toggleVote(post, type) },
        )
        return
    }

    if (selectedEvent != null) {
        EventDetailScreen(
            state = eventDetailState,
            onBack = { selectedEvent = null },
            onToggleAttendance = { viewModel.eventDetail.toggleAttendance() },
        )
        return
    }

    if (showEvents) {
        EventsScreen(
            state = eventsState,
            onBack = { showEvents = false },
            onEventClick = { groupEvent ->
                viewModel.eventDetail.load(groupEvent.event.permalink)
                selectedEvent = groupEvent
            },
        )
        return
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showDebugPanel by remember { mutableStateOf(false) }

    val title = when (val s = state) {
        is FeedState.Loaded -> s.selectedGroup?.name ?: "All Groups"
        is FeedState.Refreshing -> s.stale.selectedGroup?.name ?: "All Groups"
        else -> "FiberSocial"
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
                    actions = {
                        if (groups.isNotEmpty()) {
                            IconButton(onClick = {
                                viewModel.events.load(groups)
                                showEvents = true
                            }) {
                                Icon(Icons.Default.DateRange, contentDescription = "Upcoming events")
                            }
                        }
                        if (BuildConfig.DEBUG) {
                            IconButton(onClick = { showDebugPanel = true }) {
                                Icon(Icons.Default.Build, contentDescription = "Debug panel")
                            }
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

                is FeedState.Loaded -> FeedList(
                    items = s.items,
                    modifier = Modifier.padding(padding),
                    onTopicClick = { topic ->
                        viewModel.topicDetail.load(topic.id)
                        selectedTopic = topic
                    },
                )
                is FeedState.Refreshing -> FeedList(
                    items = s.stale.items,
                    modifier = Modifier.padding(padding),
                    onTopicClick = { topic ->
                        viewModel.topicDetail.load(topic.id)
                        selectedTopic = topic
                    },
                )
            }
        }
    }

    if (showDebugPanel) {
        DebugPanel(
            onForceSessionExpiry = { viewModel.debugForceSessionExpiry() },
            onDismiss = { showDebugPanel = false },
        )
    }
}

@Composable
private fun GroupDrawer(
    groups: List<Group>,
    selectedGroup: Group?,
    onGroupSelected: (Group?) -> Unit,
) {
    ModalDrawerSheet {
        LazyColumn {
            item {
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
                }
            }
            items(groups, key = { it.id }) { group ->
                NavigationDrawerItem(
                    label = { Text(group.name) },
                    selected = selectedGroup?.id == group.id,
                    onClick = { onGroupSelected(group) },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun FeedList(
    items: List<FeedItem>,
    modifier: Modifier = Modifier,
    onTopicClick: (FeedItem.DiscussionTopic) -> Unit,
) {
    val renderable = items.filterIsInstance<FeedItem.DiscussionTopic>()
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(renderable, key = { it.id }) { item ->
            DiscussionTopicCard(
                item = item,
                onClick = { onTopicClick(item) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }
    }
}
