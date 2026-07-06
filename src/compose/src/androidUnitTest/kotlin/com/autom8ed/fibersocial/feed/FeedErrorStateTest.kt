package com.autom8ed.fibersocial.feed

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
class FeedErrorStateTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `retry button re-runs the load`() {
        // Regression: the error screen used to be a dead end — refresh() no-ops
        // outside Loaded, so nothing short of force-restarting the app recovered.
        var retries = 0
        compose.setContent {
            FeedErrorState(rawMessage = "boom", onRetry = { retries++ })
        }
        compose.onNodeWithText("Couldn't load the feed. Check your connection and try again.")
            .assertIsDisplayed()
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `auth-flavored errors show the session-expired message`() {
        compose.setContent {
            FeedErrorState(rawMessage = "HTTP 401 unauthorized", onRetry = {})
        }
        compose.onNodeWithText("Session expired. Please log out and sign in again.")
            .assertIsDisplayed()
        compose.onNodeWithText("Retry").assertIsDisplayed()
    }
}
