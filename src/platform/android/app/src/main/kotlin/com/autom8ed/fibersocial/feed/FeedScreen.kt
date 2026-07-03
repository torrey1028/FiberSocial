package com.autom8ed.fibersocial.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.debug.DebugPanel
import com.autom8ed.fibersocial.events.EventDetailScreen
import com.autom8ed.fibersocial.events.EventsScreen
import com.autom8ed.fibersocial.events.EventsState
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
    var eventsGroup by remember { mutableStateOf<Group?>(null) }

    val groups = when (val s = state) {
        is FeedState.Loaded -> s.groups
        is FeedState.Refreshing -> s.stale.groups
        else -> emptyList()
    }

    // Scrape events in the background as soon as the groups are known so the drawer's
    // per-group calendar badges are populated by the time it opens.
    LaunchedEffect(groups) {
        if (groups.isNotEmpty()) viewModel.events.load(groups)
    }

    // Per-group upcoming-event counts for the drawer badges. Events listed by several
    // groups are attributed to the first group that listed them (see EventsViewModel).
    val eventCounts: Map<Long, Int> = when (val s = eventsState) {
        is EventsState.Loaded -> s.events.groupingBy { it.group.id }.eachCount()
        else -> emptyMap()
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
        val attendees by viewModel.eventDetail.attendees.collectAsState()
        EventDetailScreen(
            state = eventDetailState,
            attendees = attendees,
            onBack = { selectedEvent = null },
            onToggleAttendance = { viewModel.eventDetail.toggleAttendance() },
        )
        return
    }

    if (eventsGroup != null) {
        EventsScreen(
            state = eventsState,
            group = eventsGroup!!,
            onBack = { eventsGroup = null },
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

    CloseDrawerOnBack(drawerState)

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
                eventCounts = eventCounts,
                onGroupSelected = { group ->
                    scope.launch { drawerState.close() }
                    viewModel.feed.selectGroup(group)
                },
                onGroupEventsClick = { group ->
                    scope.launch { drawerState.close() }
                    eventsGroup = group
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
    eventCounts: Map<Long, Int>,
    onGroupSelected: (Group?) -> Unit,
    onGroupEventsClick: (Group) -> Unit,
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
                val eventCount = eventCounts[group.id] ?: 0
                NavigationDrawerItem(
                    label = { Text(group.name) },
                    selected = selectedGroup?.id == group.id,
                    onClick = { onGroupSelected(group) },
                    badge = if (eventCount > 0) {
                        {
                            GroupEventsBadge(
                                count = eventCount,
                                onClick = { onGroupEventsClick(group) },
                            )
                        }
                    } else null,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
    }
}

/**
 * Calendar icon + upcoming-event count shown at the trailing edge of a drawer group row.
 * Tapping it opens that group's events (the row itself still selects the feed filter).
 */
@Composable
private fun GroupEventsBadge(count: Int, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Icon(
            Icons.Default.DateRange,
            contentDescription = "Upcoming events",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = count.toString(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
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

/**
 * Closes an open navigation drawer on system back instead of letting the
 * press fall through and exit the app (issue #38). No-op while the drawer
 * is closed so normal back behavior is unaffected.
 *
 * Also enabled while the drawer is animating: isOpen tracks the *settled*
 * value, which is still Closed for the first half of the opening animation
 * and already Closed for the second half of the closing one — a back press
 * in either window would otherwise fall through and exit the app while the
 * drawer is visibly on screen (e.g. a habitual double-press of back).
 */
@Composable
internal fun CloseDrawerOnBack(drawerState: DrawerState) {
    val scope = rememberCoroutineScope()
    BackHandler(enabled = drawerState.isOpen || drawerState.isAnimationRunning) {
        scope.launch { drawerState.close() }
    }
}
