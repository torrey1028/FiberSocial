package com.autom8ed.fibersocial.feed

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.debug.DebugPanel
import com.autom8ed.fibersocial.events.EventDetailScreen
import com.autom8ed.fibersocial.events.EventsScreen
import com.autom8ed.fibersocial.events.EventsState
import com.autom8ed.fibersocial.feed.html.MarkdownPostParser
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.VoteType
import com.autom8ed.fibersocial.notifications.AndroidNotificationSettingsStore
import com.autom8ed.fibersocial.notifications.EventSyncWorker
import com.autom8ed.fibersocial.notifications.NotificationSettings
import com.autom8ed.fibersocial.settings.SettingsScreen
import com.autom8ed.fibersocial.ui.PullToRefreshBox
import com.autom8ed.fibersocial.ui.UserAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedAndroidViewModel,
    onLogout: () -> Unit,
    deepLinkEventPermalink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
) {
    val state by viewModel.feed.state.collectAsState()
    val topicDetailState by viewModel.topicDetail.state.collectAsState()
    val eventsState by viewModel.events.state.collectAsState()
    val eventDetailState by viewModel.eventDetail.state.collectAsState()
    var selectedTopic by remember { mutableStateOf<FeedItem?>(null) }
    var selectedEventPermalink by remember { mutableStateOf<String?>(null) }
    var eventsGroup by remember { mutableStateOf<Group?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var composingTopic by rememberSaveable { mutableStateOf(false) }

    // A tapped notification lands here: open the event detail directly.
    LaunchedEffect(deepLinkEventPermalink) {
        if (deepLinkEventPermalink != null) {
            // The tap must win over whatever screen is open — the early returns
            // below (topic, settings, events list) would otherwise swallow it and
            // the notification tap would visibly do nothing.
            selectedTopic = null
            showSettings = false
            eventsGroup = null
            composingTopic = false
            viewModel.eventDetail.load(deepLinkEventPermalink)
            selectedEventPermalink = deepLinkEventPermalink
            onDeepLinkConsumed()
        }
    }

    // One unwrap for every stale-capable field: Loaded directly, Refreshing via
    // its stale snapshot, anything else has no data yet.
    val loaded = when (val s = state) {
        is FeedState.Loaded -> s
        is FeedState.Refreshing -> s.stale
        else -> null
    }
    val user = loaded?.user
    val groups = loaded?.groups ?: emptyList()

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

    if (showSettings) {
        val context = LocalContext.current
        val settingsStore = remember { AndroidNotificationSettingsStore(context) }
        var pollIntervalHours by remember { mutableStateOf<Int?>(null) }
        // effective: a stale/corrupt persisted value renders as the clamped default
        // rather than an off-menu cadence the dialog can't represent.
        LaunchedEffect(Unit) { pollIntervalHours = settingsStore.load().effectivePollIntervalHours }
        val settingsScope = rememberCoroutineScope()
        SettingsScreen(
            user = user,
            onBack = { showSettings = false },
            onSignOut = onLogout,
            pollIntervalHours = pollIntervalHours,
            onPollIntervalSelected = { hours ->
                pollIntervalHours = hours
                settingsScope.launch {
                    settingsStore.save(NotificationSettings(pollIntervalHours = hours))
                    // UPDATE policy re-registers the periodic sync at the new cadence.
                    EventSyncWorker.schedulePeriodic(context, hours)
                }
            },
        )
        return
    }

    if (selectedTopic != null) {
        // Captured once: the send lambda runs later, when another handler may already
        // have nulled selectedTopic in the same frame — a !! there would crash.
        val topic = selectedTopic!!
        val replyState by viewModel.topicDetail.replyState.collectAsState()
        val deleteState by viewModel.topicDetail.deleteState.collectAsState()
        val editState by viewModel.topicDetail.editState.collectAsState()
        val currentUsername = when (val s = state) {
            is FeedState.Loaded -> s.user.username
            is FeedState.Refreshing -> s.stale.user.username
            else -> null
        }
        TopicDetailRoute(
            topic = topic,
            postsState = topicDetailState,
            replyState = replyState,
            onVote = { post, type -> viewModel.topicDetail.toggleVote(post, type) },
            onSendReply = { body -> viewModel.topicDetail.sendReply(topic.id, body) },
            onReplySent = { viewModel.topicDetail.acknowledgeReplySent() },
            onBack = { selectedTopic = null },
            // A reply bumps its topic to the top of the website's feed; refresh here so
            // the app catches up instead of showing stale ordering/last-reply data until
            // the next natural reload (issue #88). Skipped when the user merely browsed
            // and backed out, to avoid an unnecessary network call/spinner flash.
            onRefreshFeed = { viewModel.feed.refresh() },
            onRefresh = { viewModel.topicDetail.load(topic.id) },
            currentUsername = currentUsername,
            deleteState = deleteState,
            onDeletePost = { post -> viewModel.topicDetail.deletePost(post) },
            onDeleteErrorShown = { viewModel.topicDetail.acknowledgeDeleteError() },
            editState = editState,
            onEditPost = { post, newBody -> viewModel.topicDetail.editPost(post, newBody) },
            onEditErrorShown = { viewModel.topicDetail.acknowledgeEditError() },
        )
        return
    }

    if (composingTopic) {
        val newTopicState by viewModel.newTopic.state.collectAsState()
        NewTopicScreen(
            groups = groups,
            initialGroup = loaded?.selectedGroup,
            state = newTopicState,
            onBack = {
                composingTopic = false
                viewModel.newTopic.reset()
            },
            onPost = { group, title, body -> viewModel.newTopic.create(group.forumId, title, body) },
            onCreated = { topic, group ->
                composingTopic = false
                viewModel.newTopic.acknowledgeCreated()
                // Land the author inside their new topic, and refresh so the feed
                // shows it once they navigate back.
                viewModel.topicDetail.load(topic.id)
                selectedTopic = FeedItem(
                    id = topic.id,
                    groupId = group.id,
                    groupName = group.name,
                    lastPostAt = topic.repliedAt ?: topic.createdAt,
                    // The create response includes created_by_user; the signed-in user
                    // is the same person if it ever doesn't.
                    author = topic.createdByUser ?: loaded?.user ?: RavelryUser(username = "unknown"),
                    title = topic.title,
                    bodyPreview = MarkdownPostParser.plainText(topic.summary.orEmpty()).take(200),
                    bodySummary = topic.summary.orEmpty(),
                    bodySummaryHtml = topic.summaryHtml.orEmpty(),
                    replyCount = topic.postsCount,
                )
                viewModel.feed.refresh()
            },
        )
        return
    }

    if (selectedEventPermalink != null) {
        // Captured once for the same reason as `topic` above: onRefresh runs later,
        // when another handler may already have nulled selectedEventPermalink.
        val permalink = selectedEventPermalink!!
        val attendees by viewModel.eventDetail.attendees.collectAsState()
        EventDetailScreen(
            eventPermalink = permalink,
            state = eventDetailState,
            attendees = attendees,
            onBack = { selectedEventPermalink = null },
            onToggleAttendance = { viewModel.eventDetail.toggleAttendance() },
            onRefresh = { viewModel.eventDetail.load(permalink) },
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
                selectedEventPermalink = groupEvent.event.permalink
            },
            onRefresh = { viewModel.events.load(groups) },
        )
        return
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showDebugPanel by remember { mutableStateOf(false) }

    CloseDrawerOnBack(drawerState)

    val title = if (loaded == null) "FiberSocial" else loaded.selectedGroup?.name ?: "All Groups"
    val selectedGroup = loaded?.selectedGroup

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GroupDrawer(
                groups = groups,
                selectedGroup = selectedGroup,
                eventCounts = eventCounts,
                user = user,
                onGroupSelected = { group ->
                    scope.launch { drawerState.close() }
                    viewModel.feed.selectGroup(group)
                },
                onGroupEventsClick = { group ->
                    scope.launch { drawerState.close() }
                    eventsGroup = group
                },
                onSettingsClick = {
                    scope.launch { drawerState.close() }
                    showSettings = true
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
            floatingActionButton = {
                if (loaded != null && groups.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            viewModel.newTopic.reset()
                            composingTopic = true
                        },
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "New topic")
                    }
                }
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

                is FeedState.Loaded -> PullToRefreshBox(
                    refreshing = false,
                    onRefresh = { viewModel.feed.refresh() },
                    modifier = Modifier.padding(padding),
                ) {
                    FeedList(
                        items = s.items,
                        onTopicClick = { topic ->
                            viewModel.topicDetail.load(topic.id)
                            selectedTopic = topic
                        },
                    )
                }
                is FeedState.Refreshing -> PullToRefreshBox(
                    refreshing = true,
                    onRefresh = { viewModel.feed.refresh() },
                    modifier = Modifier.padding(padding),
                ) {
                    FeedList(
                        items = s.stale.items,
                        onTopicClick = { topic ->
                            viewModel.topicDetail.load(topic.id)
                            selectedTopic = topic
                        },
                    )
                }
            }
        }
    }

    if (showDebugPanel) {
        val context = LocalContext.current
        DebugPanel(
            onForceSessionExpiry = { viewModel.debugForceSessionExpiry() },
            onRunEventSync = { EventSyncWorker.runOnce(context) },
            onDismiss = { showDebugPanel = false },
        )
    }
}

/**
 * Wraps [TopicDetailScreen] with tracking of whether a reply was successfully sent during
 * this visit to the topic. If so, navigating back also invokes [onRefreshFeed] so the feed
 * catches up with the new latest-reply/bump-to-top the website would show (issue #88).
 *
 * The refresh is skipped when the user only browsed the thread and backed out without
 * replying, so a plain back-navigation doesn't trigger an unnecessary network call/spinner
 * flash on the feed.
 */
@Composable
internal fun TopicDetailRoute(
    topic: FeedItem,
    postsState: TopicDetailState,
    replyState: ReplyState,
    onVote: (Post, VoteType) -> Unit,
    onSendReply: (String) -> Unit,
    onReplySent: () -> Unit,
    onBack: () -> Unit,
    onRefreshFeed: () -> Unit,
    onRefresh: () -> Unit,
    currentUsername: String? = null,
    deleteState: DeleteState = DeleteState.Idle,
    onDeletePost: (Post) -> Unit = {},
    onDeleteErrorShown: () -> Unit = {},
    editState: EditState = EditState.Idle,
    onEditPost: (Post, String) -> Unit = { _, _ -> },
    onEditErrorShown: () -> Unit = {},
) {
    // ReplyState is transient — it flips Sent -> Idle again as soon as the composer
    // acknowledges it (see ReplyComposer/acknowledgeReplySent) — so whether a reply went
    // out has to be latched here rather than read directly off replyState at onBack time.
    // Updated inline during composition, not via LaunchedEffect: an effect only runs in a
    // later apply-changes phase, leaving a window where onBack (composed in the same pass
    // that observed Sent) could read the not-yet-updated latch if back-navigation happens
    // before that effect gets a chance to run.
    var repliedThisVisit by remember { mutableStateOf(false) }
    repliedThisVisit = trackReplySent(repliedThisVisit, replyState)
    TopicDetailScreen(
        topic = topic,
        postsState = postsState,
        onBack = {
            if (repliedThisVisit) onRefreshFeed()
            onBack()
        },
        onVote = onVote,
        replyState = replyState,
        onSendReply = onSendReply,
        onReplySent = onReplySent,
        onRefresh = onRefresh,
        currentUsername = currentUsername,
        deleteState = deleteState,
        onDeletePost = onDeletePost,
        onDeleteErrorShown = onDeleteErrorShown,
        editState = editState,
        onEditPost = onEditPost,
        onEditErrorShown = onEditErrorShown,
    )
}

/**
 * Pure decision function backing [TopicDetailRoute]'s latch: once a reply has been sent
 * during a visit, it stays "true" regardless of later [replyState] transitions (e.g. the
 * Sent -> Idle flip that follows acknowledgement) until the composable is torn down.
 */
internal fun trackReplySent(repliedThisVisit: Boolean, replyState: ReplyState): Boolean =
    repliedThisVisit || replyState is ReplyState.Sent

@Composable
internal fun GroupDrawer(
    groups: List<Group>,
    selectedGroup: Group?,
    eventCounts: Map<Long, Int>,
    user: RavelryUser?,
    onGroupSelected: (Group?) -> Unit,
    onGroupEventsClick: (Group) -> Unit,
    onSettingsClick: () -> Unit,
) {
    ModalDrawerSheet {
        Column {
            LazyColumn(modifier = Modifier.weight(1f)) {
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
            HorizontalDivider()
            ProfileFooter(user = user, onClick = onSettingsClick)
        }
    }
}

@Composable
private fun ProfileFooter(user: RavelryUser?, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick, onClickLabel = "Open settings", role = Role.Button)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        UserAvatar(user, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = user?.username ?: "Account",
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = "Settings",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
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
    onTopicClick: (FeedItem) -> Unit,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(items, key = { it.id }) { item ->
            TopicCard(
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
