package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** Per-topic reply mute from the topic's overflow menu (issue #338). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TopicDetailMuteTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun topic(unread: Int = 0) = FeedItem(
        id = 1L,
        groupId = 2L,
        groupName = "Test Group",
        lastPostAt = null,
        author = RavelryUser(username = "tester"),
        title = "A topic",
        bodySummary = "",
        postCount = 10,
        unreadCount = unread,
        firstUnreadPostNumber = null,
    )

    private val posts = listOf(Post(id = 1L, bodyHtml = "<p>one</p>", user = RavelryUser(username = "a")))

    @Test
    fun `an unmuted topic offers Mute and tapping it toggles`() {
        var toggles = 0
        compose.setContent {
            TopicDetailScreen(
                topic = topic(),
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
                isMuted = false,
                onToggleMute = { toggles++ },
            )
        }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Mute notifications").performClick()
        compose.runOnIdle { assertEquals(1, toggles) }
    }

    @Test
    fun `a muted topic offers Unmute instead`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic(),
                postsState = TopicDetailState.Loaded(posts = posts),
                onBack = {},
                onVote = { _, _ -> },
                isMuted = true,
                onToggleMute = {},
            )
        }

        compose.onNodeWithContentDescription("More options").performClick()
        compose.onNodeWithText("Unmute notifications").assertIsDisplayed()
        compose.onNodeWithText("Mute notifications").assertDoesNotExist()
    }
}
