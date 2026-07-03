package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class GroupDrawerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val user = RavelryUser(username = "knitwit")

    @Test
    fun `profile footer shows username and opens settings on click`() {
        var settingsClicks = 0
        compose.setContent {
            GroupDrawer(
                groups = emptyList(),
                selectedGroup = null,
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = { settingsClicks++ },
            )
        }
        compose.onNodeWithText("knitwit").assertIsDisplayed()
        compose.onNodeWithText("knitwit").performClick()
        compose.runOnIdle { assertEquals(1, settingsClicks) }
    }

    @Test
    fun `footer falls back to Account label when user is not loaded yet`() {
        compose.setContent {
            GroupDrawer(
                groups = emptyList(),
                selectedGroup = null,
                eventCounts = emptyMap(),
                user = null,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }
        compose.onNodeWithText("Account").assertIsDisplayed()
    }
}
