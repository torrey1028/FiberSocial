package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/**
 * The forum-style card (issue #185): title, "Started by @starter", the author-written
 * summary rendered in full (omitted when absent), the post count, and an unread badge.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TopicCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun item(
        title: String = "Show us your WIPs",
        author: String = "yarnie",
        bodySummary: String = "",
        bodySummaryHtml: String = "",
        postCount: Int = 3,
        unreadCount: Int = 0,
        sticky: Boolean = false,
        lastPostAt: String? = null,
        createdAt: String? = null,
    ) = FeedItem(
        id = 1L,
        groupId = 10L,
        groupName = "KAL Hub",
        lastPostAt = lastPostAt,
        author = RavelryUser(username = author),
        title = title,
        bodySummary = bodySummary,
        bodySummaryHtml = bodySummaryHtml,
        postCount = postCount,
        unreadCount = unreadCount,
        sticky = sticky,
        createdAt = createdAt,
    )

    @Test
    fun `shows the title and who started the topic`() {
        compose.setContent { TopicCard(item = item(author = "yarnie"), onClick = {}) }
        compose.onNodeWithText("Show us your WIPs").assertIsDisplayed()
        compose.onNodeWithText("Started by @yarnie").assertIsDisplayed()
    }

    @Test
    fun `shows when the topic was started alongside who started it`() {
        // Issue #242: the card should surface the topic's original start time, not just
        // its last-reply time.
        compose.setContent {
            TopicCard(item = item(author = "yarnie", createdAt = "2020/01/01 00:00:00 +0000"), onClick = {})
        }
        compose.onNodeWithText("ago by @yarnie", substring = true).assertIsDisplayed()
    }

    @Test
    fun `shows only the plain started-by line when createdAt is unavailable`() {
        compose.setContent { TopicCard(item = item(author = "yarnie", createdAt = null), onClick = {}) }
        compose.onNodeWithText("Started by @yarnie").assertIsDisplayed()
    }

    @Test
    fun `labels the last-post time distinctly from the started time`() {
        // Issue #242: once "Started ... ago" appears too, the bottom-row time must be
        // labeled so it isn't mistaken for the start time.
        compose.setContent {
            TopicCard(item = item(lastPostAt = "2020/01/01 00:00:00 +0000"), onClick = {})
        }
        compose.onNodeWithText("Last post", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a single-post topic's last-post time falls back to when it was started`() {
        // A topic with just its opening post has no reply yet, so its own start time IS
        // the last post's time — verified with lastPostAt null (Ravelry's replied_at may
        // simply be absent pre-reply).
        compose.setContent {
            TopicCard(
                item = item(postCount = 1, lastPostAt = null, createdAt = "2020/01/01 00:00:00 +0000"),
                onClick = {},
            )
        }
        compose.onNodeWithText("Last post", substring = true).assertIsDisplayed()
    }

    @Test
    fun `a single-post topic uses createdAt even if lastPostAt is also populated`() {
        // Whatever Ravelry's replied_at happens to report for a topic nobody has replied
        // to yet, a single-post topic's last post is unconditionally its start time.
        compose.setContent {
            TopicCard(
                item = item(
                    postCount = 1,
                    lastPostAt = "2020/06/01 00:00:00 +0000",
                    createdAt = "2020/01/01 00:00:00 +0000",
                ),
                onClick = {},
            )
        }
        compose.onNodeWithText("Last post", substring = true).assertIsDisplayed()
    }

    @Test
    fun `renders the summary's formatting instead of leaking markup`() {
        compose.setContent {
            TopicCard(item = item(bodySummary = "Cast on **all** the *stitches*"), onClick = {})
        }
        // Styled, not stripped: the text appears without any markdown markers.
        compose.onNodeWithText("Cast on all the stitches").assertIsDisplayed()
    }

    @Test
    fun `prefers ravelry's html rendering of the summary`() {
        // The rendering resolves the dangling ** the raw source drops (issue #104).
        compose.setContent {
            TopicCard(
                item = item(
                    bodySummary = "**Please use this thread",
                    bodySummaryHtml = "<p><strong>Please use this thread</strong></p>",
                ),
                onClick = {},
            )
        }
        compose.onNodeWithText("Please use this thread").assertIsDisplayed()
    }

    @Test
    fun `shows only the title and meta for a topic with no summary`() {
        compose.setContent { TopicCard(item = item(bodySummary = "", bodySummaryHtml = ""), onClick = {}) }
        compose.onNodeWithText("Show us your WIPs").assertIsDisplayed()
        compose.onNodeWithText("posts", substring = true).assertIsDisplayed()
    }

    @Test
    fun `shows the post count`() {
        compose.setContent { TopicCard(item = item(postCount = 5), onClick = {}) }
        compose.onNodeWithText("5 posts", substring = true).assertIsDisplayed()
    }

    @Test
    fun `shows a singular label for a single-post topic`() {
        compose.setContent { TopicCard(item = item(postCount = 1), onClick = {}) }
        compose.onNodeWithText("1 post", substring = true).assertIsDisplayed()
    }

    @Test
    fun `shows the unread badge when there are unread posts`() {
        compose.setContent { TopicCard(item = item(postCount = 10, unreadCount = 4), onClick = {}) }
        compose.onNodeWithText("4 new", substring = true).assertIsDisplayed()
    }

    @Test
    fun `hides the unread badge when nothing is unread`() {
        compose.setContent { TopicCard(item = item(unreadCount = 0), onClick = {}) }
        compose.onNodeWithText("new", substring = true).assertDoesNotExist()
    }

    @Test
    fun `marks a pinned topic`() {
        compose.setContent { TopicCard(item = item(sticky = true), onClick = {}) }
        compose.onNodeWithText("Pinned", substring = true).assertIsDisplayed()
    }

    @Test
    fun `tapping the summary opens the topic`() {
        // Issue #216: the summary must not swallow the tap — the whole card is one tap
        // target, so tapping the summary text fires onClick just like tapping the title.
        var clicks = 0
        compose.setContent {
            TopicCard(item = item(bodySummary = "Cast on all the stitches"), onClick = { clicks++ })
        }
        compose.onNodeWithText("Cast on all the stitches").performClick()
        assertEquals(1, clicks)
    }

    @Test
    fun `link text renders without its url`() {
        compose.setContent {
            TopicCard(
                item = item(bodySummary = "see [this pattern](https://www.ravelry.com/patterns/x)"),
                onClick = {},
            )
        }
        compose.onNodeWithText("see this pattern").assertIsDisplayed()
    }
}
