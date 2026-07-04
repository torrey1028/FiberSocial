package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * FeedBottomBar hosts the group-selector hamburger and current group name at
 * the bottom of the feed screen (issue #79), replacing the old top app bar.
 */
@RunWith(RobolectricTestRunner::class)
class FeedBottomBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the group name and opens the drawer when the menu button is tapped`() {
        var menuClicks = 0
        compose.setContent {
            FeedBottomBar(
                title = "Fiber Friends",
                onMenuClick = { menuClicks++ },
                showDebugAction = false,
                onDebugClick = {},
            )
        }

        compose.onNodeWithText("Fiber Friends").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select group").assertIsDisplayed()
        compose.onNodeWithContentDescription("Select group").performClick()
        compose.runOnIdle { assertEquals(1, menuClicks) }

        // Debug action is hidden in release builds (showDebugAction = false).
        compose.onNodeWithContentDescription("Debug panel").assertDoesNotExist()
    }

    @Test
    fun `shows the debug action when enabled and invokes its callback`() {
        var debugClicks = 0
        compose.setContent {
            FeedBottomBar(
                title = "All Groups",
                onMenuClick = {},
                showDebugAction = true,
                onDebugClick = { debugClicks++ },
            )
        }

        compose.onNodeWithContentDescription("Debug panel").assertIsDisplayed()
        compose.onNodeWithContentDescription("Debug panel").performClick()
        compose.runOnIdle { assertEquals(1, debugClicks) }
    }
}
