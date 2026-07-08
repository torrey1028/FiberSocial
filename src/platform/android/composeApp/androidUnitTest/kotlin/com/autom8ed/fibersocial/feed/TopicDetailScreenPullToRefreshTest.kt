package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
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
    fun `shows the jump-to-last-read button when the unread post is off screen`() {
        // Issue #185: firstUnreadPostNumber drives an ExtendedFloatingActionButton that
        // scrolls the thread to the first unread post. Enough posts that the target is
        // genuinely below the fold, unlike issue #255's short-topic case below.
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

        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
    }

    @Test
    fun `shows the jump-to-last-read button for a long unread topic even though post 1 is unread`() {
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

        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
    }

    @Test
    fun `hides the jump-to-last-read button when the whole topic already fits on screen`() {
        // Issue #255: a short topic (here, 3 posts) can render entirely within the
        // viewport, including its one unread post — there's nothing left to "jump" to,
        // even though the thread technically has an unread post. Scrolls to the last
        // post explicitly (mirroring the "all caught up" test below) since Robolectric's
        // test viewport doesn't necessarily fit all 3 posts on the very first layout
        // pass the way a real device screen does.
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
        compose.onNodeWithText("Jump to last read").assertDoesNotExist()
    }

    @Test
    fun `hides the jump button once scrolled past the unread target, well before the thread's true end`() {
        // On-device review: a long-running historical thread (hundreds of posts over
        // months) kept the button around — and visually overlapping content — long after
        // the user had scrolled past the actual unread post, because visibility was
        // (wrongly) gated on the thread's literal last post instead of the real target.
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
        compose.onNodeWithText("Jump to last read").assertDoesNotExist()
    }

    @Test
    fun `shows the jump button for a fully-read topic that isn't fully visible, targeting the end`() {
        // On-device review of #255/#256 asked for the same button (not a distinct one)
        // to still offer a way to skip to the bottom of a topic with nothing unread, as
        // long as the thread isn't already fully on screen — visually and behaviorally
        // identical to the unread case, just aimed at the last post instead.
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

        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
    }

    @Test
    fun `tapping the jump button on a fully-read topic scrolls to the last post`() {
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
