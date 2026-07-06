package com.autom8ed.fibersocial.feed

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.autom8ed.fibersocial.BuildConfig
import com.autom8ed.fibersocial.R
import com.autom8ed.fibersocial.debug.DebugPanel
import com.autom8ed.fibersocial.events.EventDetailScreen
import com.autom8ed.fibersocial.events.EventsScreen
import com.autom8ed.fibersocial.events.EventsState
import com.autom8ed.fibersocial.feed.html.MarkdownPostParser
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.projects.ProjectPhotoPickerDialog
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.VoteType
import com.autom8ed.fibersocial.notifications.EventSyncWorker
import com.autom8ed.fibersocial.notifications.KeyValueNotificationSettingsStore
import com.autom8ed.fibersocial.notifications.NotificationSettings
import com.autom8ed.fibersocial.notifications.PollCadence
import com.autom8ed.fibersocial.feedback.FeedbackScreen
import com.autom8ed.fibersocial.feedback.SupportGroup
import com.autom8ed.fibersocial.feedback.deviceContext
import com.autom8ed.fibersocial.settings.SettingsScreen
import com.autom8ed.fibersocial.settings.ThemeMode
import com.autom8ed.fibersocial.storage.NOTIFICATION_SETTINGS_PREFS_NAME
import com.autom8ed.fibersocial.storage.plainKeyValueStore
import com.autom8ed.fibersocial.ui.GroupBadge
import com.autom8ed.fibersocial.ui.PullToRefreshBox
import com.autom8ed.fibersocial.ui.appLogoResource
import com.autom8ed.fibersocial.ui.UserAvatar
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedAndroidViewModel,
    onLogout: () -> Unit,
    deepLinkEventPermalink: String? = null,
    onDeepLinkConsumed: () -> Unit = {},
    themeMode: ThemeMode? = null,
    onThemeModeSelected: (ThemeMode) -> Unit = {},
) {
    val state by viewModel.feed.state.collectAsState()
    val topicDetailState by viewModel.topicDetail.state.collectAsState()
    val eventsState by viewModel.events.state.collectAsState()
    val eventDetailState by viewModel.eventDetail.state.collectAsState()
    val joinState by viewModel.feed.joinState.collectAsState()
    var selectedTopic by remember { mutableStateOf<FeedItem?>(null) }
    var selectedEventPermalink by remember { mutableStateOf<String?>(null) }
    var eventsGroup by remember { mutableStateOf<Group?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    var composingTopic by rememberSaveable { mutableStateOf(false) }
    var sendingFeedback by rememberSaveable { mutableStateOf(false) }

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
            sendingFeedback = false
            // This path closes both composers without going through their onBack
            // handlers, so their attachment flows must be reset here too.
            viewModel.replyImage.reset()
            viewModel.newTopicImage.reset()
            viewModel.projectPicker.dismiss()
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
    // per-group calendar badges are populated by the time it opens. Keyed on group
    // membership, NOT the list instance: drag-reordering the drawer (issue #97) replaces
    // the list with the same groups in a new order, and re-scraping on that briefly
    // knocks eventsState out of Loaded — visibly blinking every calendar badge off.
    val memberGroupIds = groups.map { it.id }.toSet()
    LaunchedEffect(memberGroupIds) {
        if (groups.isNotEmpty()) viewModel.events.load(groups)
    }

    // Per-group upcoming-event counts for the drawer badges. Events listed by several
    // groups are attributed to the first group that listed them (see EventsViewModel).
    val eventCounts: Map<Long, Int> = when (val s = eventsState) {
        is EventsState.Loaded -> s.events.groupingBy { it.group.id }.eachCount()
        else -> emptyMap()
    }

    // Rendered before settings so "Send feedback" (opened from Settings) shows over it;
    // backing out returns to the still-open settings screen.
    if (sendingFeedback) {
        val feedbackState by viewModel.feedback.state.collectAsState()
        val uriHandler = LocalUriHandler.current
        val deviceInfo = remember { deviceContext() }
        FeedbackScreen(
            state = feedbackState,
            deviceInfo = deviceInfo,
            onBack = {
                sendingFeedback = false
                viewModel.feedback.reset()
            },
            onSend = { title, description, details -> viewModel.feedback.send(title, description, details) },
            onSent = {
                sendingFeedback = false
                viewModel.feedback.acknowledgeSent()
            },
            onOpenSupportGroup = {
                uriHandler.openUri("https://www.ravelry.com/groups/${SupportGroup.PERMALINK}")
            },
        )
        return
    }

    if (showSettings) {
        val context = LocalContext.current
        val settingsStore = remember {
            KeyValueNotificationSettingsStore(plainKeyValueStore(context, NOTIFICATION_SETTINGS_PREFS_NAME))
        }
        var pollCadence by remember { mutableStateOf<PollCadence?>(null) }
        // effective: a legacy stored hours value migrates to a cadence bucket rather
        // than the dialog having nothing to render.
        LaunchedEffect(Unit) { pollCadence = settingsStore.load().effectivePollCadence }
        val settingsScope = rememberCoroutineScope()
        SettingsScreen(
            user = user,
            onBack = { showSettings = false },
            onSignOut = onLogout,
            themeMode = themeMode,
            onThemeModeSelected = onThemeModeSelected,
            pollCadence = pollCadence,
            onPollCadenceSelected = { cadence ->
                pollCadence = cadence
                settingsScope.launch {
                    settingsStore.save(NotificationSettings(pollCadence = cadence))
                    // UPDATE policy re-registers the periodic sync at the new cadence.
                    EventSyncWorker.schedulePeriodic(context, cadence)
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
        val replyAttachment by viewModel.replyImage.state.collectAsState()
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
            onBack = {
                // An upload result that lands after leaving must not append into the
                // next topic's draft.
                viewModel.replyImage.reset()
                viewModel.projectPicker.dismiss()
                selectedTopic = null
            },
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
            attachment = replyAttachment,
            onImagePicked = { uri -> viewModel.attachReplyImage(uri) },
            onAttachmentInserted = { viewModel.replyImage.acknowledgeInserted() },
            onPickFromProjects = currentUsername?.let { u -> { viewModel.projectPicker.open(u) } },
        )
        ProjectPhotoPickerHost(viewModel, target = viewModel.replyImage)
        return
    }

    if (composingTopic) {
        val newTopicState by viewModel.newTopic.state.collectAsState()
        val newTopicAttachment by viewModel.newTopicImage.state.collectAsState()
        NewTopicScreen(
            groups = groups,
            initialGroup = loaded?.selectedGroup,
            state = newTopicState,
            attachment = newTopicAttachment,
            onImagePicked = { uri -> viewModel.attachNewTopicImage(uri) },
            onAttachmentInserted = { viewModel.newTopicImage.acknowledgeInserted() },
            onPickFromProjects = loaded?.user?.username?.let { u -> { viewModel.projectPicker.open(u) } },
            onBack = {
                composingTopic = false
                viewModel.newTopic.reset()
                viewModel.newTopicImage.reset()
                viewModel.projectPicker.dismiss()
            },
            onPost = { group, title, body, summary ->
                viewModel.newTopic.create(group.forumId, title, body, summary)
            },
            onCreated = { topic, group ->
                composingTopic = false
                viewModel.newTopic.acknowledgeCreated()
                viewModel.newTopicImage.reset()
                viewModel.projectPicker.dismiss()
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
        ProjectPhotoPickerHost(viewModel, target = viewModel.newTopicImage)
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

    val title = loaded?.selectedGroup?.name ?: "FiberSocial"
    val selectedGroup = loaded?.selectedGroup

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GroupDrawer(
                groups = groups,
                selectedGroup = selectedGroup,
                eventCounts = eventCounts,
                user = user,
                isFeedbackGroupMember = groups.any { it.permalink == SupportGroup.PERMALINK },
                joinState = joinState,
                onSendFeedback = {
                    scope.launch { drawerState.close() }
                    sendingFeedback = true
                },
                onJoinFeedbackGroup = { viewModel.feed.joinSupportGroup(SupportGroup.PERMALINK) },
                onGroupSelected = { group ->
                    scope.launch { drawerState.close() }
                    viewModel.feed.selectGroup(group)
                },
                onReorder = { viewModel.feed.reorderGroups(it) },
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
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Image(
                                painter = painterResource(appLogoResource()),
                                contentDescription = stringResource(R.string.app_logo_content_description),
                                modifier = Modifier.size(28.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(title)
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            viewModel.feed.acknowledgeJoinError()
                            scope.launch { drawerState.open() }
                        }) {
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
                    FeedFabs(
                        selectedGroup = loaded.selectedGroup,
                        onGroupEventsClick = { group -> eventsGroup = group },
                        onNewTopicClick = {
                            viewModel.newTopic.reset()
                            viewModel.newTopicImage.reset()
                            // Also dismiss the shared project picker: it survives config
                            // changes in the ViewModel, so a picker left open in a prior
                            // composer would otherwise reappear over this fresh one.
                            viewModel.projectPicker.dismiss()
                            composingTopic = true
                        },
                    )
                }
            },
        ) { padding ->
            when (val s = state) {
                FeedState.Loading -> Box(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }

                // Recovery must go through load(), not refresh(): refresh() no-ops
                // unless the state is Loaded, so from Error it can never leave the
                // error screen (issue: feed stuck on "couldn't load" until the app
                // was force-restarted).
                is FeedState.Error -> PullToRefreshBox(
                    refreshing = false,
                    onRefresh = { viewModel.feed.load() },
                    modifier = Modifier.padding(padding),
                ) {
                    FeedErrorState(
                        rawMessage = s.message,
                        onRetry = { viewModel.feed.load() },
                    )
                }

                is FeedState.Loaded -> PullToRefreshBox(
                    refreshing = false,
                    onRefresh = { viewModel.feed.refresh() },
                    modifier = Modifier.padding(padding),
                ) {
                    FeedList(
                        items = s.items,
                        hasMore = s.hasMore,
                        loadingMore = s.loadingMore,
                        onLoadMore = { viewModel.feed.loadMore() },
                        onTopicClick = { topic ->
                            // Reset on open as well as on back, so this topic's reply
                            // composer starts from a clean attachment flow no matter how
                            // the previous one was left. Dismiss the shared project picker
                            // too — it outlives a config change in the ViewModel and would
                            // otherwise reappear over this topic's composer.
                            viewModel.replyImage.reset()
                            viewModel.projectPicker.dismiss()
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
                        hasMore = s.stale.hasMore,
                        loadingMore = s.stale.loadingMore,
                        onLoadMore = { viewModel.feed.loadMore() },
                        onTopicClick = { topic ->
                            // Reset on open as well as on back, so this topic's reply
                            // composer starts from a clean attachment flow no matter how
                            // the previous one was left. Dismiss the shared project picker
                            // too — it outlives a config change in the ViewModel and would
                            // otherwise reappear over this topic's composer.
                            viewModel.replyImage.reset()
                            viewModel.projectPicker.dismiss()
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
            onForceFeedError = { viewModel.debugForceFeedError() },
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
    attachment: ImageAttachmentState = ImageAttachmentState.Idle,
    onImagePicked: (Uri) -> Unit = {},
    onAttachmentInserted: () -> Unit = {},
    onPickFromProjects: (() -> Unit)? = null,
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
        attachment = attachment,
        onImagePicked = onImagePicked,
        onAttachmentInserted = onAttachmentInserted,
        onPickFromProjects = onPickFromProjects,
    )
}

/**
 * Renders the pick-a-project-photo dialog for whichever composer is on screen and
 * routes the picked photo's markdown into that composer's attachment flow ([target]).
 * The dialog state lives in [FeedAndroidViewModel.projectPicker]; only one composer
 * is ever visible at a time, so a single picker instance is safe to share.
 */
@Composable
private fun ProjectPhotoPickerHost(viewModel: FeedAndroidViewModel, target: ImageAttachmentViewModel) {
    val pickerState by viewModel.projectPicker.state.collectAsState()
    ProjectPhotoPickerDialog(
        state = pickerState,
        onProjectSelected = { viewModel.projectPicker.selectProject(it) },
        onPhotoPicked = { project, photo ->
            viewModel.projectPicker.markdownFor(project, photo)?.let { target.insertExisting(it) }
            viewModel.projectPicker.dismiss()
        },
        onBackToProjects = { viewModel.projectPicker.backToProjects() },
        onDismiss = { viewModel.projectPicker.dismiss() },
    )
}

/**
 * Pure decision function backing [TopicDetailRoute]'s latch: once a reply has been sent
 * during a visit, it stays "true" regardless of later [replyState] transitions (e.g. the
 * Sent -> Idle flip that follows acknowledgement) until the composable is torn down.
 */
internal fun trackReplySent(repliedThisVisit: Boolean, replyState: ReplyState): Boolean =
    repliedThisVisit || replyState is ReplyState.Sent

/**
 * Reorders [list] by moving the element at [from] to [to] (both must be valid indices).
 */
internal fun <T> moveItem(list: List<T>, from: Int, to: Int): List<T> =
    if (from == to || from !in list.indices || to !in list.indices) list
    else list.toMutableList().apply { add(to, removeAt(from)) }

// Lazy items before the first group row in the drawer (the "Your Groups" header).
private const val DRAWER_ITEMS_BEFORE_GROUPS = 1

/** The classic six-dot drag affordance shown on rows while reordering is active. */
@Composable
private fun DragHandle(contentDescription: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(
        modifier = modifier
            .size(24.dp)
            .semantics { this.contentDescription = contentDescription },
    ) {
        val radius = 1.5.dp.toPx()
        val xs = listOf(size.width * 0.38f, size.width * 0.62f)
        val ys = listOf(size.height * 0.3f, size.height * 0.5f, size.height * 0.7f)
        xs.forEach { x -> ys.forEach { y -> drawCircle(color, radius, Offset(x, y)) } }
    }
}

/**
 * Full-screen feed error with a working way out: a Retry button (and, via the
 * surrounding [PullToRefreshBox], pull-to-refresh) that re-runs the initial load.
 * The scroll modifier exists for the pull gesture — pull-to-refresh only engages on
 * a nested-scrolling child, and this content never fills a screen on its own.
 */
@Composable
internal fun FeedErrorState(
    rawMessage: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val message = if (rawMessage.contains("403") || rawMessage.contains("401")) {
        "Session expired. Please log out and sign in again."
    } else {
        "Couldn't load the feed. Check your connection and try again."
    }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupDrawer(
    groups: List<Group>,
    selectedGroup: Group?,
    eventCounts: Map<Long, Int>,
    user: RavelryUser?,
    isFeedbackGroupMember: Boolean = false,
    joinState: JoinState = JoinState.Idle,
    onSendFeedback: () -> Unit = {},
    onJoinFeedbackGroup: () -> Unit = {},
    onGroupSelected: (Group) -> Unit,
    onGroupEventsClick: (Group) -> Unit,
    onSettingsClick: () -> Unit,
    onReorder: (List<Group>) -> Unit = {},
) {
    // Reordering is an explicit mode (issue #97): outside it the list is locked; inside
    // it each row grows a drag handle, row taps are disabled, and rows can be dragged —
    // immediately from the handle, or via long-press anywhere on the row.
    var reorderMode by remember { mutableStateOf(false) }

    // Rows move live in a local working copy so the list follows the finger; the new
    // order is committed upstream (and persisted) once at drop. One state object for
    // the drawer's lifetime — NOT remember(groups) — because the per-row pointerInput
    // coroutines capture it once and never restart; a re-keyed remember would leave
    // them mutating an orphaned copy the list no longer renders (each drag could then
    // commit only its first swap). The source list and callback are re-read through
    // rememberUpdatedState for the same reason.
    var localGroups by remember { mutableStateOf(groups) }
    val latestGroups by rememberUpdatedState(groups)
    val latestOnReorder by rememberUpdatedState(onReorder)
    LaunchedEffect(groups) { localGroups = groups }
    val listState = rememberLazyListState()
    var draggingId by remember { mutableStateOf<Long?>(null) }
    var dragOffset by remember { mutableStateOf(0f) }
    val haptics = LocalHapticFeedback.current

    fun startDrag(id: Long) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        draggingId = id
        dragOffset = 0f
    }

    fun dragBy(id: Long, deltaY: Float) {
        dragOffset += deltaY
        val visible = listState.layoutInfo.visibleItemsInfo
        val current = visible.firstOrNull { it.key == id } ?: return
        val from = localGroups.indexOfFirst { it.id == id }
        // Drag events can outpace layout: right after a swap the offsets in layoutInfo
        // still describe the pre-swap order, and acting on them re-applies the same
        // swap, making the row ping-pong. Only swap when layout reflects the working
        // list.
        if (current.index != from + DRAWER_ITEMS_BEFORE_GROUPS) return
        // The neighbor whose midpoint the dragged row covers becomes the drop slot.
        val top = current.offset + dragOffset
        val bottom = top + current.size
        val target = visible.firstOrNull { info ->
            info.key != id &&
                localGroups.any { it.id == info.key } &&
                (info.offset + info.size / 2f) in top..bottom &&
                info.index == localGroups.indexOfFirst { it.id == info.key } +
                DRAWER_ITEMS_BEFORE_GROUPS
        } ?: return
        val to = target.index - DRAWER_ITEMS_BEFORE_GROUPS
        if (from != to) {
            localGroups = moveItem(localGroups, from, to)
            // The swap moves the dragged row's base slot to the target's; compensate so
            // it stays under the finger.
            dragOffset += current.offset - target.offset
        }
    }

    fun endDrag() {
        draggingId = null
        dragOffset = 0f
        if (localGroups != latestGroups) latestOnReorder(localGroups)
    }

    fun cancelDrag() {
        draggingId = null
        dragOffset = 0f
        localGroups = latestGroups
    }

    ModalDrawerSheet {
        Column {
            LazyColumn(state = listState, modifier = Modifier.weight(1f)) {
                item(key = "drawer-header") {
                    Spacer(Modifier.height(16.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(start = 28.dp, end = 16.dp),
                    ) {
                        Text(
                            text = "Your Groups",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { reorderMode = !reorderMode }) {
                            Text(if (reorderMode) "Done" else "Reorder")
                        }
                    }
                }
                items(localGroups, key = { it.id }) { group ->
                    val eventCount = eventCounts[group.id] ?: 0
                    val dragging = draggingId == group.id
                    NavigationDrawerItem(
                        label = { Text(group.name) },
                        selected = selectedGroup?.id == group.id,
                        // While reordering, taps must not navigate away mid-arrangement.
                        onClick = { if (!reorderMode) onGroupSelected(group) },
                        // The badge image yields to the drag handle in reorder mode —
                        // both compete for the leading slot, and mid-reorder the handle
                        // is the one doing work.
                        icon = if (!reorderMode) {
                            { GroupBadge(group = group, size = 28.dp) }
                        } else {
                            {
                                DragHandle(
                                    contentDescription = "Reorder ${group.name}",
                                    modifier = Modifier.pointerInput(group.id) {
                                        detectDragGestures(
                                            onDragStart = { startDrag(group.id) },
                                            onDrag = { change, amount ->
                                                change.consume()
                                                dragBy(group.id, amount.y)
                                            },
                                            onDragEnd = ::endDrag,
                                            onDragCancel = ::cancelDrag,
                                        )
                                    },
                                )
                            }
                        },
                        badge = if (eventCount > 0) {
                            {
                                GroupEventsBadge(
                                    count = eventCount,
                                    // Same reasoning as the row's own onClick above: a
                                    // rearrange can't be allowed to navigate away.
                                    onClick = { if (!reorderMode) onGroupEventsClick(group) },
                                )
                            }
                        } else null,
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            // Displaced rows slide to their new slot so the reorder is
                            // visible; the dragged row itself must not animate — it is
                            // positioned by the finger (translationY below), and a
                            // placement animation would fight the swap compensation.
                            .then(if (dragging) Modifier else Modifier.animateItemPlacement())
                            .zIndex(if (dragging) 1f else 0f)
                            .graphicsLayer {
                                translationY = if (dragging) dragOffset else 0f
                                shadowElevation = if (dragging) 8.dp.toPx() else 0f
                                // Match the pill shape of the drawer item's highlight so
                                // the drag shadow isn't a bare rectangle behind it.
                                shape = CircleShape
                            }
                            .then(
                                if (!reorderMode) Modifier
                                else Modifier.pointerInput(group.id) {
                                    detectDragGesturesAfterLongPress(
                                        onDragStart = { startDrag(group.id) },
                                        onDrag = { change, amount ->
                                            change.consume()
                                            dragBy(group.id, amount.y)
                                        },
                                        onDragEnd = ::endDrag,
                                        onDragCancel = ::cancelDrag,
                                    )
                                },
                            ),
                    )
                }
                item(key = "drawer-footer-spacer") { Spacer(Modifier.height(16.dp)) }
            }
            HorizontalDivider()
            FeedbackDrawerAction(
                isMember = isFeedbackGroupMember,
                joinState = joinState,
                onSendFeedback = onSendFeedback,
                onJoin = onJoinFeedbackGroup,
            )
            HorizontalDivider()
            ProfileFooter(user = user, onClick = onSettingsClick)
        }
    }
}

/**
 * Drawer row for app feedback (issue #57): "Send feedback" once the user is a member of the
 * support group, or "Join feedback group" beforehand — joining is a prerequisite for posting.
 * While a join is in flight the row shows a spinner; a failed join surfaces an inline message.
 */
@Composable
private fun FeedbackDrawerAction(
    isMember: Boolean,
    joinState: JoinState,
    onSendFeedback: () -> Unit,
    onJoin: () -> Unit,
) {
    val joining = joinState is JoinState.Joining
    val label = when {
        isMember -> "Send feedback"
        joining -> "Joining…"
        else -> "Join feedback group"
    }
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(
                    enabled = !joining,
                    onClick = if (isMember) onSendFeedback else onJoin,
                    onClickLabel = label,
                    role = Role.Button,
                )
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (joining) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                Icon(
                    if (isMember) Icons.AutoMirrored.Filled.Send else Icons.Default.Add,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = label, style = MaterialTheme.typography.bodyLarge)
                if (!isMember && !joining) {
                    Text(
                        text = "Join to report bugs and request features",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (joinState is JoinState.Error) {
            Text(
                text = joinState.message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(start = 56.dp, end = 16.dp, bottom = 8.dp),
            )
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
 * The feed's floating action buttons: a small calendar button for the selected group's
 * events (issue #179) stacked above the new-topic button. The calendar button needs a
 * group to open events for, so it renders only when [selectedGroup] is non-null; at the
 * FeedScreen call site that is the currently-viewed group, which (since the all-groups
 * view was removed, #97) is present whenever the user belongs to any group.
 */
@Composable
internal fun FeedFabs(
    selectedGroup: Group?,
    onGroupEventsClick: (Group) -> Unit,
    onNewTopicClick: () -> Unit,
) {
    Column(horizontalAlignment = Alignment.End) {
        selectedGroup?.let { group ->
            SmallFloatingActionButton(onClick = { onGroupEventsClick(group) }) {
                Icon(Icons.Default.DateRange, contentDescription = "Group events")
            }
            Spacer(Modifier.height(16.dp))
        }
        FloatingActionButton(onClick = onNewTopicClick) {
            Icon(Icons.Default.Edit, contentDescription = "New topic")
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

/** Visible-item slack before the end of the list that triggers [onLoadMore]. */
private const val LOAD_MORE_THRESHOLD = 5

@Composable
private fun FeedList(
    items: List<FeedItem>,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    onTopicClick: (FeedItem) -> Unit,
) {
    val listState = rememberLazyListState()

    // Fires onLoadMore once the user has scrolled within LOAD_MORE_THRESHOLD items of the
    // bottom, so the next page arrives before they hit the end (issue #106).
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
        if (items.isNotEmpty()) {
            item(key = "feed-footer") {
                FeedListFooter(loadingMore = loadingMore, hasMore = hasMore)
            }
        }
    }
}

/**
 * Trailing row below the last topic card: a spinner while [loadingMore] fetches the next
 * page, or an "end of feed" message once [hasMore] goes `false` (issue #106).
 */
@Composable
private fun FeedListFooter(loadingMore: Boolean, hasMore: Boolean) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        contentAlignment = Alignment.Center,
    ) {
        when {
            loadingMore -> CircularProgressIndicator(modifier = Modifier.size(24.dp))
            !hasMore -> Text(
                text = "You're all caught up",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
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
