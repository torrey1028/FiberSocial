package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
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

    private val topic = FeedItem(
        id = 10L, groupId = 1L, groupName = "G", lastPostAt = null,
        author = RavelryUser(username = "someone"), title = "T",
        bodySummary = "", postCount = 2,
    )

    private fun editIconCount(): Int =
        compose.onAllNodes(androidx.compose.ui.test.hasContentDescription("Edit post"))
            .fetchSemanticsNodes().size

    private fun renderThread(post: Post) {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                currentUsername = "me",
            )
        }
    }

    @Test
    fun `edit icon shows on own post Ravelry marks editable`() {
        renderThread(ownPost.copy(editable = true))
        compose.onNodeWithContentDescription("Edit post").assertIsDisplayed()
    }

    @Test
    fun `edit icon shows on own post with unknown (null) editable`() {
        // A just-created reply comes back editable=null; treat it optimistically as editable.
        renderThread(ownPost.copy(editable = null))
        compose.onNodeWithContentDescription("Edit post").assertIsDisplayed()
    }

    @Test
    fun `edit icon hidden when Ravelry explicitly says not editable`() {
        renderThread(ownPost.copy(editable = false))
        compose.runOnIdle { assertEquals(0, editIconCount()) }
    }

    @Test
    fun `edit icon hidden on someone else's post even if editable`() {
        renderThread(otherPost.copy(editable = true))
        compose.runOnIdle { assertEquals(0, editIconCount()) }
    }

    @Test
    fun `tapping edit opens the docked edit bar prefilled with the post body`() {
        renderThread(ownPost.copy(editable = true, body = "original text"))
        // Before: the reply composer is shown, not the edit bar.
        compose.onNodeWithText("Write a reply…").assertIsDisplayed()

        compose.onNodeWithContentDescription("Edit post").performClick()

        // After: the bottom bar becomes the edit bar with save/cancel controls and the
        // body pre-filled. The post body also renders "original text" in the thread now,
        // so match the editable field specifically.
        compose.onNodeWithContentDescription("Save edit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Cancel edit").assertIsDisplayed()
        compose.onNode(hasText("original text") and hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun `a pull-to-refresh mid-edit does not close the edit bar`() {
        // Regression: editingPost used to resolve against the raw postsState instead of
        // the pull-to-refresh fallback (displayState), so postsState briefly flipping to
        // Loading during a refresh made the open post vanish from editingPost's lookup,
        // silently swapping the edit bar back out for the reply composer mid-edit.
        var postsState by mutableStateOf<TopicDetailState>(
            TopicDetailState.Loaded(listOf(ownPost.copy(editable = true, body = "original text"))),
        )
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = postsState,
                onBack = {},
                onVote = { _, _ -> },
                currentUsername = "me",
            )
        }
        compose.onNodeWithContentDescription("Edit post").performClick()
        compose.onNodeWithContentDescription("Save edit").assertIsDisplayed()

        // Pull-to-refresh: postsState briefly reports Loading again.
        postsState = TopicDetailState.Loading

        compose.onNodeWithContentDescription("Save edit").assertIsDisplayed()
        compose.onNode(hasText("original text") and hasSetTextAction()).assertIsDisplayed()
    }

    @Test
    fun `cancel closes the edit bar back to the reply composer`() {
        renderThread(ownPost.copy(editable = true, body = "original text"))
        compose.onNodeWithContentDescription("Edit post").performClick()
        compose.onNodeWithContentDescription("Cancel edit").performClick()
        compose.onNodeWithText("Write a reply…").assertIsDisplayed()
    }

    @Test
    fun `save edit invokes onEditPost with the edited text`() {
        var edited: Pair<Long, String>? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(ownPost.copy(editable = true, body = "original text"))),
                onBack = {},
                onVote = { _, _ -> },
                currentUsername = "me",
                onEditPost = { post, body -> edited = post.id to body },
            )
        }
        compose.onNodeWithContentDescription("Edit post").performClick()
        // The edit bar replaces the reply composer, so it holds the only text field.
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextClearance()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("edited text")
        compose.onNodeWithContentDescription("Save edit").performClick()
        compose.runOnIdle { assertEquals(1L to "edited text", edited) }
    }

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
