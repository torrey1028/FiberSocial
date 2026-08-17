package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class GroupPreviewScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val lace = Group(
        id = 1L,
        name = "Lace Knitters",
        permalink = "lace-knitters",
        forumId = 11L,
        shortDescription = "For lovers of lace",
    )

    private val topic = FeedItem(
        id = 100L,
        groupId = 1L,
        groupName = "Lace Knitters",
        lastPostAt = null,
        author = RavelryUser(username = "knitwit"),
        title = "Blocking wires?",
        bodySummary = "What do people use",
        postCount = 3,
    )

    private fun loaded(items: List<FeedItem> = listOf(topic), hasMore: Boolean = false) =
        GroupPreviewState.Loaded(lace, items, page = 1, hasMore = hasMore)

    @Test
    fun `shows the group name and its topics`() {
        // The point of the screen: read the actual conversations before deciding to join.
        compose.setContent { GroupPreviewScreen(state = loaded(), onBack = {}) }
        compose.onNodeWithText("Lace Knitters").assertIsDisplayed()
        compose.onNodeWithText("Blocking wires?").assertIsDisplayed()
    }

    @Test
    fun `offers a join button for a group not yet joined`() {
        var joined: String? = null
        compose.setContent {
            GroupPreviewScreen(state = loaded(), onBack = {}, onJoin = { joined = it.permalink })
        }
        compose.onNodeWithTag("PreviewJoin").performClick()
        compose.runOnIdle { assertEquals("lace-knitters", joined) }
    }

    @Test
    fun `shows Joined instead of the button once joined`() {
        compose.setContent { GroupPreviewScreen(state = loaded(), onBack = {}, isJoined = true) }
        compose.onNodeWithText("Joined").assertIsDisplayed()
        compose.onNodeWithTag("PreviewJoin").assertDoesNotExist()
    }

    @Test
    fun `hides the join button while a join is in flight`() {
        compose.setContent { GroupPreviewScreen(state = loaded(), onBack = {}, isJoining = true) }
        compose.onNodeWithTag("PreviewJoin").assertDoesNotExist()
    }

    @Test
    fun `tapping a topic reports it so the host can open the detail`() {
        var opened: Long? = null
        compose.setContent {
            GroupPreviewScreen(state = loaded(), onBack = {}, onTopicClick = { opened = it.id })
        }
        compose.onNodeWithText("Blocking wires?").performClick()
        compose.runOnIdle { assertEquals(100L, opened) }
    }

    @Test
    fun `an empty group says so rather than looking broken`() {
        compose.setContent { GroupPreviewScreen(state = loaded(items = emptyList()), onBack = {}) }
        compose.onNodeWithText("No topics in this group yet.").assertIsDisplayed()
    }

    @Test
    fun `an error names the group and offers a retry`() {
        var retries = 0
        compose.setContent {
            GroupPreviewScreen(
                state = GroupPreviewState.Error(lace, "Couldn't load Lace Knitters."),
                onBack = {},
                onRetry = { retries++ },
            )
        }
        compose.onNodeWithText("Couldn't load Lace Knitters.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `load more shows only while another page exists`() {
        var loads = 0
        compose.setContent {
            GroupPreviewScreen(state = loaded(hasMore = true), onBack = {}, onLoadMore = { loads++ })
        }
        compose.onNodeWithText("Load more").performClick()
        compose.runOnIdle { assertEquals(1, loads) }
    }

    @Test
    fun `the last page offers no load more`() {
        compose.setContent { GroupPreviewScreen(state = loaded(hasMore = false), onBack = {}) }
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test
    fun `the ravelry escape hatch reports the group`() {
        // The topic list can't show group rules, moderators or the member list; this is
        // the only remaining way to reach them.
        var opened: String? = null
        compose.setContent {
            GroupPreviewScreen(state = loaded(), onBack = {}, onOpenInBrowser = { opened = it.permalink })
        }
        compose.onNodeWithText("On Ravelry").performClick()
        compose.runOnIdle { assertEquals("lace-knitters", opened) }
    }

    @Test
    fun `back arrow and system back both invoke onBack`() {
        var backs = 0
        compose.setContent { GroupPreviewScreen(state = loaded(), onBack = { backs++ }) }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(2, backs) }
    }

    @Test
    fun `a hidden state renders nothing`() {
        compose.setContent { GroupPreviewScreen(state = GroupPreviewState.Hidden, onBack = {}) }
        compose.onNodeWithContentDescription("Back").assertDoesNotExist()
    }
}
