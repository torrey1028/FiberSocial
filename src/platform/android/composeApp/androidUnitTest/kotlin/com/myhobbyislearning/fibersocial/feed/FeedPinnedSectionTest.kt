package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The collapsible pinned-topics section in [FeedList]: sticky topics render under a
 * "pinned topics" header that folds the whole section closed and back open, while
 * non-sticky topics stay visible either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedPinnedSectionTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun item(id: Long, title: String, sticky: Boolean) = FeedItem(
        id = id,
        groupId = 10L,
        groupName = "KAL Hub",
        lastPostAt = null,
        author = RavelryUser(username = "yarnie"),
        title = title,
        bodySummary = "",
        postCount = 3,
        sticky = sticky,
    )

    private fun setFeedList(items: List<FeedItem>) {
        compose.setContent {
            var collapsed by remember { mutableStateOf(false) }
            FeedList(
                items = items,
                hasMore = false,
                loadingMore = false,
                onLoadMore = {},
                pinnedCollapsed = collapsed,
                onTogglePinnedCollapsed = { collapsed = !collapsed },
                onTopicClick = {},
            )
        }
    }

    @Test
    fun `shows a counted header above pinned topics, expanded by default`() {
        setFeedList(
            listOf(
                item(id = 1, title = "Group rules", sticky = true),
                item(id = 2, title = "KAL sign-ups", sticky = true),
                item(id = 3, title = "Show us your WIPs", sticky = false),
            ),
        )

        compose.onNodeWithText("📌 2 pinned topics").assertIsDisplayed()
        compose.onNodeWithText("Group rules").assertIsDisplayed()
        compose.onNodeWithText("KAL sign-ups").assertIsDisplayed()
        compose.onNodeWithText("Show us your WIPs").assertIsDisplayed()
    }

    @Test
    fun `uses singular wording for a single pinned topic`() {
        setFeedList(
            listOf(
                item(id = 1, title = "Group rules", sticky = true),
                item(id = 2, title = "Show us your WIPs", sticky = false),
            ),
        )

        compose.onNodeWithText("📌 1 pinned topic").assertIsDisplayed()
    }

    @Test
    fun `shows no header when the feed has no pinned topics`() {
        setFeedList(listOf(item(id = 1, title = "Show us your WIPs", sticky = false)))

        compose.onNodeWithText("pinned topic", substring = true).assertDoesNotExist()
    }

    @Test
    fun `tapping the header collapses pinned topics but keeps the rest`() {
        setFeedList(
            listOf(
                item(id = 1, title = "Group rules", sticky = true),
                item(id = 2, title = "Show us your WIPs", sticky = false),
            ),
        )

        compose.onNodeWithText("📌 1 pinned topic").performClick()

        compose.onNodeWithText("Group rules").assertDoesNotExist()
        compose.onNodeWithText("Show us your WIPs").assertIsDisplayed()
        // The chevron's description flips to advertise re-expansion.
        compose.onNodeWithContentDescription("Expand pinned topics").assertIsDisplayed()
    }

    @Test
    fun `tapping a collapsed header expands the pinned topics again`() {
        setFeedList(
            listOf(
                item(id = 1, title = "Group rules", sticky = true),
                item(id = 2, title = "Show us your WIPs", sticky = false),
            ),
        )

        compose.onNodeWithText("📌 1 pinned topic").performClick()
        compose.onNodeWithText("📌 1 pinned topic").performClick()

        compose.onNodeWithText("Group rules").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse pinned topics").assertIsDisplayed()
    }

    @Test
    fun `a sticky topic sorted below a regular one still lands in the pinned section`() {
        // FeedRepository sorts sticky-first, but the section must not depend on that:
        // partitioning (not prefix-splitting) keeps a stray sticky item in the fold.
        setFeedList(
            listOf(
                item(id = 1, title = "Show us your WIPs", sticky = false),
                item(id = 2, title = "Group rules", sticky = true),
            ),
        )

        compose.onNodeWithText("📌 1 pinned topic").performClick()

        compose.onNodeWithText("Group rules").assertDoesNotExist()
        compose.onNodeWithText("Show us your WIPs").assertIsDisplayed()
    }
}
