package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class UnreadFilterEmptyStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `plain message with no affordance when there are no more pages to check`() {
        compose.setContent { UnreadFilterEmptyState(hasMore = false) }
        compose.onNodeWithText("No unread topics").assertIsDisplayed()
        compose.onNodeWithText("Check more topics").assertDoesNotExist()
    }

    @Test
    fun `offers to check further pages when more are available`() {
        // Regression: replacing FeedList with this empty state also drops FeedList's own
        // scroll-triggered pagination, which would otherwise strand the user on "No
        // unread topics" even if unread topics exist on pages not yet fetched.
        var loadMores = 0
        compose.setContent {
            UnreadFilterEmptyState(hasMore = true, onLoadMore = { loadMores++ })
        }
        compose.onNodeWithText("No unread topics in what's loaded so far").assertIsDisplayed()
        compose.onNodeWithText("Check more topics").performClick()
        compose.runOnIdle { assertEquals(1, loadMores) }
    }

    @Test
    fun `shows a spinner instead of the button while a page is already loading`() {
        compose.setContent { UnreadFilterEmptyState(hasMore = true, loadingMore = true) }
        compose.onNodeWithText("Check more topics").assertDoesNotExist()
    }
}
