package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TopicCardTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun item(
        bodyPreview: String = "",
        bodySummary: String = "",
        bodySummaryHtml: String = "",
        latestReplyHtml: String? = null,
        latestReplyPreview: String? = null,
        openingPostHtml: String = "",
    ) = FeedItem(
        id = 1L,
        groupId = 10L,
        groupName = "KAL Hub",
        lastPostAt = null,
        author = RavelryUser(username = "yarnie"),
        title = "Show us your WIPs",
        bodyPreview = bodyPreview,
        bodySummary = bodySummary,
        bodySummaryHtml = bodySummaryHtml,
        replyCount = 3,
        latestReplyAuthor = latestReplyHtml?.let { RavelryUser(username = "replier") },
        latestReplyPreview = latestReplyPreview,
        latestReplyHtml = latestReplyHtml,
        openingPostHtml = openingPostHtml,
    )

    @Test
    fun `renders the summary's formatting instead of leaking markup`() {
        compose.setContent {
            TopicCard(
                item = item(bodySummary = "Cast on **all** the *stitches*", bodyPreview = "unused"),
                onClick = {},
            )
        }
        // Styled, not stripped: the text appears without any markdown markers.
        compose.onNodeWithText("Cast on all the stitches").assertIsDisplayed()
    }

    @Test
    fun `prefers the latest reply's rendered body`() {
        compose.setContent {
            TopicCard(
                item = item(
                    bodySummaryHtml = "<p>opening post</p>",
                    latestReplyHtml = "<p><strong>fresh reply</strong> here</p>",
                    latestReplyPreview = "fresh reply here",
                ),
                onClick = {},
            )
        }
        compose.onNodeWithText("fresh reply here").assertIsDisplayed()
        compose.onNodeWithText("opening post").assertDoesNotExist()
    }

    @Test
    fun `falls back to the stripped preview when the document flattens to nothing`() {
        compose.setContent {
            TopicCard(
                item = item(
                    bodySummaryHtml = """<p><img src="https://img/p.jpg" alt=""/></p>""",
                    bodyPreview = "legacy preview text",
                ),
                onClick = {},
            )
        }
        compose.onNodeWithText("legacy preview text").assertIsDisplayed()
    }

    @Test
    fun `shows no preview row for genuinely empty content`() {
        compose.setContent {
            TopicCard(item = item(), onClick = {})
        }
        compose.onNodeWithText("Show us your WIPs").assertIsDisplayed()
    }

    @Test
    fun `previews the opening post body over a stripped summary`() {
        compose.setContent {
            TopicCard(
                item = item(
                    bodySummaryHtml = "<p>stripped summary</p>",
                    openingPostHtml = "<p>Test <em>italic</em> topic</p>",
                ),
                onClick = {},
            )
        }
        compose.onNodeWithText("Test italic topic").assertIsDisplayed()
        compose.onNodeWithText("stripped summary").assertDoesNotExist()
    }

    @Test
    fun `shows a thumbnail for a post with a photo`() {
        compose.setContent {
            TopicCard(
                item = item(
                    openingPostHtml = """<p>my socks</p><p><img src="https://img.example/socks.jpg" alt=""/></p>""",
                ),
                onClick = {},
            )
        }
        compose.onNodeWithText("my socks").assertIsDisplayed()
        compose.onNodeWithContentDescription("Preview photo").assertIsDisplayed()
    }

    @Test
    fun `shows the thumbnail alone for an image-only post`() {
        compose.setContent {
            TopicCard(
                item = item(
                    openingPostHtml = """<p><img src="https://img.example/socks.jpg" alt=""/></p>""",
                ),
                onClick = {},
            )
        }
        compose.onNodeWithContentDescription("Preview photo").assertIsDisplayed()
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
