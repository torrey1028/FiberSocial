package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DeletePostUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val ownPost = Post(id = 1L, bodyHtml = "<p>mine</p>", user = RavelryUser(username = "me"))
    private val otherPost = Post(id = 2L, bodyHtml = "<p>theirs</p>", user = RavelryUser(username = "someone"))

    private val topic = FeedItem.DiscussionTopic(
        id = 10L, groupId = 1L, groupName = "G", lastPostAt = null,
        author = RavelryUser(username = "someone"), title = "T",
        bodyPreview = "", bodySummary = "", replyCount = 2,
    )

    @Test
    fun `delete icon shows only on own posts`() {
        compose.setContent {
            ReplyItem(post = ownPost, onVote = {}, canDelete = true)
            }
        compose.onNodeWithContentDescription("Delete post").assertIsDisplayed()
    }

    @Test
    fun `delete icon absent when not own post`() {
        compose.setContent {
            ReplyItem(post = otherPost, onVote = {}, canDelete = false)
        }
        assertEquals(
            0,
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Delete post"))
                .fetchSemanticsNodes().size,
        )
    }

    @Test
    fun `confirmation dialog guards deletion and confirms through`() {
        var deleted: Post? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(ownPost, otherPost)),
                onBack = {},
                onVote = { _, _ -> },
                currentUsername = "me",
                onDeletePost = { deleted = it },
            )
        }
        compose.onNodeWithContentDescription("Delete post").performClick()
        compose.onNodeWithText("Delete this post?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(null, deleted) }
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(1L, deleted?.id) }
    }

    @Test
    fun `cancel dismisses without deleting`() {
        var deleted: Post? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(ownPost)),
                onBack = {},
                onVote = { _, _ -> },
                currentUsername = "me",
                onDeletePost = { deleted = it },
            )
        }
        compose.onNodeWithContentDescription("Delete post").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(null, deleted) }
    }
}
