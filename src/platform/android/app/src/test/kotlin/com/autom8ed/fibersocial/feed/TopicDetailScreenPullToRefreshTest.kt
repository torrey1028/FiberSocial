package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/** Covers issue #69: pulling down on the reply thread calls the screen's onRefresh. */
@RunWith(RobolectricTestRunner::class)
class TopicDetailScreenPullToRefreshTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val topic = FeedItem(
        id = 1L,
        groupId = 2L,
        groupName = "Test Group",
        lastPostAt = null,
        author = RavelryUser(username = "tester"),
        title = "A topic",
        bodyPreview = "",
        bodySummary = "",
        replyCount = 0,
    )

    @Test
    fun `pulling down on a loaded thread invokes onRefresh`() {
        var refreshCount = 0
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(posts = emptyList()),
                onBack = {},
                onVote = { _, _ -> },
                onRefresh = { refreshCount++ },
            )
        }

        compose.onNode(hasScrollAction()).performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, refreshCount)
    }
}
