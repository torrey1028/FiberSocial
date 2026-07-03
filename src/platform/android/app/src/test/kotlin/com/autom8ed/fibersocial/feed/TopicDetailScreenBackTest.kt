package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertFalse

@RunWith(RobolectricTestRunner::class)
class TopicDetailScreenBackTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val topic = FeedItem.DiscussionTopic(
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
    fun `system back press invokes onBack instead of finishing the activity`() {
        var backCount = 0
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loading,
                onBack = { backCount++ },
                onVote = { _, _ -> },
            )
        }

        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }

        compose.runOnIdle {
            assertEquals(1, backCount)
            assertFalse(compose.activity.isFinishing)
        }
    }
}
