package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Covers issue #69: pulling down on the reply thread calls the screen's onRefresh. */
@RunWith(RobolectricTestRunner::class)
class TopicDetailScreenPullToRefreshTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val topic = FeedItem(
        id = 1L,
        groupId = 2L,
        groupName = "Test Group",
        lastPostAt = null,
        author = RavelryUser(username = "tester"),
        title = "A topic",
        bodySummary = "",
        postCount = 0,
    )

    /**
     * Opens the jump chooser (issue #309 follow-up: "Jump to last read" and "Jump to
     * newest" are two menu items behind a single FAB, rather than each being its own
     * always-visible button) so a test can then tap whichever item it needs.
     */
    private fun openJumpMenu() {
        compose.onNodeWithContentDescription("Jump").performClick()
    }

    @Test
    fun `pulling down on a loaded thread invokes onRefresh`() {
        var refreshCount = 0
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(posts = emptyList()),
                onBack = {},
                onVote = { _, _ -> },
                onRefresh = { refreshCount++ },
            )
        }

        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, refreshCount)
    }

    @Test
    fun `offers jump-to-last-read when the unread post is off screen`() {
        // Issue #185: firstUnreadPostNumber drives a jump target that scrolls the thread
        // to the first unread post. Enough posts that the target is genuinely below the
        // fold, unlike issue #255's short-topic case below.
        val posts = (1..60L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 60, unreadCount = 10, firstUnreadPostNumber = 50)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        openJumpMenu()
        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
    }

    @Test
    fun `offers jump-to-last-read for a long unread topic even though post 1 is unread`() {
        // On-device review of #255 found that gating on firstUnread (rather than
        // postCount) broke a real case: a topic nobody has read yet has
        // firstUnreadPostNumber == 1, and post 1 is trivially visible the instant the
        // screen opens — that would hide the button immediately even for a 60-post
        // thread that's almost entirely below the fold.
        val posts = (1..60L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 60, unreadCount = 60, firstUnreadPostNumber = 1)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        openJumpMenu()
        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
    }

    @Test
    fun `hides the jump FAB entirely when the whole topic already fits on screen`() {
        // Issue #255: a short topic (here, 3 posts) can render entirely within the
        // viewport, including its one unread post — there's nothing left to "jump" to,
        // even though the thread technically has an unread post. Neither destination
        // (last read or newest) differs from where the user already is, so the FAB
        // itself shouldn't appear at all. Scrolls to the last post explicitly (mirroring
        // the "all caught up" test below) since Robolectric's test viewport doesn't
        // necessarily fit all 3 posts on the very first layout pass the way a real
        // device screen does.
        val unreadTopic = topic.copy(postCount = 3, unreadCount = 2, firstUnreadPostNumber = 2)
        val posts = listOf(
            Post(id = 1L, bodyHtml = "<p>one</p>", user = RavelryUser(username = "a")),
            Post(id = 2L, bodyHtml = "<p>two</p>", user = RavelryUser(username = "b")),
            Post(id = 3L, bodyHtml = "<p>three</p>", user = RavelryUser(username = "c")),
        )
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("three"))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Jump").assertDoesNotExist()
    }

    @Test
    fun `drops jump-to-last-read from the menu once scrolled past the unread target, well before the thread's true end`() {
        // On-device review: a long-running historical thread (hundreds of posts over
        // months) kept the option around long after the user had scrolled past the
        // actual unread post, because visibility was (wrongly) gated on the thread's
        // literal last post instead of the real target. The FAB itself still shows here
        // (jump-to-newest is still meaningful — post 600 is still below the fold), but
        // "Jump to last read" specifically should no longer be offered.
        val posts = (1..600L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 600, unreadCount = 50, firstUnreadPostNumber = 550)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("post 555"))
        compose.waitForIdle()
        openJumpMenu()
        compose.onNodeWithText("Jump to last read").assertDoesNotExist()
    }

    @Test
    fun `jump-to-last-read reappears in the menu when scrolling back up past the furthest point reached`() {
        // Issue #257 (follow-up to #255/#256): once the user has scrolled deeper than
        // the original unread marker, that deeper point is the more useful thing to jump
        // back to. Before this fix, showJump stayed gated on the static original
        // firstUnreadPostNumber, so scrolling back up from post 200 (after reading past
        // post 50's original marker) found no way back to 200 — the option just never
        // reappeared.
        val posts = (1..300L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 300, unreadCount = 250, firstUnreadPostNumber = 50)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        // Scroll well past the original marker (post 50) down to post 200 — furthestSeen
        // now tracks 200 — then back up to post 100: still past the ORIGINAL marker (50),
        // but well short of the deepest point actually reached (200). Never opens the
        // menu mid-sequence (a still-open DropdownMenu would intercept the scroll, and
        // reliably dismissing one from a Robolectric test turned out to be its own can of
        // worms); the "drops once scrolled past" half of this behavior has its own
        // dedicated test above, so this one only needs to prove the reappearance itself.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("post 200"))
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("post 100"))
        compose.waitForIdle()
        openJumpMenu()
        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
    }

    @Test
    fun `tapping the reappeared jump-to-last-read scrolls to the furthest point reached, not the stale original marker`() {
        val posts = (1..300L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 300, unreadCount = 250, firstUnreadPostNumber = 50)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("post 200"))
        compose.waitForIdle()
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("post 100"))
        compose.waitForIdle()

        openJumpMenu()
        compose.onNodeWithText("Jump to last read").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("post 200").assertIsDisplayed()
    }

    @Test
    fun `jumping to last read does not advance the read marker past it`() {
        // Regression for a bug found on-device: scrollToItem/animateScrollToItem land the
        // target at the TOP of the viewport by default, pulling a screenful of not-yet-read
        // posts into view below it. furthestSeen tracks the LAST visible item, so those
        // freshly-visible-but-unread posts got silently counted as "seen" too — the reported
        // read marker on leaving the thread landed past where the user actually jumped to,
        // and repeated jumps would creep further ahead each time.
        val posts = (1..300L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 300, unreadCount = 100, firstUnreadPostNumber = 200)
        var markedTo: Int? = null
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
                onMarkRead = { markedTo = it },
            )
        }

        openJumpMenu()
        compose.onNodeWithText("Jump to last read").performClick()
        compose.waitForIdle()
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }

        compose.runOnIdle { assertEquals(200, markedTo) }
    }

    @Test
    fun `offers both jump-to-last-read and jump-to-newest as distinct menu items on an unread topic`() {
        // Issue #309 follow-up: "jump to last read" and "jump to newest" are distinct,
        // both potentially useful regardless of read state — not one control that only
        // ever means "resume" or only ever means "skip to the end". They're two items
        // behind one FAB (not two separately-floating buttons) since they'd differ for
        // the WHOLE time spent catching up on a thread, not just occasionally — two
        // permanently-visible buttons would read as cluttered/competing.
        val posts = (1..300L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 300, unreadCount = 250, firstUnreadPostNumber = 50)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        openJumpMenu()
        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
        compose.onNodeWithText("Jump to newest").assertIsDisplayed()
    }

    @Test
    fun `jump-to-newest stays offered after marking all as read, unlike jump-to-last-read`() {
        // "Jump to newest" is a plain navigation shortcut (scroll to the end), not a
        // read-tracking action — it should still be offered as a way to skip to the
        // bottom even after "mark all as read" drops "Jump to last read".
        val posts = (1..300L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 300, unreadCount = 250, firstUnreadPostNumber = 50)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Mark all as read").performClick()
        compose.waitForIdle()

        openJumpMenu()
        compose.onNodeWithText("Jump to last read").assertDoesNotExist()
        compose.onNodeWithText("Jump to newest").assertIsDisplayed()
    }

    @Test
    fun `tapping jump-to-newest scrolls to the true last post and marks it read`() {
        val posts = (1..300L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 300, unreadCount = 250, firstUnreadPostNumber = 50)
        var markedTo: Int? = null
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
                onMarkRead = { markedTo = it },
            )
        }

        openJumpMenu()
        compose.onNodeWithText("Jump to newest").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("post 300").assertIsDisplayed()

        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { assertEquals(300, markedTo) }
    }

    @Test
    fun `hides the jump FAB once the true last post is on screen`() {
        val posts = listOf(
            Post(id = 1L, bodyHtml = "<p>one</p>", user = RavelryUser(username = "a")),
            Post(id = 2L, bodyHtml = "<p>two</p>", user = RavelryUser(username = "b")),
            Post(id = 3L, bodyHtml = "<p>three</p>", user = RavelryUser(username = "c")),
        )
        val readTopic = topic.copy(postCount = 3, unreadCount = 0, firstUnreadPostNumber = null)
        compose.setContent {
            TopicDetailScreen(
                topic = readTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.onNode(hasScrollAction()).performScrollToNode(hasText("three"))
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Jump").assertDoesNotExist()
    }

    @Test
    fun `newest menu item reflects the topic once it finishes loading, not stuck on the initial Loading frame`() {
        // Regression for a bug found on-device: showJumpToNewest's derivedStateOf closed
        // over `loaded`/`postCount` (plain vals derived from postsState, not genuine State
        // reads) inside a key-less remember. That freezes the closure on whichever values
        // were current on the FIRST composition — Loading, i.e. loaded=null/postCount=0 —
        // and it never sees the topic actually finish loading, so "Jump to newest" never
        // appeared in the menu at all. (The FAB itself is a poor probe for this bug:
        // showJump doesn't have the same issue — its jumpTarget comes straight from the
        // stable `topic` parameter, available immediately — so the FAB is already visible
        // from the very first Loading frame on any unread topic, masking whether
        // showJumpToNewest specifically ever recovers.) Every other test in this file
        // passes postsState = Loaded directly from setContent, which happens to dodge
        // this exact bug by never actually going through a Loading -> Loaded transition —
        // this test deliberately does, the way the real screen (TopicDetailViewModel.load())
        // always does.
        val posts = (1..300L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 300, unreadCount = 250, firstUnreadPostNumber = 50)
        var state by mutableStateOf<TopicDetailState>(TopicDetailState.Loading)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = state,
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        state = TopicDetailState.Loaded(posts = posts, hasMore = false)
        compose.waitForIdle()

        openJumpMenu()
        compose.onNodeWithText("Jump to newest").assertIsDisplayed()
    }

    @Test
    fun `tapping the jump FAB again mid-jump does not reopen the menu`() {
        // Regression: the FAB's own `pending` guard (pendingJump || pendingJumpToNewest ||
        // isJumping) must block reopening the chooser while a jump from EITHER destination
        // is still resolving — the two menu items each close the menu immediately on tap
        // (jumpMenuExpanded = false) before starting their own jump, so there's no way to
        // have both items open/tappable at once; the FAB reopening mid-jump is the only
        // remaining path back to a second jump attempt, and this is what actually gates it
        // now (rather than a race between two already-open menu items' animations).
        val posts = (1..300L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val unreadTopic = topic.copy(postCount = 300, unreadCount = 250, firstUnreadPostNumber = 50)
        compose.setContent {
            TopicDetailScreen(
                topic = unreadTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        // Open the menu first (its own entrance animation needs the clock running), THEN
        // freeze it so "Jump to last read"'s animateScrollToItem(50) genuinely suspends
        // mid-flight instead of resolving within a single test-idle pass.
        openJumpMenu()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("Jump to last read").performClick()
        compose.mainClock.advanceTimeByFrame()

        // Tap the FAB again while that jump is still in flight. It's showing its
        // spinner (no "Jump" contentDescription) at this point, hence the stable
        // testTag instead — see the FAB's own comment on why that tag exists.
        compose.onNodeWithTag("JumpFab").performClick()

        compose.mainClock.autoAdvance = true
        compose.waitForIdle()

        // The menu never reopened, so the first jump reaches its own target (post 50)
        // undisturbed, and "Jump to newest" was never offered a chance to hijack it.
        compose.onNodeWithText("post 50").assertIsDisplayed()
        compose.onNodeWithText("Jump to newest").assertDoesNotExist()
    }

    @Test
    fun `offers jump-to-last-read for a fully-read topic that isn't fully visible, targeting the end`() {
        // On-device review of #255/#256 asked for the same target (not a distinct one)
        // to still offer a way to skip to the bottom of a topic with nothing unread, as
        // long as the thread isn't already fully on screen — visually and behaviorally
        // identical to the unread case, just aimed at the last post instead. (Its target
        // coincides with jump-to-newest's here, so the menu offers both even though
        // they'd currently land in the same place — kept simple/predictable rather than
        // suppressing the redundant one.)
        val posts = (1..60L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val readTopic = topic.copy(postCount = 60, unreadCount = 0, firstUnreadPostNumber = null)
        compose.setContent {
            TopicDetailScreen(
                topic = readTopic,
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        openJumpMenu()
        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
    }

    @Test
    fun `tapping jump-to-last-read on a fully-read topic scrolls to the last post`() {
        val posts = (1..60L).map { id ->
            Post(id = id, bodyHtml = "<p>post $id</p>", user = RavelryUser(username = "a"))
        }
        val readTopic = topic.copy(postCount = 60, unreadCount = 0, firstUnreadPostNumber = null)
        compose.setContent {
            TopicDetailScreen(
                topic = readTopic,
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        openJumpMenu()
        compose.onNodeWithText("Jump to last read").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("post 60").assertIsDisplayed()
    }

    @Test
    fun `shows the all-caught-up marker at the end of a fully loaded thread`() {
        // Issue #202: once the newest post is loaded (hasMore = false), the thread ends
        // with a one-time "all caught up" marker.
        val posts = listOf(Post(id = 1L, bodyHtml = "<p>one</p>", user = RavelryUser(username = "a")))
        compose.setContent {
            TopicDetailScreen(
                topic = topic.copy(postCount = 1),
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = false),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.onNode(hasScrollAction())
            .performScrollToNode(hasText("You're all caught up"))
        compose.onNodeWithText("You're all caught up").assertIsDisplayed()
    }

    @Test
    fun `hides the all-caught-up marker while more pages remain`() {
        // With further pages to load, the end marker must not show yet (issue #202).
        val posts = listOf(Post(id = 1L, bodyHtml = "<p>one</p>", user = RavelryUser(username = "a")))
        compose.setContent {
            TopicDetailScreen(
                topic = topic.copy(postCount = 25),
                postsState = TopicDetailState.Loaded(posts = posts, hasMore = true),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.onNodeWithText("You're all caught up").assertDoesNotExist()
    }
}
