package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** "Mark all as read" from the topic's overflow menu (issue #227). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TopicDetailMarkAllReadTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun topic(unread: Int, postCount: Int = 10) = FeedItem(
        id = 1L,
        groupId = 2L,
        groupName = "Test Group",
        lastPostAt = null,
        author = RavelryUser(username = "tester"),
        title = "A topic",
        bodySummary = "",
        postCount = postCount,
        unreadCount = unread,
        firstUnreadPostNumber = if (unread > 0) postCount - unread + 1 else null,
    )

    private val posts = listOf(Post(id = 1L, bodyHtml = "<p>one</p>", user = RavelryUser(username = "a")))

    @Test
    fun `mark all as read reports the topic's full post count`() {
        var markedTo = -1
        compose.setContent {
            TopicDetailScreen(
                topic = topic(unread = 4, postCount = 10),
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
                onMarkRead = { markedTo = it },
            )
        }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Mark all as read").performClick()
        compose.runOnIdle { assertEquals(10, markedTo) }
    }

    @Test
    fun `the menu is hidden once everything is read`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic(unread = 0),
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.onNodeWithContentDescription("More options").assertDoesNotExist()
    }
}
