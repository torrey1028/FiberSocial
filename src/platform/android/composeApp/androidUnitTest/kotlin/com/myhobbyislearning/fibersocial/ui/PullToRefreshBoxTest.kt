package com.myhobbyislearning.fibersocial.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * Covers the shared pull-to-refresh wrapper reused by FeedScreen, EventsScreen,
 * TopicDetailScreen, and EventDetailScreen (issue #69) — a swipe-down over its
 * scrollable content invokes [onRefresh], and the wrapped content stays displayed
 * whether or not a refresh is in flight (unlike a full-screen loading state).
 */
@RunWith(RobolectricTestRunner::class)
class PullToRefreshBoxTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `swiping down over the content invokes onRefresh`() {
        var refreshCount = 0
        compose.setContent {
            PullToRefreshBox(refreshing = false, onRefresh = { refreshCount++ }) {
                LazyColumn(modifier = Modifier.testTag("list")) {
                    items(20) { index -> Text("Item $index") }
                }
            }
        }

        compose.onNodeWithTag("list").performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(1, refreshCount)
    }

    @Test
    fun `swiping down does nothing when disabled`() {
        // Issue #246: the group drawer suppresses the gesture entirely during reorder
        // mode, since a refresh mid-drag would corrupt the in-progress reorder.
        var refreshCount = 0
        compose.setContent {
            PullToRefreshBox(refreshing = false, onRefresh = { refreshCount++ }, enabled = false) {
                LazyColumn(modifier = Modifier.testTag("list")) {
                    items(20) { index -> Text("Item $index") }
                }
            }
        }

        compose.onNodeWithTag("list").performTouchInput { swipeDown() }
        compose.waitForIdle()

        assertEquals(0, refreshCount)
    }

    @Test
    fun `content stays displayed while refreshing`() {
        compose.setContent {
            PullToRefreshBox(refreshing = true, onRefresh = {}) {
                Text("Still here")
            }
        }

        compose.onNodeWithText("Still here").assertIsDisplayed()
    }

    @Test
    fun `content stays displayed when not refreshing`() {
        compose.setContent {
            PullToRefreshBox(refreshing = false, onRefresh = {}) {
                Text("Still here")
            }
        }

        compose.onNodeWithText("Still here").assertIsDisplayed()
    }
}
