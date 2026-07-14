package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToIndex
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Issue #243: tapping a project link from within a topic shows the project page over
 * [TopicDetailScreen] via an early-return branch in `FeedScreen`, which stops the screen
 * (and its `rememberLazyListState`) from being composed at all while the project page is
 * shown. [TopicDetailScreen] must therefore seed its list state from an externally-owned
 * position (in production, [TopicDetailViewModel]'s plain — non-Compose-state — field) and
 * report scroll changes back out, rather than relying on `remember` surviving that gap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TopicDetailScrollPositionTest {

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
        postCount = 60,
    )

    private fun posts(count: Int) = (1..count.toLong()).map { id ->
        Post(id = id, body = "Reply $id", bodyHtml = "<p>Reply $id</p>")
    }

    @Test
    fun `scroll position survives the screen leaving and re-entering composition`() {
        // Stands in for TopicDetailViewModel's scrollPositions map: plain (non-Compose)
        // state that outlives TopicDetailScreen being torn down, exactly like the real
        // ViewModel field this test exercises the contract of.
        var recorded = ScrollPosition.TOP
        var showTopic by mutableStateOf(true)

        compose.setContent {
            if (showTopic) {
                TopicDetailScreen(
                    topic = topic,
                    postsState = TopicDetailState.Loaded(posts(60)),
                    onBack = {},
                    onVote = { _, _ -> },
                    initialScrollPosition = recorded,
                    onScrollPositionChanged = { index, offset -> recorded = ScrollPosition(index, offset) },
                )
            }
        }

        compose.onNode(hasScrollAction()).performScrollToIndex(20)
        compose.waitForIdle()
        assertTrue(recorded.index > 0, "precondition: the list actually scrolled down and reported it")
        val scrolledTo = recorded

        // Simulate a project page opening over the topic (FeedScreen's early-return branch):
        // the screen leaves composition entirely, then comes back — mirroring how a fresh
        // TopicDetailRoute call would be seeded from the ViewModel's recorded position.
        showTopic = false
        compose.waitForIdle()
        showTopic = true
        compose.waitForIdle()

        // The freshly-recomposed screen must have re-reported (at least) the seeded
        // position — it never regresses back toward the top on a bare re-entry.
        assertEquals(scrolledTo, recorded)
    }

    @Test
    fun `a topic never scrolled starts at the top without a recorded position`() {
        compose.setContent {
            TopicDetailScreen(
                topic = topic,
                postsState = TopicDetailState.Loaded(posts(60)),
                onBack = {},
                onVote = { _, _ -> },
            )
        }

        compose.waitForIdle()
        // Defaults (ScrollPosition.TOP / no-op callback) compose without incident — the
        // restore behavior itself is covered by the test above.
    }
}
