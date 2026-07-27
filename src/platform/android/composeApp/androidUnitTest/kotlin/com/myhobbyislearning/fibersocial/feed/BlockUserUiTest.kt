package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
import kotlin.test.assertNull

/**
 * The "Block user" half of the per-post overflow menu (issue #410 — Apple Guideline 1.2's
 * "block abusive users" mechanism), mirroring [ReportPostUiTest]'s shape for the sibling
 * "Report post" action the same menu offers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BlockUserUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val post = Post(id = 1L, bodyHtml = "<p>hello</p>", user = RavelryUser(username = "someone"))

    private val topic = FeedItem(
        id = 10L, groupId = 1L, groupName = "G", lastPostAt = null,
        author = RavelryUser(username = "someone"), title = "T",
        bodySummary = "", postCount = 1,
    )

    @Test
    fun `block user menu item is offered when the post has a known author`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
            )
        }
        compose.onNodeWithContentDescription("More post options").performClick()
        compose.onNodeWithText("Block user").assertIsDisplayed()
    }

    @Test
    fun `block user menu item is absent when the post has no known author`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post.copy(user = null))),
                onBack = {},
                onVote = { _, _ -> },
            )
        }
        compose.onNodeWithContentDescription("More post options").performClick()
        compose.onNodeWithText("Block user").assertDoesNotExist()
    }

    @Test
    fun `tapping block user opens a confirmation dialog naming the author`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
            )
        }
        compose.onNodeWithContentDescription("More post options").performClick()
        compose.onNodeWithText("Block user").performClick()
        compose.onNodeWithText("Block @someone?").assertIsDisplayed()
    }

    @Test
    fun `confirming without the checkbox blocks without asking to notify the developer`() {
        var blocked: Pair<Post, Boolean>? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                onBlockUser = { p, notify -> blocked = p to notify },
            )
        }
        compose.onNodeWithContentDescription("More post options").performClick()
        compose.onNodeWithText("Block user").performClick()
        compose.onNodeWithText("Block").performClick()
        compose.runOnIdle { assertEquals(1L to false, blocked?.first?.id to blocked?.second) }
    }

    @Test
    fun `checking notify the developer then confirming passes true`() {
        var blocked: Pair<Post, Boolean>? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                onBlockUser = { p, notify -> blocked = p to notify },
            )
        }
        compose.onNodeWithContentDescription("More post options").performClick()
        compose.onNodeWithText("Block user").performClick()
        compose.onNodeWithText("Also notify the developer").performClick()
        compose.onNodeWithText("Block").performClick()
        compose.runOnIdle { assertEquals(1L to true, blocked?.first?.id to blocked?.second) }
    }

    @Test
    fun `cancel dismisses the dialog without blocking`() {
        var blocked: Post? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                onBlockUser = { p, _ -> blocked = p },
            )
        }
        compose.onNodeWithContentDescription("More post options").performClick()
        compose.onNodeWithText("Block user").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Block @someone?").assertDoesNotExist()
        compose.runOnIdle { assertNull(blocked) }
    }

    @Test
    fun `a post from a blocked author is fully hidden from the reply list`() {
        val fromBlocked = Post(id = 1L, bodyHtml = "<p>hi</p>", user = RavelryUser(username = "blocked-user"))
        val fromOther = Post(id = 2L, bodyHtml = "<p>hey</p>", user = RavelryUser(username = "someone-else"))
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(fromBlocked, fromOther)),
                onBack = {},
                onVote = { _, _ -> },
                blockedUsernames = setOf("blocked-user"),
            )
        }
        compose.onAllNodesWithText("hi").assertCountEquals(0)
        compose.onNodeWithText("hey").assertIsDisplayed()
    }
}
