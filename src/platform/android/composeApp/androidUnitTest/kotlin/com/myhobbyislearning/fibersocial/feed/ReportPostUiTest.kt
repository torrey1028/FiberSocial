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
import kotlin.test.assertNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReportPostUiTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val post = Post(id = 1L, bodyHtml = "<p>hello</p>", user = RavelryUser(username = "someone"))

    private val topic = FeedItem(
        id = 10L, groupId = 1L, groupName = "G", lastPostAt = null,
        author = RavelryUser(username = "someone"), title = "T",
        bodySummary = "", postCount = 1,
    )

    private val flagForm = FlagPostForm(
        postId = 1L,
        authenticityToken = "tok",
        reasons = listOf(FlagReason("off_topic", "Off topic"), FlagReason("spam", "Spam")),
        supportsEscalate = true,
    )

    @Test
    fun `report post menu item is offered on every post`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
            )
        }
        compose.onNodeWithContentDescription("More post options").performClick()
        compose.onNodeWithText("Report post").assertIsDisplayed()
    }

    @Test
    fun `tapping report post opens the report dialog for that post`() {
        var reported: Post? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                onReportPost = { reported = it },
            )
        }
        compose.onNodeWithContentDescription("More post options").performClick()
        compose.onNodeWithText("Report post").performClick()
        compose.runOnIdle { assertEquals(1L, reported?.id) }
    }

    @Test
    fun `loading state shows a spinner while the flag form is fetched`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                reportState = ReportState.LoadingForm(post),
            )
        }
        compose.onNodeWithText("Report post").assertIsDisplayed()
        compose.onNodeWithText("Report to app developer instead").assertIsDisplayed()
    }

    @Test
    fun `ready state lists the form's reasons and an escalate toggle`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                reportState = ReportState.Ready(post, flagForm),
            )
        }
        compose.onNodeWithText("Off topic").assertIsDisplayed()
        compose.onNodeWithText("Spam").assertIsDisplayed()
        compose.onNodeWithText("Escalate to Ravelry staff").assertIsDisplayed()
    }

    @Test
    fun `confirming a reason submits it`() {
        var submitted: Pair<String, Boolean>? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                reportState = ReportState.Ready(post, flagForm),
                onSubmitReport = { reasonId, escalate -> submitted = reasonId to escalate },
            )
        }
        // The first reason is preselected by default.
        compose.onNodeWithText("Report").performClick()
        compose.runOnIdle { assertEquals("off_topic" to false, submitted) }
    }

    @Test
    fun `picking a different reason and toggling escalate submits both`() {
        var submitted: Pair<String, Boolean>? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                reportState = ReportState.Ready(post, flagForm),
                onSubmitReport = { reasonId, escalate -> submitted = reasonId to escalate },
            )
        }
        compose.onNodeWithText("Spam").performClick()
        compose.onNodeWithText("Escalate to Ravelry staff").performClick()
        compose.onNodeWithText("Report").performClick()
        compose.runOnIdle { assertEquals("spam" to true, submitted) }
    }

    @Test
    fun `cancel dismisses the dialog without submitting`() {
        var dismissed = false
        var submitted: Pair<String, Boolean>? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                reportState = ReportState.Ready(post, flagForm),
                onSubmitReport = { reasonId, escalate -> submitted = reasonId to escalate },
                onDismissReport = { dismissed = true },
            )
        }
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {
            assertEquals(true, dismissed)
            assertNull(submitted)
        }
    }

    @Test
    fun `load error still offers the report-to-developer fallback`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                reportState = ReportState.LoadError(post, "Couldn't load the report form"),
            )
        }
        compose.onNodeWithText("Couldn't load the report form").assertIsDisplayed()
        compose.onNodeWithText("Report to app developer instead").assertIsDisplayed()
    }

    @Test
    fun `report to developer invokes the callback with the reported post`() {
        var developerReported: Post? = null
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                reportState = ReportState.Ready(post, flagForm),
                onReportToDeveloper = { developerReported = it },
            )
        }
        compose.onNodeWithText("Report to app developer instead").performClick()
        compose.runOnIdle { assertEquals(1L, developerReported?.id) }
    }

    @Test
    fun `sent state shows a confirmation instead of the reason picker`() {
        var acknowledged = false
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
                reportState = ReportState.Sent,
                onReportSent = { acknowledged = true },
            )
        }
        compose.onNodeWithText("Report sent").assertIsDisplayed()
        compose.onNodeWithText("OK").performClick()
        compose.runOnIdle { assertEquals(true, acknowledged) }
    }

    @Test
    fun `idle state renders no report dialog`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(listOf(post)),
                onBack = {},
                onVote = { _, _ -> },
            )
        }
        // The overflow menu's "Report post" item only exists once tapped open (covered by
        // the first test above); with no dialog state, none of the dialog's own content
        // should be present at all.
        assertEquals(
            0,
            compose.onAllNodes(androidx.compose.ui.test.hasText("Report to app developer instead"))
                .fetchSemanticsNodes()
                .size,
        )
    }
}
