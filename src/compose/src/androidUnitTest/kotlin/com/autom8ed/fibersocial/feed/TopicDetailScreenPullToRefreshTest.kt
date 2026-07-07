package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    fun `hides the jump-to-last-read button when the whole topic already fits on screen`() {
        // Issue #255: a short topic (here, 3 posts) can render entirely within the
        // viewport, including its one unread post — there's nothing left to "jump" to,
        // even though the thread technically has an unread post.
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

        compose.onNodeWithText("Jump to last read").assertDoesNotExist()
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
