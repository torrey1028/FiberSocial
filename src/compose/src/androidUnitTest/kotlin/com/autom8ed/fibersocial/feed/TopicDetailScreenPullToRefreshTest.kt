package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
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
    fun `shows the jump-to-last-read button when the topic has an unread post`() {
        // Issue #185: firstUnreadPostNumber drives an ExtendedFloatingActionButton that
        // scrolls the thread to the first unread post.
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

        compose.onNodeWithText("Jump to last read").assertIsDisplayed()
    }
}
