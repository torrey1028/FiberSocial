package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Covers the feed-refresh-after-reply wiring for issue #88: [TopicDetailRoute] should
 * trigger a feed refresh on back-navigation only when at least one reply was actually
 * sent during the visit, not on every plain browse-and-back.
 */
@RunWith(RobolectricTestRunner::class)
class TopicDetailRouteTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val topic = FeedItem(
        id = 1L,
        groupId = 2L,
        groupName = "Test Group",
        lastPostAt = null,
        author = RavelryUser(username = "tester"),
        title = "A topic",
        bodySummary = "",
        postCount = 0,
    )

    @Test
    fun `trackReplySent latches true once a reply is Sent`() {
        assertFalse(trackReplySent(repliedThisVisit = false, replyState = ReplyState.Idle))
        assertFalse(trackReplySent(repliedThisVisit = false, replyState = ReplyState.Sending))
        assertFalse(trackReplySent(repliedThisVisit = false, replyState = ReplyState.Error("boom")))
        assertTrue(trackReplySent(repliedThisVisit = false, replyState = ReplyState.Sent))
    }

    @Test
    fun `trackReplySent stays true after a later state reverts to Idle`() {
        // Mirrors acknowledgeReplySent() flipping Sent back to Idle once the UI reacts —
        // the visit should still be considered "replied" for the rest of the visit.
        assertTrue(trackReplySent(repliedThisVisit = true, replyState = ReplyState.Idle))
    }

    @Test
    fun `back after a successful reply triggers a feed refresh`() {
        var refreshCount = 0
        var backCount = 0
        var replyState by mutableStateOf<ReplyState>(ReplyState.Idle)

        compose.setContent {
            TopicDetailRoute(
                topic = topic,
                postsState = TopicDetailState.Loaded(emptyList()),
                replyState = replyState,
                onVote = { _, _ -> },
                onSendReply = {},
                onReplySent = { replyState = ReplyState.Idle },
                onBack = { backCount++ },
                onRefreshFeed = { refreshCount++ },
                onRefresh = {},
            )
        }

        compose.runOnIdle { replyState = ReplyState.Sent }
        // The composer acknowledges Sent and flips it back to Idle almost immediately —
        // the refresh decision must survive that flip.
        compose.waitForIdle()
        compose.runOnIdle { replyState = ReplyState.Idle }
        compose.waitForIdle()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.runOnIdle {
            assertEquals(1, refreshCount)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun `back without ever sending a reply does not trigger a feed refresh`() {
        var refreshCount = 0
        var backCount = 0

        compose.setContent {
            TopicDetailRoute(
                topic = topic,
                postsState = TopicDetailState.Loaded(emptyList()),
                replyState = ReplyState.Idle,
                onVote = { _, _ -> },
                onSendReply = {},
                onReplySent = {},
                onBack = { backCount++ },
                onRefreshFeed = { refreshCount++ },
                onRefresh = {},
            )
        }

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.runOnIdle {
            assertEquals(0, refreshCount)
            assertEquals(1, backCount)
        }
    }

    @Test
    fun `back after a failed reply attempt does not trigger a feed refresh`() {
        var refreshCount = 0
        var backCount = 0
        var replyState by mutableStateOf<ReplyState>(ReplyState.Idle)

        compose.setContent {
            TopicDetailRoute(
                topic = topic,
                postsState = TopicDetailState.Loaded(emptyList()),
                replyState = replyState,
                onVote = { _, _ -> },
                onSendReply = {},
                onReplySent = {},
                onBack = { backCount++ },
                onRefreshFeed = { refreshCount++ },
                onRefresh = {},
            )
        }

        compose.runOnIdle { replyState = ReplyState.Sending }
        compose.waitForIdle()
        compose.runOnIdle { replyState = ReplyState.Error("boom") }
        compose.waitForIdle()

        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }

        compose.runOnIdle {
            assertEquals(0, refreshCount)
            assertEquals(1, backCount)
        }
    }
}
