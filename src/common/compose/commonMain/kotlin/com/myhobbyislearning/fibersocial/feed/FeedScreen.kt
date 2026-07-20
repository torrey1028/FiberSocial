@file:OptIn(ExperimentalComposeUiApi::class)

package com.myhobbyislearning.fibersocial.feed

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.myhobbyislearning.fibersocial.about.AboutScreen
import com.myhobbyislearning.fibersocial.app.ForegroundActivations
import com.myhobbyislearning.fibersocial.debug.DebugPanel
import com.myhobbyislearning.fibersocial.events.EventDetailScreen
import com.myhobbyislearning.fibersocial.events.EventsScreen
import com.myhobbyislearning.fibersocial.events.EventsState
import com.myhobbyislearning.fibersocial.events.NewEventScreen
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.projects.ProjectPageScreen
import com.myhobbyislearning.fibersocial.projects.ProjectPageState
import com.myhobbyislearning.fibersocial.projects.ProjectCommentsState
import com.myhobbyislearning.fibersocial.projects.CommentPostState
import com.myhobbyislearning.fibersocial.projects.ProjectPhotoPickerDialog
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.feed.models.VoteType
import com.myhobbyislearning.fibersocial.composeapp.resources.Res
import com.myhobbyislearning.fibersocial.composeapp.resources.app_logo_content_description
import com.myhobbyislearning.fibersocial.messages.MessageThreadScreen
import com.myhobbyislearning.fibersocial.messages.MessagesScreen
import com.myhobbyislearning.fibersocial.messages.NewMessageScreen
import com.myhobbyislearning.fibersocial.messages.ReplyContext
import com.myhobbyislearning.fibersocial.messages.replySubject
import com.myhobbyislearning.fibersocial.profile.UserProfileScreen
import com.myhobbyislearning.fibersocial.profile.UserProfileState
import com.myhobbyislearning.fibersocial.notifications.DeepLink
import com.myhobbyislearning.fibersocial.notifications.MutedTopicsStore
import com.myhobbyislearning.fibersocial.notifications.NotificationSettings
import com.myhobbyislearning.fibersocial.notifications.NotificationSettingsStore
import com.myhobbyislearning.fibersocial.notifications.PollCadence
import com.myhobbyislearning.fibersocial.feedback.FeedbackScreen
import com.myhobbyislearning.fibersocial.feedback.SupportGroup
import com.myhobbyislearning.fibersocial.settings.SettingsScreen
import com.myhobbyislearning.fibersocial.settings.ThemeMode
import com.myhobbyislearning.fibersocial.ui.AppBranding
import com.myhobbyislearning.fibersocial.ui.GroupBadge
import com.myhobbyislearning.fibersocial.ui.PullToRefreshBox
import com.myhobbyislearning.fibersocial.ui.appLogoResource
import com.myhobbyislearning.fibersocial.ui.UserAvatar
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource

/**
 * Full-page loading screen shown while the initial feed (groups) loads (issue #233).
 * Mirrors the login screen — logo, name, tagline — with a spinner in place of the button,
 * so the launch experience is a deliberate loading page rather than half-built chrome.
 */
@Composable
internal fun LaunchLoadingScreen() {
    // The theme sets colors but no background (FiberSocialTheme has no Surface), and this
    // screen renders before the feed's Scaffold — so it needs its own themed surface, or
    // it falls through to the raw window background (wrong in both light and dark). #233
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            AppBranding()
            Spacer(modifier = Modifier.height(48.dp))
            CircularProgressIndicator()
        }
    }
}

/**
 * Softly fades [fadeWidth] of a composable's leading and/or trailing edge to transparent
 * (issue #207): a horizontally-scrolling group title that overruns the bar fades into
 * whichever edge has more content off-screen, hinting you can scroll that way instead of
 * hard-clipping. Each side is gated by [fadeStart]/[fadeEnd] (typically the scroll state's
 * `canScrollBackward`/`canScrollForward`), so a fully-visible title shows no fade.
 */
private fun Modifier.horizontalEdgeFade(
    fadeWidth: Dp,
    fadeStart: Boolean,
    fadeEnd: Boolean,
): Modifier = this
    .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
    .drawWithContent {
        drawContent()
        val fadePx = fadeWidth.toPx()
        if (size.width <= fadePx) return@drawWithContent
        if (fadeStart) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.Black),
                    startX = 0f,
                    endX = fadePx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
        if (fadeEnd) {
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Black, Color.Transparent),
                    startX = size.width - fadePx,
                    endX = size.width,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * Material's "filter_list" glyph, defined inline because the app only ships
 * material-icons-core, which doesn't include it (issue #210's unread-topics toggle).
 */
private val FilterListIcon: ImageVector by lazy {
    materialIcon(name = "Filled.FilterList") {
        materialPath {
            moveTo(10.0f, 18.0f)
            horizontalLineToRelative(4.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-4.0f)
            close()
            moveTo(3.0f, 6.0f)
            verticalLineToRelative(2.0f)
            horizontalLineToRelative(18.0f)
            verticalLineToRelative(-2.0f)
            close()
            moveTo(6.0f, 13.0f)
            horizontalLineToRelative(12.0f)
            verticalLineToRelative(-2.0f)
            horizontalLineToRelative(-12.0f)
            close()
        }
    }
}

/**
 * The feed's top bar (issue #207): the selected group's badge and name on the left (the
 * name scrolls sideways with an edge fade when it's too long to fit), and the FiberSocial
 * logo pinned to the far right. The navigation icon opens the group drawer.
 *
 * A topic filter menu (issue #210) sits between the title and the logo — a client-side
 * filter over the already-loaded feed (including sticky/pinned topics — sticky is just a
 * sort-order flag on [com.myhobbyislearning.fibersocial.feed.models.FeedItem], not a separate list,
 * so the same predicate covers both), so flipping it needs no network call.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun FeedTopBar(
    title: String,
    selectedGroup: Group?,
    onOpenDrawer: () -> Unit,
    showUnreadOnly: Boolean = false,
    onToggleUnreadOnly: () -> Unit = {},
    // Hidden on destinations that aren't the topic feed (currently Messages, #369), where the
    // filter would have nothing to act on. Defaults to true so the feed keeps its behaviour.
    showFilter: Boolean = true,
) {
    TopAppBar(
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // The group's badge takes the slot the logo used to sit in (#207).
                selectedGroup?.let { group ->
                    GroupBadge(group = group, size = 28.dp)
                    Spacer(modifier = Modifier.width(8.dp))
                }
                // A long group name scrolls sideways instead of wrapping to a second line,
                // and fades on whichever side has more off-screen: trailing only at the
                // start, both in the middle, leading only once fully scrolled (#207).
                val titleScroll = rememberScrollState()
                Text(
                    text = title,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f)
                        .horizontalEdgeFade(
                            fadeWidth = 24.dp,
                            fadeStart = titleScroll.canScrollBackward,
                            fadeEnd = titleScroll.canScrollForward,
                        )
                        .horizontalScroll(titleScroll),
                )
            }
        },
        navigationIcon = {
            IconButton(onClick = onOpenDrawer) {
                Icon(Icons.Default.Menu, contentDescription = "Select group")
            }
        },
        actions = {
            // Topic filter menu (#210): a dropdown rather than a bare toggle, since a
            // plain icon tint alone didn't clearly communicate what tapping it would do.
            // The icon itself still tints to the theme's primary color while a filter is
            // active, so the state is visible without opening the menu.
            var filterMenuExpanded by remember { mutableStateOf(false) }
            if (showFilter) Box {
                IconButton(onClick = { filterMenuExpanded = true }) {
                    Icon(
                        imageVector = FilterListIcon,
                        tint = if (showUnreadOnly) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            LocalContentColor.current
                        },
                        contentDescription = if (showUnreadOnly) {
                            "Filter: showing unread topics only. Tap to change."
                        } else {
                            "Filter: showing all topics. Tap to change."
                        },
                    )
                }
                DropdownMenu(expanded = filterMenuExpanded, onDismissRequest = { filterMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text("All topics") },
                        leadingIcon = if (!showUnreadOnly) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            filterMenuExpanded = false
                            if (showUnreadOnly) onToggleUnreadOnly()
                        },
                    )
                    DropdownMenuItem(
                        text = { Text("Unread only") },
                        leadingIcon = if (showUnreadOnly) {
                            { Icon(Icons.Default.Check, contentDescription = null) }
                        } else null,
                        onClick = {
                            filterMenuExpanded = false
                            if (!showUnreadOnly) onToggleUnreadOnly()
                        },
                    )
                }
            }
            // FiberSocial logo pinned to the far right of the bar (#207).
            Image(
                painter = painterResource(appLogoResource()),
                contentDescription = stringResource(Res.string.app_logo_content_description),
                modifier = Modifier.padding(end = 12.dp).size(28.dp),
            )
        },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedScreen(
    viewModel: FeedScreenModel,
    onLogout: () -> Unit,
    // Where a tapped notification wants to land (issue #351). Consume-once: the host
    // nulls its flow when onDeepLinkConsumed fires, so a recomposition can't replay it.
    deepLink: DeepLink? = null,
    onDeepLinkConsumed: () -> Unit = {},
    themeMode: ThemeMode? = null,
    onThemeModeSelected: (ThemeMode) -> Unit = {},
    // Platform seams, injected by the host (MainActivity today, an iOS host in #117):
    // the settings store lives wherever the platform keeps preferences, and cadence
    // changes / debug sync-now must reach the platform's background scheduler.
    notificationSettingsStore: NotificationSettingsStore? = null,
    // Where per-topic reply mutes are persisted (issue #338); read for the topic overflow
    // menu's mute state and written when the user toggles it.
    mutedTopicsStore: MutedTopicsStore? = null,
    onPollCadenceChanged: (PollCadence) -> Unit = {},
    debugPanelEnabled: Boolean = false,
    onRunEventSync: () -> Unit = {},
    deviceInfo: String = "",
) {
    val state by viewModel.feed.state.collectAsState()
    val topicDetailState by viewModel.topicDetail.state.collectAsState()
    val eventsState by viewModel.events.state.collectAsState()
    val eventDetailState by viewModel.eventDetail.state.collectAsState()
    val messagesState by viewModel.messages.state.collectAsState()
    // THE conversation-detail nav flag (issue #371). Non-null means a thread is open, and
    // it is the ViewModel's own state rather than a screen-level `mutableStateOf` like the
    // other destinations here: the thread's content, its mark-read pass and the list row's
    // dot are one piece of state, so a second flag beside it could only ever disagree with
    // it. The cost is that a process death closes the thread and lands the user back on the
    // list — the mailbox they can see the conversation in, with its read state applied.
    val openMessageThread by viewModel.messages.openThread.collectAsState()
    val joinState by viewModel.feed.joinState.collectAsState()
    val leavingGroupId by viewModel.feed.leavingGroupId.collectAsState()
    val leaveError by viewModel.feed.leaveError.collectAsState()
    val drawerUnread by viewModel.feed.drawerUnread.collectAsState()
    // Fetch the drawer's unread dots once the authenticated feed is showing (issue: unread
    // indicators), and again on every real foreground resume (issue #350 part 1). Kept a
    // UI-driven side channel — not folded into load()/refresh() — so it never blocks or
    // interferes with the feed fetch; drawer pull-to-refresh re-fires it too.
    //
    // Keyed on the activation counter rather than Unit: ForegroundActivations is a
    // StateFlow, so this effect runs immediately on first composition with whatever value
    // is current (cold start / login) AND restarts on each later resume. A separate
    // LaunchedEffect(Unit) for the initial fetch would be redundant — and would double
    // the cold-start network pass — so it is deliberately gone.
    //
    // Also keyed on "the feed has loaded at least once": the per-group dots are derived
    // from the user's groups (issue #350 part 3), which don't exist yet on a cold start's
    // first composition, so firing then would waste a pass and leave the group dots blank
    // until the next resume. The flag is monotonic — set once and never cleared — rather
    // than `state is Loaded`, which flaps false on every pull-to-refresh and would
    // re-trigger this 1 + groups.size-request pass each time.
    val foregroundTick by ForegroundActivations.ticks.collectAsState()
    val feedEverLoaded = remember { mutableStateOf(false) }
    if (state is FeedState.Loaded) feedEverLoaded.value = true
    LaunchedEffect(foregroundTick, feedEverLoaded.value) {
        if (feedEverLoaded.value) viewModel.feed.refreshDrawerUnread()
    }
    // Hoisted above the topic-detail early-return below so the feed's scroll position
    // survives opening a topic and coming back (issue #204): FeedList is removed from
    // composition while a topic is open, so a list state owned by it would reset to top.
    // Keyed by the selected group so it resets to the top on a group SWITCH (the new
    // group is different content) while still surviving a topic open/return within the
    // same group — without the key, switching groups would leave the list at the old
    // group's stale scroll offset (and could fire a spurious load-more). `key { … }`
    // keeps rememberLazyListState's own rememberSaveable, so config-change restore still
    // works; a plain remember(groupId) would drop it.
    val selectedGroupId = when (val s = state) {
        is FeedState.Loaded -> s.selectedGroup?.id
        is FeedState.Refreshing -> s.stale.selectedGroup?.id
        else -> null
    }
    // Whether the cross-group "My Posts" feed is showing (drawer item above the groups).
    // Derived alongside selectedGroupId because the two are the feed's selection identity:
    // My Posts has a null selectedGroupId, so the list-state key below needs this flag to
    // tell it apart from the no-groups case and reset scroll on a group ↔ My Posts switch.
    val showingMyPosts = when (val s = state) {
        is FeedState.Loaded -> s.myPosts
        is FeedState.Refreshing -> s.stale.myPosts
        else -> false
    }
    val feedListState = key(if (showingMyPosts) "my-posts" else selectedGroupId) { rememberLazyListState() }
    // Messages' own scroll position, hoisted for the same reason as feedListState: the
    // Messages body is removed from composition whenever the user switches to Posts or a
    // group, so a list state owned down inside MessagesScreen would reset scroll (and
    // re-trigger paging from the top) on every visit.
    val messagesListState = rememberLazyListState()
    // "Unread only" filter (issue #210): a purely client-side toggle over the already-
    // loaded feed. Unlike feedListState above, this is intentionally NOT scoped per
    // group — it's a standing preference the user sets once and expects to carry across
    // group switches, not a per-group default that resets to "show all" each time.
    var showUnreadOnly by remember { mutableStateOf(false) }
    // Collapsed state of the pinned-topics section. Hoisted here (not owned by FeedList)
    // for the same reason as feedListState: FeedList is removed from composition while a
    // topic is open, so state owned by it would reset on return. Like showUnreadOnly it
    // deliberately carries across group switches — folding the pinned section away is a
    // standing "I don't need these" preference, not a per-group choice.
    var pinnedCollapsed by remember { mutableStateOf(false) }
    // THE Messages destination flag (issue #369, epic #365). This single boolean is how
    // Messages is selected — programmatically, set it to `true` (and it wins over whatever
    // the feed's own selection is, without disturbing it). Later work in this epic — the
    // drawer unread dot (#372) and the message-notification deep link (#375) — should drive
    // this flag rather than inventing a second mechanism.
    //
    // Why a screen-level flag and NOT a third field on FeedState.Loaded: Messages doesn't
    // share the feed's item pipeline at all, so threading it through FeedState would mean
    // touching every copy(...) in selectGroup/selectMyPosts/refresh/fetchFeed to carry a
    // value none of them can act on — and it would put a third mode into the feedListState
    // key (:433), which currently distinguishes "My Posts" from a group id and would collide
    // with the no-groups null case exactly as the comment above that key warns.
    //
    // Why it renders INSIDE the Scaffold rather than as a top-level early return like
    // eventsGroup/showSettings: those are pushed sub-screens with their own back arrow.
    // Messages is a peer drawer destination, so it has to keep the drawer and the top bar —
    // otherwise the user would land somewhere with no way back to Posts or Groups. Selecting
    // Posts or a group clears it (see the drawer call site below), which is the "back" here.
    var showingMessages by rememberSaveable { mutableStateOf(false) }
    var selectedTopic by remember { mutableStateOf<FeedItem?>(null) }
    var selectedEventPermalink by remember { mutableStateOf<String?>(null) }
    var eventsGroup by remember { mutableStateOf<Group?>(null) }
    var composingEventGroup by remember { mutableStateOf<Group?>(null) }
    var showSettings by rememberSaveable { mutableStateOf(false) }
    // Declared here (not at the feed chrome below) so the Settings block above can open it (#207).
    var showDebugPanel by remember { mutableStateOf(false) }
    // Declared here (not inside the Settings block above) so it outlives that subtree (#343).
    // The block is an early return, so tapping Back drops it out of composition immediately —
    // a scope remembered in there would be cancelled with it, killing an in-flight settings
    // save mid-write and silently reverting the toggle the user just flipped.
    val settingsScope = rememberCoroutineScope()
    // Hoisted here for the same lifetime reason as settingsScope, one step further (#360).
    // The whole NotificationSettings object is held (not just the cadence) so a per-kind
    // toggle change (issue #335) saves a mutated copy and never clobbers the cadence or the
    // other toggles — they all live in one serialized object.
    //
    // Remembered at screen lifetime rather than inside the Settings block because a
    // block-scoped `remember` + one-shot load re-read disk on *every* entry: toggle, Back,
    // reopen faster than the (now NonCancellable) save lands, and the fresh load returned
    // the pre-write value and rendered the toggle back where it started, with nothing left
    // to re-trigger a reload. The app is the only writer of this store — the event sync only
    // load()s it — so once loaded the in-memory value is authoritative and re-reading buys
    // nothing but that race.
    var notificationSettings by remember { mutableStateOf<NotificationSettings?>(null) }
    // Lazy, not eager: load on first Settings open and keep it, so opening Settings still
    // costs the one disk read it always did and app start doesn't gain one for the users who
    // never open it. The null guard is what makes it once-only — after that this re-runs on
    // each open and immediately no-ops, so it can never clobber an in-flight optimistic edit.
    // Closing Settings mid-load cancels the read and leaves it null; the next open retries.
    LaunchedEffect(showSettings) {
        if (showSettings && notificationSettings == null) {
            notificationSettingsStore?.let { notificationSettings = it.load() }
        }
    }
    // Same reasoning as settingsScope above, for the topic-detail mute toggle's early-return
    // block: hoisted here so tapping Back right after toggling mute doesn't cancel the write.
    val muteScope = rememberCoroutineScope()
    // Opened from the Settings block below without clearing showSettings, so backing out
    // of About returns to the still-open Settings screen (issue #289).
    var showAbout by remember { mutableStateOf(false) }
    var composingTopic by rememberSaveable { mutableStateOf(false) }
    // The message composer (issue #374), following composingTopic's shape exactly: a
    // rememberSaveable flag plus an early return, not a new navigation mechanism.
    //
    // TWO flags rather than one, because the composer serves two entries with different
    // ways back: composingMessage is a new conversation reached from the Messages list's
    // FAB, and replyingToRootId is a reply reached from an open thread. Collapsing them into
    // one nullable would make "new message" and "reply to thread 0" the same value, and the
    // reply's Back has to land on the still-open thread while the new message's lands on the
    // list — which is exactly the distinction the early return below reads.
    var composingMessage by rememberSaveable { mutableStateOf(false) }
    var replyingToRootId by rememberSaveable { mutableStateOf<Long?>(null) }
    var sendingFeedback by rememberSaveable { mutableStateOf(false) }

    // Deep-link ids parked until the feed data needed to act on them arrives; see the
    // two resolver effects below for why each resolution has to be deferred.
    var pendingDeepLinkTopicId by remember { mutableStateOf<Long?>(null) }
    var pendingDeepLinkEventGroup by remember { mutableStateOf<Pair<String, Long>?>(null) }

    /**
     * Clears every navigation flag ahead of acting on a deep link.
     *
     * A tap must win over whatever screen is open — the early returns below (topic,
     * settings, events list, composers) would otherwise swallow it and the notification
     * would visibly do nothing. Factored out of the deep-link branches so a nav flag added
     * later can't be cleared in one of them and forgotten in the others — `showingMessages`
     * is in here for exactly that reason, and the Message/Messages branches then set it
     * back to true after this has cleared everything else (issue #375).
     */
    fun clearNavigationForDeepLink() {
        selectedTopic = null
        selectedEventPermalink = null
        showSettings = false
        showAbout = false
        showDebugPanel = false
        eventsGroup = null
        showingMessages = false
        composingTopic = false
        composingMessage = false
        replyingToRootId = null
        composingEventGroup = null
        sendingFeedback = false
        // The parks count as navigation state too: without this, a Topic link parked
        // while My Posts was still loading would resolve *after* a second tap sent the
        // user to an event, and the topic early return (which sits above the event one)
        // would cover the event they actually asked for.
        pendingDeepLinkTopicId = null
        pendingDeepLinkEventGroup = null
        // This path closes both composers without going through their onBack
        // handlers, so their attachment flows must be reset here too.
        viewModel.replyImage.reset()
        viewModel.newTopicImage.reset()
        viewModel.newEvent.reset()
        viewModel.projectPicker.dismiss()
        viewModel.projectPage.dismiss()
        viewModel.userProfile.dismiss()
    }

    /**
     * Brings the cross-group My Posts feed up, the shared first step of the Topic and
     * MyPosts deep links.
     *
     * `selectMyPosts()` no-ops when My Posts is already the active view (it's a "switch
     * to" call, not a "load" call) — which would otherwise strand a second notification
     * tap on stale content, since that is exactly the state a reply notification arrives
     * in. Refresh instead, so the tap that reported new replies actually pulls them in.
     */
    fun showMyPostsFeed(alreadyShowing: Boolean) {
        if (alreadyShowing) viewModel.feed.refresh() else viewModel.feed.selectMyPosts()
        // A reply notification means content moved under the drawer's unread dots too,
        // so re-evaluate them rather than leaving the tap to fix only the feed.
        viewModel.feed.refreshDrawerUnread()
    }

    // Deleting the opening post deletes the whole topic on Ravelry (issue #247): close
    // the now-gone thread view immediately instead of leaving it open, and refresh the
    // feed so the stale card disappears without waiting for a manual pull-to-refresh.
    LaunchedEffect(Unit) {
        viewModel.topicDetail.topicDeleted.collect {
            viewModel.replyImage.reset()
            viewModel.projectPicker.dismiss()
            selectedTopic = null
            viewModel.feed.refresh()
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

    val feedIsLoaded = state is FeedState.Loaded

    // Load the inbox when the Messages destination opens (issue #370).
    //
    // The username comes from the feed's ALREADY-RESOLVED current user rather than a fresh
    // getCurrentUser() call — MessagesViewModel needs it to tell inbound from outbound, and
    // the feed has fetched it before Messages can be reached. Keying on it (not just on
    // showingMessages) is what handles the one case where it isn't available yet:
    // showingMessages is rememberSaveable, so a process death while Messages was open
    // restores it as true while the feed is still loading. The effect then simply re-runs
    // once the user arrives instead of loading with a blank username.
    //
    // Re-entering Messages reloads rather than showing a cached list — a mailbox the user
    // just navigated back to is exactly where stale contents are most noticeable.
    LaunchedEffect(showingMessages, user?.username) {
        val username = user?.username ?: return@LaunchedEffect
        if (showingMessages) viewModel.messages.load(username)
    }
    // The My Posts items available right now — Loaded, or a Refreshing snapshot of them.
    // This is the lookup table a Topic deep link resolves its id against.
    val myPostsItems = if (loaded?.myPosts == true) loaded.items else emptyList()

    // A tapped notification lands here and is routed to one of five destinations. The two
    // feed-backed ones must wait for the feed to load — selectMyPosts() no-ops
    // before FeedState.Loaded, and a cold start from a notification arrives mid-load — so
    // the effect keys on the loaded flag too and consumes the link only once it has acted.
    LaunchedEffect(deepLink, feedIsLoaded) {
        when (val link = deepLink) {
            null -> Unit

            // Acts immediately rather than waiting for feedIsLoaded: the event detail
            // fetches its own data, and gating it here would strand the tap entirely if
            // the feed happened to fail to load.
            is DeepLink.Event -> {
                clearNavigationForDeepLink()
                viewModel.eventDetail.load(link.permalink)
                selectedEventPermalink = link.permalink
                // Putting the group's events list underneath is what makes back land
                // there, and a second back reach the feed (issue #351) — the
                // selectedEventPermalink early return sits above the eventsGroup one.
                // Null for reminder notifications, which carry no group at all — back
                // from the event then goes straight to the feed. See DeepLink.Event.
                val groupId = link.groupId
                if (groupId != null) {
                    val group = groups.firstOrNull { it.id == groupId }
                    // Groups not loaded yet (the usual cold-start-from-a-tap case) —
                    // park it for the resolver below.
                    if (group != null) eventsGroup = group
                    else pendingDeepLinkEventGroup = link.permalink to groupId
                }
                onDeepLinkConsumed()
            }

            // Switches to My Posts and parks the topic id; the resolver below opens the
            // thread once that feed has content to look the id up in.
            is DeepLink.Topic -> {
                if (!feedIsLoaded) return@LaunchedEffect // re-runs when it loads
                clearNavigationForDeepLink()
                showMyPostsFeed(alreadyShowing = loaded?.myPosts == true)
                pendingDeepLinkTopicId = link.topicId
                onDeepLinkConsumed()
            }

            DeepLink.MyPosts -> {
                if (!feedIsLoaded) return@LaunchedEffect
                clearNavigationForDeepLink()
                showMyPostsFeed(alreadyShowing = loaded?.myPosts == true)
                onDeepLinkConsumed()
            }

            // Acts immediately rather than waiting for feedIsLoaded (like Event, unlike the
            // two feed-backed destinations): the Messages destination replaces the feed body
            // entirely and reads none of its state, so gating on the feed would strand the
            // tap whenever the feed happened to fail to load.
            is DeepLink.Message -> {
                clearNavigationForDeepLink()
                showingMessages = true
                // PARKED FOR #371. Selecting Messages is as far as this can route today —
                // the conversation detail screen doesn't exist yet, so there is nothing to
                // open the thread *in*. When #371 lands, this is where its id gets parked
                // for a resolver effect, following the pattern pendingDeepLinkTopicId uses
                // above; resolve on link.messageId ("the thread containing this message")
                // rather than link.threadRootId, which is best-effort — see DeepLink.Message.
                println(
                    "FiberSocial: message deep link -> thread ${link.threadRootId} " +
                        "message ${link.messageId}; showing Messages (conversation detail is #371)",
                )
                onDeepLinkConsumed()
            }

            DeepLink.Messages -> {
                clearNavigationForDeepLink()
                showingMessages = true
                onDeepLinkConsumed()
            }
        }
    }

    // Phase 2 of a Topic deep link. `selectedTopic` is a whole FeedItem (author, post
    // count, unread counts, first-unread anchor…) and the notification carries only an
    // id, so the item is looked up in the My Posts page the branch above switched to
    // rather than synthesized — a synthetic item would show bogus unread state and no
    // first-unread anchor to jump to.
    // Keyed on the whole loaded snapshot, not just its items: a My Posts feed that loads
    // empty leaves the item list unchanged, and this effect still has to run so it can
    // release the parked id instead of waiting on a page that will never arrive.
    LaunchedEffect(pendingDeepLinkTopicId, loaded) {
        val topicId = pendingDeepLinkTopicId ?: return@LaunchedEffect
        // The branch that parked this id switched to My Posts synchronously
        // (selectMyPosts flips the chrome on the same frame), so a feed that is no
        // longer on My Posts means the user navigated away while the page was still
        // loading. Drop the link rather than leaving it parked to fire minutes later
        // and yank them into a topic out of nowhere — the same hazard the event
        // resolver below guards against.
        if (loaded?.myPosts != true) {
            if (loaded != null) pendingDeepLinkTopicId = null
            return@LaunchedEffect
        }
        // Wait until My Posts actually has content. A Refreshing snapshot is fine, and
        // in fact preferable: its unread state is the pre-notification one, which is
        // exactly where "jump to first unread" should land the user.
        if (myPostsItems.isEmpty() && !feedIsLoaded) return@LaunchedEffect
        // Releasing the park invalidates this effect's own key, so everything below must
        // stay non-suspending — a suspension point here would be cancelled mid-flight and
        // the navigation would silently not happen.
        pendingDeepLinkTopicId = null
        val topic = myPostsItems.firstOrNull { it.id == topicId }
        if (topic == null) {
            // The topic fell off page 1 of filtered_topics between the sync that
            // notified and the tap. Degrade to the My Posts feed that's already on
            // screen — better than a dead tap or a fabricated item.
            println("FiberSocial: deep-linked topic $topicId is not on the My Posts page; showing the feed")
            return@LaunchedEffect
        }
        // Mirrors onTopicClick below, including its composer resets.
        viewModel.replyImage.reset()
        viewModel.projectPicker.dismiss()
        viewModel.topicDetail.load(topic.id)
        selectedTopic = topic
    }

    // Phase 2 of an Event deep link whose group wasn't loaded when the tap arrived.
    // eventsGroup sits below the event-detail early return, so filling it in late is
    // invisible — it only decides where back lands.
    LaunchedEffect(pendingDeepLinkEventGroup, groups) {
        val (permalink, groupId) = pendingDeepLinkEventGroup ?: return@LaunchedEffect
        if (groups.isEmpty()) return@LaunchedEffect
        // Same non-suspending caveat as the topic resolver above.
        pendingDeepLinkEventGroup = null
        // If the user already backed out of the deep-linked event, leave them where they
        // are — pushing an events list under them now would yank them into a screen they
        // never asked for. A group that isn't found (they left it) also stays null,
        // which just means back goes to the feed.
        if (selectedEventPermalink != permalink) return@LaunchedEffect
        eventsGroup = groups.firstOrNull { it.id == groupId }
    }

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

    // Rendered before every other screen: a project link can be tapped from a topic,
    // an event description, or the feedback preview, and the page must show over
    // whichever of them is open — backing out returns there untouched.
    val projectPageState by viewModel.projectPage.state.collectAsState()
    if (projectPageState !is ProjectPageState.Hidden) {
        val commentsState by viewModel.projectPage.commentsState.collectAsState()
        val postState by viewModel.projectPage.postState.collectAsState()
        val pattern by viewModel.projectPage.pattern.collectAsState()
        ProjectPageScreen(
            state = projectPageState,
            commentsState = commentsState,
            postState = postState,
            pattern = pattern,
            currentUsername = loaded?.user?.username,
            onBack = { viewModel.projectPage.dismiss() },
            onRetry = { viewModel.projectPage.retry() },
            onPostComment = { viewModel.projectPage.postComment(it) },
            onPostErrorShown = { viewModel.projectPage.acknowledgePostError() },
            onDeleteComment = { viewModel.projectPage.deleteComment(it) },
            onPostAcknowledged = { viewModel.projectPage.acknowledgePosted() },
        )
        return
    }

    // Rendered before everything: a username can be tapped from a topic, a feed card,
    // or an event's attendee list, and the profile must show over whichever is open —
    // backing out returns there untouched.
    val userProfileState by viewModel.userProfile.state.collectAsState()
    if (userProfileState !is UserProfileState.Hidden) {
        UserProfileScreen(
            state = userProfileState,
            onBack = { viewModel.userProfile.dismiss() },
            onRetry = { viewModel.userProfile.retry() },
            onOpenProject = { project -> viewModel.projectPage.open(project) },
            onGroupClick = { group ->
                viewModel.userProfile.dismiss()
                viewModel.feed.selectGroup(group)
            },
        )
        return
    }

    // Rendered before settings so "Send feedback" (opened from Settings) shows over it;
    // backing out returns to the still-open settings screen.
    if (sendingFeedback) {
        val feedbackState by viewModel.feedback.state.collectAsState()
        val uriHandler = LocalUriHandler.current
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

    // Rendered before settings so "About FiberSocial" (opened from Settings, issue #289)
    // shows over it; backing out returns to the still-open settings screen.
    if (showAbout) {
        val uriHandler = LocalUriHandler.current
        AboutScreen(
            onBack = { showAbout = false },
            onOpenRepo = { uriHandler.openUri("https://github.com/torrey1028/FiberSocial") },
            onOpenPrivacyPolicy = { uriHandler.openUri("https://torrey1028.github.io/FiberSocial/") },
            // Private mailto, deliberately not the public "Send feedback" flow — see the
            // param KDoc on AboutScreen. Also reported to NCMEC/Ravelry per
            // legal/child-safety-standards.html; this is just the in-app entry point.
            onReportChildSafetyConcern = {
                uriHandler.openUri(
                    "mailto:myhobbyislearning@gmail.com" +
                        "?subject=FiberSocial%20child%20safety%20concern" +
                        "&body=Please%20describe%20what%20you%20observed%2C%20including%20a%20" +
                        "Ravelry%20username%2C%20group%2C%20or%20topic%20link%20if%20possible.",
                )
            },
        )
        return
    }

    if (showSettings) {
        // notificationSettings and its one-shot load are hoisted to FeedScreen's state block
        // (see #360 there) so the optimistic value survives leaving and re-entering Settings.
        // Optimistically update local state, then persist. Null (still loading) is a no-op.
        // settingsScope is hoisted to FeedScreen's state block (see #343 there) so leaving
        // Settings doesn't cancel the save; NonCancellable covers the remaining case that
        // hoisting can't — FeedScreen itself leaving composition (logout, teardown) while a
        // save is in flight — so a started write always runs to completion either way.
        fun updateSettings(transform: (NotificationSettings) -> NotificationSettings) {
            val updated = notificationSettings?.let(transform) ?: return
            notificationSettings = updated
            settingsScope.launch { withContext(NonCancellable) { notificationSettingsStore?.save(updated) } }
        }
        SettingsScreen(
            user = user,
            onBack = { showSettings = false },
            onSignOut = onLogout,
            themeMode = themeMode,
            onThemeModeSelected = onThemeModeSelected,
            // effective: a legacy stored hours value migrates to a cadence bucket rather
            // than the dialog having nothing to render. Null while loading hides the row.
            pollCadence = notificationSettings?.effectivePollCadence,
            onPollCadenceSelected = { cadence ->
                updateSettings { it.copy(pollCadence = cadence) }
                // The host re-registers the periodic sync at the new cadence.
                onPollCadenceChanged(cadence)
            },
            eventRemindersEnabled = notificationSettings?.eventRemindersEnabled ?: true,
            onEventRemindersEnabledChange = { enabled ->
                updateSettings { it.copy(eventRemindersEnabled = enabled) }
                // Unlike the other two kinds, reminders have already-scheduled OS alarms
                // that only get cancelled/rescheduled when a sync actually runs — and the
                // next periodic one could be up to a day away (ONCE_A_DAY cadence). Force
                // it now, the same way an RSVP change already does (issue #185), so turning
                // reminders off actually silences an imminent one instead of letting it
                // fire anyway.
                onRunEventSync()
            },
            newGroupEventsEnabled = notificationSettings?.newGroupEventsEnabled ?: true,
            onNewGroupEventsEnabledChange = { enabled ->
                updateSettings { it.copy(newGroupEventsEnabled = enabled) }
            },
            topicRepliesEnabled = notificationSettings?.topicRepliesEnabled ?: true,
            onTopicRepliesEnabledChange = { enabled ->
                updateSettings { it.copy(topicRepliesEnabled = enabled) }
            },
            newMessagesEnabled = notificationSettings?.newMessagesEnabled ?: true,
            onNewMessagesEnabledChange = { enabled ->
                // No onRunEventSync() here, unlike the reminders toggle above: messages
                // schedule no OS alarms, so there's nothing already queued that a forced
                // sync would have to cancel. Turning the kind off just means the next sync
                // skips the inbox fetch and clears the mark (EventSyncRunner.sync), which
                // is exactly what the other two alarm-less kinds do.
                updateSettings { it.copy(newMessagesEnabled = enabled) }
            },
            // Debug panel now lives in Settings (debug builds only) instead of the top bar (#207).
            onOpenDebugPanel = if (debugPanelEnabled) {
                { showSettings = false; showDebugPanel = true }
            } else {
                null
            },
            onOpenAbout = { showAbout = true },
        )
        return
    }

    // The message composer (issue #374). ABOVE the open-thread return on purpose: a reply is
    // composed from inside a thread, so it has to win over the screen it was launched from,
    // exactly as the topic composer sits above the feed it posts to.
    if (composingMessage || replyingToRootId != null) {
        val sendState by viewModel.messages.sendState.collectAsState()
        val recipientSearch by viewModel.messages.recipientSearch.collectAsState()
        val replyRootId = replyingToRootId
        val replyThread = openMessageThread?.thread?.takeIf { it.rootId == replyRootId }
        // Leaves the composer without sending; the reply case lands back on its thread,
        // which is still open behind this.
        val leaveComposer = {
            composingMessage = false
            replyingToRootId = null
            viewModel.messages.resetCompose()
        }
        NewMessageScreen(
            sendState = sendState,
            searchState = recipientSearch,
            onQueryChange = { viewModel.messages.searchRecipients(it) },
            onSend = { recipient, subject, bodyText ->
                if (replyRootId != null) {
                    // reply.json, never create.json — see MessagesViewModel.sendReply.
                    viewModel.messages.sendReply(replyRootId, bodyText)
                } else {
                    // Non-null by construction: Send is disabled until a recipient is
                    // committed from the picker, and a typed name is never a recipient.
                    recipient?.let { viewModel.messages.sendNewMessage(it.username, subject, bodyText) }
                }
            },
            onSent = {
                leaveComposer()
                viewModel.messages.acknowledgeSent()
                // No refetch: the sent message was appended to the accumulated list and the
                // threads regrouped, so the conversation behind this — and its list row —
                // already show it.
            },
            onBack = leaveComposer,
            replyTo = replyThread?.let {
                ReplyContext(
                    counterpartName = it.counterpart?.username?.takeIf { name -> name.isNotBlank() }
                        ?: "this conversation",
                    subject = replySubject(it.subject),
                )
            },
        )
        return
    }

    // A conversation is open (issue #371). A pushed sub-screen with its own back arrow, so
    // it early-returns like the topic detail rather than rendering inside the Scaffold the
    // way the messages LIST does — the list is a drawer destination and has to keep the
    // drawer; a thread is one level down from it and back is the way out.
    val threadState = openMessageThread
    if (threadState != null) {
        MessageThreadScreen(
            state = threadState,
            // Blank only in the window before the feed has resolved the user, which the
            // messages list can't be reached in — messageDirection treats it as "everything
            // received", which mis-styles rather than mis-marks (mark-read already ran).
            currentUsername = user?.username.orEmpty(),
            // Read state is already applied by the time this fires: openThread() marked the
            // thread read when it opened it, and updated the list row from the same
            // regrouping, so the row behind is correct the instant it reappears.
            onBack = { viewModel.messages.closeThread() },
            onReply = {
                viewModel.messages.resetCompose()
                replyingToRootId = threadState.thread.rootId
            },
            // Left null on purpose — the mute affordance renders disabled until issue #377
            // supplies the behaviour. See MessageThreadScreen.
            onToggleMute = null,
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
        // Per-topic reply mute (issue #338): load this topic's muted state, then persist a
        // toggle back to the dedicated store. Keyed on topic.id so re-entry reloads it.
        var topicMuted by remember(topic.id) { mutableStateOf(false) }
        LaunchedEffect(topic.id) {
            mutedTopicsStore?.let { topicMuted = topic.id in it.load() }
        }
        TopicDetailRoute(
            topic = topic,
            postsState = topicDetailState,
            isMuted = topicMuted,
            onToggleMute = {
                val store = mutedTopicsStore ?: return@TopicDetailRoute
                val nowMuted = !topicMuted
                topicMuted = nowMuted // optimistic; the store write follows
                muteScope.launch {
                    // mutate(), not load()-then-save(): a background sync's retention
                    // pruning can run concurrently, and separate load/save calls here
                    // could race it and silently lose this toggle (or resurrect a mute
                    // the sync just pruned).
                    withContext(NonCancellable) {
                        store.mutate { current ->
                            if (nowMuted) current + topic.id else current - topic.id
                        }
                    }
                }
            },
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
            onLoadMore = { viewModel.topicDetail.loadMore() },
            onLoadUntil = { target -> viewModel.topicDetail.loadUntilPost(target) },
            // Leaving the thread: sync Ravelry's read marker to how far the user scrolled
            // and mirror it in the feed card's unread badge (issue #206).
            onMarkRead = { lastRead ->
                // The "Your Posts" dot re-check is sequenced behind the mark-read (issue
                // #350 part 2), not fired next to it: it is a GET that would race this
                // POST and, on winning, report the pre-read state.
                viewModel.topicDetail.markRead(topic.id, lastRead) {
                    viewModel.feed.refreshDrawerUnreadAfterReading(topic.id)
                }
                viewModel.feed.markTopicReadUpTo(topic.id, lastRead)
            },
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
            // Restores the reply list's scroll position on re-entry (issue #243): opening a
            // project link from within a topic composes the project page over this route via
            // an early-return above, tearing TopicDetailScreen (and its rememberLazyListState)
            // down; the ViewModel's plain field is what survives to seed it back on return.
            initialScrollPosition = viewModel.topicDetail.scrollPositionFor(topic.id),
            onScrollPositionChanged = { index, offset ->
                viewModel.topicDetail.setScrollPosition(topic.id, index, offset)
            },
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
                    bodySummary = topic.summary.orEmpty(),
                    bodySummaryHtml = topic.summaryHtml.orEmpty(),
                    // A just-created topic has only the opening post and the author has,
                    // by definition, read it — nothing unread to scroll to.
                    postCount = topic.postsCount,
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

    if (composingEventGroup != null) {
        // Captured once for the same reason as `topic`/`permalink` above.
        val group = composingEventGroup!!
        val newEventState by viewModel.newEvent.state.collectAsState()
        val newEventStates by viewModel.newEvent.states.collectAsState()
        NewEventScreen(
            group = group,
            state = newEventState,
            statesForCountry = newEventStates,
            onBack = {
                composingEventGroup = null
                viewModel.newEvent.reset()
            },
            onCountrySelected = { countryId -> viewModel.newEvent.loadStates(countryId) },
            onCreate = { input -> viewModel.newEvent.create(input) },
            onCreated = { permalink ->
                composingEventGroup = null
                viewModel.newEvent.reset()
                // Land the moderator inside their new event, and refresh the events
                // list so it shows up once they navigate back.
                viewModel.eventDetail.load(permalink)
                selectedEventPermalink = permalink
                viewModel.events.load(groups)
            },
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
            onAddEvent = {
                val group = eventsGroup!!
                composingEventGroup = group
                viewModel.newEvent.loadForm(group.id)
            },
        )
        return
    }

    // Initial load: show a full-page loading screen instead of the drawer + empty group
    // list + a settings page with nothing to act on, so the user can't click around
    // half-built chrome while groups are still loading (issue #233).
    //
    // ...but never over Messages (#369). Messages doesn't read the feed state at all, so
    // a Loading state while it's up would replace a perfectly valid screen with a
    // full-page spinner AND take the drawer away, stranding the user. No current caller
    // can reach that combination — feed.load() runs once on entry, and the other two
    // callers (Error retry, pull-to-refresh) are unreachable while Messages covers the
    // feed — so this guard is insurance, not a bug fix. It stops being insurance the
    // moment anything else routes through load().
    if (state is FeedState.Loading && !showingMessages) {
        LaunchLoadingScreen()
        return
    }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val uriHandler = LocalUriHandler.current

    CloseDrawerOnBack(drawerState)

    val title = when {
        showingMessages -> "Messages"
        showingMyPosts -> "Posts"
        else -> loaded?.selectedGroup?.name ?: "FiberSocial"
    }
    val selectedGroup = loaded?.selectedGroup
    // What the chrome treats as the active feed selection. Messages overrides the feed's own
    // selection without clearing it (see showingMessages above), so while it's up neither the
    // group rows nor "Posts" may paint as selected and the top bar must drop the group badge —
    // otherwise two destinations look active at once and the badge contradicts the title.
    val chromeSelectedGroup = if (showingMessages) null else selectedGroup

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            GroupDrawer(
                groups = groups,
                selectedGroup = chromeSelectedGroup,
                myPostsSelected = showingMyPosts && !showingMessages,
                onMyPostsSelected = {
                    scope.launch { drawerState.close() }
                    showingMessages = false
                    viewModel.feed.selectMyPosts()
                },
                messagesSelected = showingMessages,
                onMessagesSelected = {
                    scope.launch { drawerState.close() }
                    showingMessages = true
                },
                messagesHasUnread = drawerUnread.messagesHaveUnread,
                unreadGroupForumIds = drawerUnread.unreadGroupForumIds,
                myPostsHasUnread = drawerUnread.yourPostsHasUnread,
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
                    showingMessages = false
                    viewModel.feed.selectGroup(group)
                },
                onReorder = { viewModel.feed.reorderGroups(it) },
                onFindGroups = { uriHandler.openUri("https://www.ravelry.com/groups/search") },
                isRefreshing = state is FeedState.Refreshing,
                onRefresh = {
                    viewModel.feed.refresh()
                    // Re-check the unread dots on a drawer pull-to-refresh (unread indicators).
                    viewModel.feed.refreshDrawerUnread()
                },
                onLeaveGroup = { group -> viewModel.feed.leaveGroup(group) },
                leavingGroupId = leavingGroupId,
                leaveError = leaveError,
                onAcknowledgeLeaveError = { viewModel.feed.acknowledgeLeaveError() },
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
                FeedTopBar(
                    title = title,
                    selectedGroup = chromeSelectedGroup,
                    onOpenDrawer = {
                        viewModel.feed.acknowledgeJoinError()
                        scope.launch { drawerState.open() }
                    },
                    showUnreadOnly = showUnreadOnly,
                    onToggleUnreadOnly = { showUnreadOnly = !showUnreadOnly },
                    // The unread filter acts on the topic feed, which isn't what's on screen
                    // while Messages is up — leaving it there would offer a control that
                    // silently does nothing.
                    showFilter = !showingMessages,
                )
            },
            floatingActionButton = {
                // Messages gets the FAB slot the feed's own FABs vacate here (issue #374):
                // "New topic" and the events shortcut both act on the topic feed that
                // Messages has replaced, so the slot is free for "New message".
                if (showingMessages) {
                    FloatingActionButton(
                        onClick = {
                            viewModel.messages.resetCompose()
                            composingMessage = true
                        },
                        modifier = Modifier.testTag("NewMessageFab"),
                    ) {
                        Icon(Icons.Default.Edit, contentDescription = "New message")
                    }
                } else if (loaded != null && groups.isNotEmpty()) {
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
            // Messages replaces the feed content while keeping the drawer and top bar, so the
            // user can navigate back out to Posts or a group (issue #370, epic #365).
            if (showingMessages) MessagesScreen(
                state = messagesState,
                onRefresh = { viewModel.messages.refresh() },
                // NOT refresh(): retry has to work from the error state, where refresh has
                // no loaded content to refresh and would no-op — that is exactly the open
                // bug #330 on the events screen ("pull-to-refresh doesn't work on 'Couldn't
                // load events.'"). retry() always restarts the load.
                onRetry = { viewModel.messages.retry() },
                onLoadMore = { viewModel.messages.loadMore() },
                // Opens the conversation detail (issue #371), which also marks it read.
                //
                // THE MARK-READ RACE, closed here: the lambda handed over is the drawer
                // dot's re-probe, and openThread() guarantees it runs only after every
                // mark_read POST has returned. Firing it next to them instead would let
                // the GET win and report the pre-read state, re-lighting the very dot
                // reading the thread just cleared — the trap documented on
                // FeedViewModel.refreshMessagesUnreadAfterReading and on
                // RavelryApiClient.markMessageRead, and the same shape FeedScreen already
                // sequences for topics via TopicDetailViewModel.markRead's onMarked.
                onThreadClick = { thread ->
                    viewModel.messages.openThread(thread.rootId) {
                        viewModel.feed.refreshMessagesUnreadAfterReading()
                    }
                },
                modifier = Modifier.padding(padding),
                listState = messagesListState,
            )
            else when (val s = state) {
                // Loading is handled by the full-page LaunchLoadingScreen early-return
                // above (issue #233), so it never reaches the Scaffold; this arm only
                // keeps the sealed `when` exhaustive.
                FeedState.Loading -> Unit

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
                    val displayedItems = filterUnread(s.items, showUnreadOnly)
                    if (showUnreadOnly && displayedItems.isEmpty()) {
                        UnreadFilterEmptyState(
                            hasMore = s.hasMore,
                            loadingMore = s.loadingMore,
                            onLoadMore = { viewModel.feed.loadMore() },
                        )
                    } else {
                        FeedList(
                            items = displayedItems,
                            hasMore = s.hasMore,
                            loadingMore = s.loadingMore,
                            onLoadMore = { viewModel.feed.loadMore() },
                            listState = feedListState,
                            pinnedCollapsed = pinnedCollapsed,
                            onTogglePinnedCollapsed = { pinnedCollapsed = !pinnedCollapsed },
                            pinnedSectionEnabled = !showingMyPosts,
                            showGroupNames = showingMyPosts,
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
                // Switching groups (issue #214) empties the content while the new group
                // loads: show a centered spinner over the blank area instead of an empty
                // list under the pull indicator. A normal refresh keeps its stale content.
                // (This still gates on the RAW stale list, not the filtered one below — it's
                // asking "is there any previous content at all yet", not "did the filter
                // match anything".)
                is FeedState.Refreshing -> if (s.stale.items.isEmpty()) {
                    Box(
                        modifier = Modifier.fillMaxSize().padding(padding),
                        contentAlignment = Alignment.Center,
                    ) { CircularProgressIndicator() }
                } else PullToRefreshBox(
                    refreshing = true,
                    onRefresh = { viewModel.feed.refresh() },
                    modifier = Modifier.padding(padding),
                ) {
                    val displayedItems = filterUnread(s.stale.items, showUnreadOnly)
                    if (showUnreadOnly && displayedItems.isEmpty()) {
                        UnreadFilterEmptyState(
                            hasMore = s.stale.hasMore,
                            loadingMore = s.stale.loadingMore,
                            onLoadMore = { viewModel.feed.loadMore() },
                        )
                    } else {
                        FeedList(
                            items = displayedItems,
                            hasMore = s.stale.hasMore,
                            loadingMore = s.stale.loadingMore,
                            onLoadMore = { viewModel.feed.loadMore() },
                            listState = feedListState,
                            pinnedCollapsed = pinnedCollapsed,
                            onTogglePinnedCollapsed = { pinnedCollapsed = !pinnedCollapsed },
                            pinnedSectionEnabled = !showingMyPosts,
                            showGroupNames = showingMyPosts,
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
    }

    if (showDebugPanel) {
        DebugPanel(
            onForceSessionExpiry = { viewModel.debugForceSessionExpiry() },
            onForceFeedError = { viewModel.debugForceFeedError() },
            onRunEventSync = onRunEventSync,
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
    onLoadMore: () -> Unit = {},
    onLoadUntil: (Int) -> Unit = {},
    onMarkRead: (Int) -> Unit = {},
    isMuted: Boolean = false,
    onToggleMute: () -> Unit = {},
    currentUsername: String? = null,
    deleteState: DeleteState = DeleteState.Idle,
    onDeletePost: (Post) -> Unit = {},
    onDeleteErrorShown: () -> Unit = {},
    editState: EditState = EditState.Idle,
    onEditPost: (Post, String) -> Unit = { _, _ -> },
    onEditErrorShown: () -> Unit = {},
    attachment: ImageAttachmentState = ImageAttachmentState.Idle,
    onImagePicked: (String) -> Unit = {},
    onAttachmentInserted: () -> Unit = {},
    onPickFromProjects: (() -> Unit)? = null,
    initialScrollPosition: ScrollPosition = ScrollPosition.TOP,
    onScrollPositionChanged: (index: Int, offset: Int) -> Unit = { _, _ -> },
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
        onLoadMore = onLoadMore,
        onLoadUntil = onLoadUntil,
        onMarkRead = onMarkRead,
        isMuted = isMuted,
        onToggleMute = onToggleMute,
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
        initialScrollPosition = initialScrollPosition,
        onScrollPositionChanged = onScrollPositionChanged,
    )
}

/**
 * Renders the pick-a-project-photo dialog for whichever composer is on screen and
 * routes the picked photo's markdown into that composer's attachment flow ([target]).
 * The dialog state lives in [FeedScreenModel.projectPicker]; only one composer
 * is ever visible at a time, so a single picker instance is safe to share.
 */
@Composable
private fun ProjectPhotoPickerHost(viewModel: FeedScreenModel, target: ImageAttachmentViewModel) {
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

/**
 * A thin scroll indicator for the drawer's group list (issue #347).
 *
 * Compose Multiplatform has no built-in scrollbar for `LazyColumn` on Android/iOS, so
 * this derives one from [state]. It renders nothing when everything already fits — the
 * point is to signal "there is more below", and a permanently-visible bar on a short
 * list would be noise rather than information.
 *
 * The thumb is sized and positioned from ITEM COUNTS, not pixel offsets: exact pixel
 * extents aren't knowable for a lazy list whose off-screen rows have never been measured.
 * Rows here are near enough uniform height that the approximation reads correctly, and
 * it degrades gracefully — the thumb tracks proportionally even if a row is taller.
 */
@Composable
private fun GroupListScrollbar(state: LazyListState, modifier: Modifier = Modifier) {
    val info = state.layoutInfo
    val total = info.totalItemsCount
    val visible = info.visibleItemsInfo.size
    // Nothing to scroll (or nothing laid out yet) — draw nothing at all.
    if (total == 0 || visible == 0 || visible >= total) return
    val visibleFraction = visible.toFloat() / total
    // The last scroll position puts the final item on screen, so the furthest the first
    // visible index can reach is total - visible, not total.
    val maxFirstIndex = (total - visible).coerceAtLeast(1)
    val scrollFraction = (state.firstVisibleItemIndex.toFloat() / maxFirstIndex).coerceIn(0f, 1f)
    BoxWithConstraints(modifier.fillMaxHeight().width(4.dp).padding(vertical = 4.dp)) {
        val thumbHeight = maxHeight * visibleFraction
        Box(
            Modifier
                .offset(y = (maxHeight - thumbHeight) * scrollFraction)
                .height(thumbHeight)
                .width(4.dp)
                .background(
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                    RoundedCornerShape(2.dp),
                ),
        )
    }
}

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

/**
 * Shown in place of the topic list when the "unread only" filter (issue #210) has
 * nothing to show — every topic currently loaded for this group has already been read.
 * Scrollable (like [FeedErrorState]) so the surrounding [PullToRefreshBox] still has a
 * nested-scrolling child to engage pull-to-refresh on.
 *
 * Replacing [FeedList] here means its own scroll-triggered pagination never mounts, so
 * when [hasMore] is true this offers an explicit "Check more topics" affordance instead
 * — otherwise a group whose loaded page(s) happen to be fully read would report "No
 * unread topics" with no way to reach further, unfetched pages that might have some.
 */
@Composable
internal fun UnreadFilterEmptyState(
    hasMore: Boolean = false,
    loadingMore: Boolean = false,
    onLoadMore: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = if (hasMore) "No unread topics in what's loaded so far" else "No unread topics",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 32.dp),
        )
        if (hasMore) {
            Spacer(modifier = Modifier.height(12.dp))
            if (loadingMore) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            } else {
                TextButton(onClick = onLoadMore) { Text("Check more topics") }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupDrawer(
    groups: List<Group>,
    selectedGroup: Group?,
    myPostsSelected: Boolean = false,
    onMyPostsSelected: () -> Unit = {},
    messagesSelected: Boolean = false,
    onMessagesSelected: () -> Unit = {},
    // Unread indicators (unread dots): forum ids of groups with unread posts, and whether
    // the user's own posts have unread replies. A dot appears on the matching rows.
    unreadGroupForumIds: Set<Long> = emptySet(),
    myPostsHasUnread: Boolean = false,
    // Whether the message inbox holds anything unread (#372). Unlike the group dots this is
    // a straight server answer — Ravelry has a real per-message read flag — so there is no
    // local last-viewed state behind it.
    messagesHasUnread: Boolean = false,
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
    onFindGroups: () -> Unit = {},
    onLeaveGroup: (Group) -> Unit = {},
    leavingGroupId: Long? = null,
    leaveError: String? = null,
    onAcknowledgeLeaveError: () -> Unit = {},
    isRefreshing: Boolean = false,
    onRefresh: () -> Unit = {},
) {
    // The group awaiting a leave confirmation (issue #231), if any.
    var pendingLeave by remember { mutableStateOf<Group?>(null) }
    pendingLeave?.let { group ->
        // Leaving takes a moment (the app re-scrapes memberships), so the dialog stays open
        // and turns into a spinner until it's done. It used to unconditionally auto-dismiss
        // once the spinner cleared, whether the leave succeeded or failed — a non-session
        // failure (e.g. a 403 or transient network error) left the group in the drawer with
        // zero feedback that anything went wrong (issue #263). Now it only auto-dismisses on
        // success; a failure keeps it open with the error message and a Retry.
        val leaving = leavingGroupId == group.id
        var started by remember(group.id) { mutableStateOf(false) }
        LaunchedEffect(leaving) {
            if (started && !leaving && leaveError == null) pendingLeave = null
        }
        AlertDialog(
            // Can't dismiss mid-leave — the spinner is doing work.
            onDismissRequest = {
                if (!leaving) {
                    pendingLeave = null
                    onAcknowledgeLeaveError()
                }
            },
            title = { Text("Leave ${group.name}?") },
            text = {
                when {
                    leaving -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Leaving ${group.name}…")
                    }
                    leaveError != null -> Text(leaveError, color = MaterialTheme.colorScheme.error)
                    else -> Text("You'll stop seeing this group's topics. You can re-join it from Ravelry.")
                }
            },
            confirmButton = {
                if (!leaving) {
                    TextButton(onClick = { started = true; onLeaveGroup(group) }) {
                        Text(if (leaveError != null) "Retry" else "Leave")
                    }
                }
            },
            dismissButton = {
                if (!leaving) {
                    TextButton(onClick = { pendingLeave = null; onAcknowledgeLeaveError() }) { Text("Cancel") }
                }
            },
        )
    }
    // Reordering is an explicit mode (issue #97): outside it the list is locked; inside
    // it each row grows a drag handle, row taps are disabled, and rows can be dragged —
    // immediately from the handle, or via long-press anywhere on the row.
    var reorderMode by remember { mutableStateOf(false) }

    // Folds the "Groups" section (list + "Find groups") behind its header pill.
    // Session-scoped like the feed's pinnedCollapsed: a standing preference, not
    // persisted. Toggling is locked during reorder mode — collapsing mid-drag would
    // tear the rows being dragged out of composition.
    var groupsCollapsed by remember { mutableStateOf(false) }

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
        if (current.index != from) return
        // The neighbor whose midpoint the dragged row covers becomes the drop slot.
        val top = current.offset + dragOffset
        val bottom = top + current.size
        val target = visible.firstOrNull { info ->
            info.key != id &&
                localGroups.any { it.id == info.key } &&
                (info.offset + info.size / 2f) in top..bottom &&
                info.index == localGroups.indexOfFirst { it.id == info.key }
        } ?: return
        val to = target.index
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
            // The cross-group "My Posts" entry and the "Groups" section header. Both sit
            // ABOVE the scrolling list, not inside it (issue #347): they are the drawer's
            // fixed navigation, so they stay put while the group list scrolls under them
            // rather than sliding away once a user with many groups scrolls down.
            //
            // Consequence worth knowing: because they left the LazyColumn, the group rows
            // now start at lazy index 0, which is why DRAWER_ITEMS_BEFORE_GROUPS (formerly
            // 2, compensating for these two items) is gone from the drag-to-reorder math.
            //
            // "Posts", "Messages" and "Groups" each get a HorizontalDivider ABOVE them, the
            // same treatment the "Send feedback" row gets at the bottom of the sheet.
            // Unselected NavigationDrawerItems paint no pill, so on their own they read as
            // flat text on the sheet surface and don't look tappable; a rule above each one
            // reads as a list of discrete tappable rows, which bracketing the group as a
            // single block did not. There is deliberately NO rule below "Groups" — it heads
            // the group list beneath it, so a line there would cut the header off from its
            // own children rather than separating two peers.
            //
            // The test tags name the row each rule sits above, so adding a destination is a
            // matter of adding one more "AboveX" tag rather than renumbering Top/Middle/Bottom
            // (which is what these were before Messages landed between Posts and Groups).
            Spacer(Modifier.height(16.dp))
            HorizontalDivider(Modifier.testTag("DrawerNavDividerAbovePosts"))
            Spacer(Modifier.height(8.dp))
            NavigationDrawerItem(
                label = { Text("Posts") },
                selected = myPostsSelected,
                onClick = { if (!reorderMode) onMyPostsSelected() },
                icon = {
                    IconWithUnreadDot(hasUnread = myPostsHasUnread) {
                        Icon(Icons.Default.Person, contentDescription = null)
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(Modifier.testTag("DrawerNavDividerAboveMessages"))
            Spacer(Modifier.height(8.dp))
            // Direct messages (#369). A peer of "Posts" rather than a group-list entry, so it
            // gets the same row treatment, including the unread-dot slot.
            NavigationDrawerItem(
                label = { Text("Messages") },
                selected = messagesSelected,
                onClick = { if (!reorderMode) onMessagesSelected() },
                icon = {
                    IconWithUnreadDot(hasUnread = messagesHasUnread) {
                        Icon(Icons.Default.Email, contentDescription = null)
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            HorizontalDivider(Modifier.testTag("DrawerNavDividerAboveGroups"))
            Spacer(Modifier.height(8.dp))
            // The section header is a NavigationDrawerItem so its pill matches the "Posts"
            // row above, and it folds the group list like the feed's pinned section: same
            // rotating disclosure chevron (right = folded, down = open). While open the
            // badge slot holds the reorder Edit/Done button; folded it shows the hidden
            // group count, and the pill highlights when the active feed is one of them.
            val chevronRotation by animateFloatAsState(
                disclosureChevronRotation(groupsCollapsed, LocalLayoutDirection.current),
            )
            NavigationDrawerItem(
                label = { Text("Groups") },
                selected = groupsCollapsed && selectedGroup != null,
                onClick = { if (!reorderMode) groupsCollapsed = !groupsCollapsed },
                icon = {
                    Icon(
                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = if (groupsCollapsed) {
                            "Expand your groups"
                        } else {
                            "Collapse your groups"
                        },
                        modifier = Modifier.rotate(chevronRotation),
                    )
                },
                badge = {
                    if (groupsCollapsed) {
                        Text(
                            text = groups.size.toString(),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        TextButton(onClick = { reorderMode = !reorderMode }) {
                            Text(if (reorderMode) "Done" else "Edit")
                        }
                    }
                },
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.height(8.dp))
            // Issue #246: joining a group elsewhere in the app left no way to refresh the
            // drawer's list short of leaving and re-entering the app. Pull-to-refresh over
            // the group list matches every other interactable list/drawer in the app
            // (feed, events, topic detail) instead of a one-off icon button; it reuses the
            // same feed refresh that already re-fetches groups (FeedViewModel.refresh() ->
            // getUserGroups()).
            //
            // Disabled during reorder mode: FeedViewModel.refresh() synchronously flips
            // state away from Loaded, which makes reorderGroups() silently no-op (dropping
            // an in-progress drag without persisting it), and the LaunchedEffect(groups)
            // resync that follows would clobber the drawer's working localGroups copy
            // mid-drag. Row taps and the events badge already lock the same way (#231).
            PullToRefreshBox(
                refreshing = isRefreshing,
                onRefresh = onRefresh,
                modifier = Modifier.weight(1f),
                enabled = !reorderMode,
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().testTag("GroupList"),
                ) {
                    if (!groupsCollapsed) {
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
                            {
                                IconWithUnreadDot(hasUnread = group.forumId in unreadGroupForumIds) {
                                    GroupBadge(group = group, size = 28.dp)
                                }
                            }
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
                        badge = when {
                            // In edit mode the trailing slot offers "leave this group" (#231)
                            // in place of the event badge.
                            reorderMode -> {
                                {
                                    IconButton(
                                        onClick = {
                                            // Clears any leaveError left over from a
                                            // previous group's dialog that got dismissed
                                            // without going through Cancel/onDismissRequest
                                            // (e.g. a deep link tearing the drawer down
                                            // while an error was showing) — otherwise this
                                            // fresh dialog for a DIFFERENT group would open
                                            // already showing that stale error/Retry.
                                            onAcknowledgeLeaveError()
                                            pendingLeave = group
                                        },
                                    ) {
                                        Icon(
                                            Icons.Default.Delete,
                                            contentDescription = "Leave ${group.name}",
                                            tint = MaterialTheme.colorScheme.error,
                                        )
                                    }
                                }
                            }
                            eventCount > 0 -> {
                                {
                                    GroupEventsBadge(
                                        count = eventCount,
                                        // Same reasoning as the row's own onClick above: a
                                        // rearrange can't be allowed to navigate away.
                                        onClick = { if (!reorderMode) onGroupEventsClick(group) },
                                    )
                                }
                            }
                            else -> null
                        },
                        modifier = Modifier
                            .padding(horizontal = 12.dp)
                            // Displaced rows slide to their new slot so the reorder is
                            // visible; the dragged row itself must not animate — it is
                            // positioned by the finger (translationY below), and a
                            // placement animation would fight the swap compensation.
                            .then(if (dragging) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
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
                // Discover and join more groups on Ravelry's own search page (issue #232 —
                // linking out rather than rebuilding search in-app).
                item(key = "find-groups") {
                    NavigationDrawerItem(
                        label = { Text("Find groups") },
                        selected = false,
                        onClick = onFindGroups,
                        icon = { Icon(Icons.Default.Search, contentDescription = null) },
                        modifier = Modifier.padding(horizontal = 12.dp),
                    )
                }
                } // end of the collapsible "Groups" section (groupsCollapsed)
                item(key = "drawer-footer-spacer") { Spacer(Modifier.height(16.dp)) }
                }
                GroupListScrollbar(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
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
 * FeedScreen call site that is the currently-viewed group, which is null both when the
 * user belongs to no groups and while viewing the cross-group My Posts feed.
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
 * A small filled dot marking unread activity — the drawer's unread indicator, shown via
 * [IconWithUnreadDot] on a "Posts" / group row whose posts have unread replies.
 */
@Composable
private fun UnreadDot(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(9.dp)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .semantics { contentDescription = "Unread posts" },
    )
}

/**
 * Overlays an [UnreadDot] on the top-trailing corner of [content] (a drawer row's leading
 * icon) when [hasUnread]. The dot sits on the icon rather than the trailing badge slot,
 * which the group rows already use for the events count / leave control.
 */
@Composable
private fun IconWithUnreadDot(hasUnread: Boolean, content: @Composable () -> Unit) {
    Box {
        content()
        if (hasUnread) {
            UnreadDot(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 3.dp, y = (-3).dp),
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

/**
 * Purely client-side "unread only" filter (issue #210) — no new API call, since
 * [FeedItem.unreadCount] is already tracked per topic. `sticky` is just a sort-order flag on
 * [FeedItem] (pinned topics live in the same flat list, sorted first), not a separate list, so
 * this predicate covers sticky and non-sticky topics identically: a read sticky topic is
 * filtered out just like any other read topic.
 */
internal fun filterUnread(items: List<FeedItem>, showUnreadOnly: Boolean): List<FeedItem> =
    if (showUnreadOnly) items.filter { it.unreadCount > 0 } else items

/** Visible-item slack before the end of the list that triggers [onLoadMore]. */
private const val LOAD_MORE_THRESHOLD = 5

@Composable
internal fun FeedList(
    items: List<FeedItem>,
    hasMore: Boolean,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    pinnedCollapsed: Boolean = false,
    onTogglePinnedCollapsed: () -> Unit = {},
    pinnedSectionEnabled: Boolean = true,
    showGroupNames: Boolean = false,
    onTopicClick: (FeedItem) -> Unit,
) {
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

    // Sticky topics are already sorted first within the flat list (FeedRepository), but
    // partition rather than split at the first non-sticky index so a sticky item is never
    // stranded outside the section if the ordering assumption ever changes upstream.
    //
    // The section is disabled entirely for the cross-group "My Posts" feed: sticky means
    // "pinned in its own forum", which isn't a meaningful grouping across groups — there
    // the sticky flag stays a per-card label only.
    val (pinnedItems, regularItems) = if (pinnedSectionEnabled) {
        items.partition { it.sticky }
    } else {
        emptyList<FeedItem>() to items
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (pinnedItems.isNotEmpty()) {
            item(key = "pinned-header") {
                PinnedSectionHeader(
                    count = pinnedItems.size,
                    unreadCount = pinnedItems.sumOf { it.unreadCount },
                    collapsed = pinnedCollapsed,
                    onToggle = onTogglePinnedCollapsed,
                    modifier = Modifier.animateItem().padding(horizontal = 16.dp),
                )
            }
            if (!pinnedCollapsed) {
                items(pinnedItems, key = { it.id }) { item ->
                    // Indented past the regular cards, with an accent rail in the
                    // header's tint attaching them to it, so the open section reads
                    // as "these belong to the header above" rather than more feed.
                    // animateItem() makes the fold visible motion (fade + the list
                    // resettling) instead of an instant layout jump.
                    Row(
                        modifier = Modifier
                            .animateItem()
                            .padding(start = 20.dp, end = 16.dp)
                            .height(IntrinsicSize.Min),
                    ) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(2.dp))
                                .background(MaterialTheme.colorScheme.secondaryContainer),
                        )
                        Spacer(modifier = Modifier.width(9.dp))
                        TopicCard(
                            item = item,
                            onClick = { onTopicClick(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
        items(regularItems, key = { it.id }) { item ->
            TopicCard(
                item = item,
                onClick = { onTopicClick(item) },
                showGroupName = showGroupNames,
                modifier = Modifier.animateItem().padding(horizontal = 16.dp),
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
 * Header row above the pinned (sticky) topics in the feed. Tapping anywhere on it folds
 * the section closed or back open — the chevron only mirrors the state. Only rendered
 * when the feed actually has pinned topics, so a collapsed-but-empty section can't leave
 * a dangling header behind.
 *
 * While folded, the header carries the section's total unread count (summed over the
 * hidden cards' [FeedItem.unreadCount]) so collapsing can't silently swallow new
 * replies. Expanded it stays quiet — each visible card already wears its own "N new"
 * badge, and doubling that up on the header would just be noise.
 */
@Composable
private fun PinnedSectionHeader(
    count: Int,
    unreadCount: Int,
    collapsed: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // A filled tonal band, deliberately unlike the elevated surface-colored TopicCards
    // around it, so the row reads as a section control and not as just another post.
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .clickable(role = Role.Button, onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            text = if (count == 1) "📌 1 pinned topic" else "📌 $count pinned topics",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
        if (collapsed && unreadCount > 0) {
            Spacer(modifier = Modifier.width(8.dp))
            // Bold like TopicCard's per-topic unread badge, so the folded header
            // reads as the sum of the badges it's hiding.
            Text(
                text = "$unreadCount new",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        // Disclosure-style chevron: points right while folded, rotates to point down
        // while open — the folder/accordion convention, less ambiguous than up/down
        // arrows ("is down the state or the action?"). The rotation is animated so a
        // tap visibly turns the chevron rather than swapping it.
        val chevronRotation by animateFloatAsState(
            disclosureChevronRotation(collapsed, LocalLayoutDirection.current),
        )
        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = if (collapsed) "Expand pinned topics" else "Collapse pinned topics",
            tint = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.rotate(chevronRotation),
        )
    }
}

/**
 * Rotation (clockwise degrees) for a disclosure chevron — [PinnedSectionHeader]'s and the
 * drawer's "Groups" section header both use this.
 *
 * `Icons.AutoMirrored.Filled.KeyboardArrowRight` flips horizontally in RTL layouts (it
 * points left while folded there, matching every other directional icon in this app), but
 * [androidx.compose.ui.Modifier.rotate] always turns clockwise in screen space regardless
 * of layout direction. A fixed +90° would rotate the already-mirrored (left-pointing) icon
 * up instead of down once expanded, so the sign flips for RTL to keep "expanded" pointing
 * down in both directions.
 */
internal fun disclosureChevronRotation(collapsed: Boolean, layoutDirection: LayoutDirection): Float =
    if (collapsed) 0f else if (layoutDirection == LayoutDirection.Rtl) -90f else 90f

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
