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

    private fun item(id: Long, title: String, sticky: Boolean, unreadCount: Int = 0) = FeedItem(
        id = id,
        groupId = 10L,
        groupName = "KAL Hub",
        lastPostAt = null,
        author = RavelryUser(username = "yarnie"),
        title = title,
        bodySummary = "",
        postCount = 3,
        unreadCount = unreadCount,
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
    fun `a folded header sums the hidden topics' unread counts`() {
        setFeedList(
            listOf(
                item(id = 1, title = "Group rules", sticky = true, unreadCount = 2),
                item(id = 2, title = "KAL sign-ups", sticky = true, unreadCount = 5),
                item(id = 3, title = "Show us your WIPs", sticky = false, unreadCount = 9),
            ),
        )

        compose.onNodeWithText("📌 2 pinned topics").performClick()

        // 2 + 5 from the pinned topics only — the regular topic's 9 must not leak in.
        compose.onNodeWithText("7 new").assertIsDisplayed()
    }

    @Test
    fun `an expanded header shows no unread count`() {
        // Each visible card already wears its own badge; the header stays quiet.
        setFeedList(
            listOf(
                item(id = 1, title = "Group rules", sticky = true, unreadCount = 2),
                item(id = 2, title = "Show us your WIPs", sticky = false),
            ),
        )

        compose.onNodeWithText("2 new").assertIsDisplayed() // the card's own badge
        compose.onNodeWithText("📌 1 pinned topic").performClick()
        compose.onNodeWithText("2 new").assertIsDisplayed() // now the header's sum
        compose.onNodeWithText("📌 1 pinned topic").performClick()
        compose.onNodeWithText("2 new").assertIsDisplayed() // the card's badge again
    }

    @Test
    fun `a folded header with nothing unread shows no badge`() {
        setFeedList(
            listOf(
                item(id = 1, title = "Group rules", sticky = true),
                item(id = 2, title = "Show us your WIPs", sticky = false),
            ),
        )

        compose.onNodeWithText("📌 1 pinned topic").performClick()

        compose.onNodeWithText("new", substring = true).assertDoesNotExist()
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
