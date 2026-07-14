package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myhobbyislearning.fibersocial.feed.models.Group
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

/** The feed top bar layout: group badge + name on the left, drawer nav icon (issue #207). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedTopBarTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val group = Group(id = 10L, name = "KAL Hub", permalink = "kal-hub", forumId = 42L)

    @Test
    fun `shows the group name and its badge`() {
        compose.setContent { FeedTopBar(title = "KAL Hub", selectedGroup = group, onOpenDrawer = {}) }

        compose.onNodeWithText("KAL Hub").assertIsDisplayed()
        // The group has no badge image, so its monogram stands in.
        compose.onNodeWithTag("GroupBadgeMonogram").assertIsDisplayed()
    }

    @Test
    fun `the navigation icon opens the drawer`() {
        var opened = 0
        compose.setContent { FeedTopBar(title = "KAL Hub", selectedGroup = group, onOpenDrawer = { opened++ }) }

        compose.onNodeWithContentDescription("Select group").performClick()
        assertEquals(1, opened)
    }

    @Test
    fun `shows no badge when no group is selected`() {
        compose.setContent { FeedTopBar(title = "FiberSocial", selectedGroup = null, onOpenDrawer = {}) }

        compose.onNodeWithText("FiberSocial").assertIsDisplayed()
        compose.onNodeWithTag("GroupBadgeMonogram").assertDoesNotExist()
        compose.onNodeWithTag("GroupBadgeImage").assertDoesNotExist()
    }

    @Test
    fun `the filter menu opens on tap and offers both options`() {
        compose.setContent {
            FeedTopBar(title = "KAL Hub", selectedGroup = group, onOpenDrawer = {}, showUnreadOnly = false)
        }

        compose.onNodeWithContentDescription("Filter: showing all topics. Tap to change.").performClick()
        compose.onNodeWithText("All topics").assertIsDisplayed()
        compose.onNodeWithText("Unread only").assertIsDisplayed()
    }

    @Test
    fun `picking Unread only from the menu toggles the filter on`() {
        var toggled = 0
        compose.setContent {
            FeedTopBar(
                title = "KAL Hub",
                selectedGroup = group,
                onOpenDrawer = {},
                showUnreadOnly = false,
                onToggleUnreadOnly = { toggled++ },
            )
        }

        compose.onNodeWithContentDescription("Filter: showing all topics. Tap to change.").performClick()
        compose.onNodeWithText("Unread only").performClick()

        assertEquals(1, toggled)
    }

    @Test
    fun `picking All topics while already showing all does not toggle`() {
        var toggled = 0
        compose.setContent {
            FeedTopBar(
                title = "KAL Hub",
                selectedGroup = group,
                onOpenDrawer = {},
                showUnreadOnly = false,
                onToggleUnreadOnly = { toggled++ },
            )
        }

        compose.onNodeWithContentDescription("Filter: showing all topics. Tap to change.").performClick()
        compose.onNodeWithText("All topics").performClick()

        assertEquals(0, toggled)
    }

    @Test
    fun `the icon's description reflects an active unread filter`() {
        compose.setContent {
            FeedTopBar(title = "KAL Hub", selectedGroup = group, onOpenDrawer = {}, showUnreadOnly = true)
        }

        compose.onNodeWithContentDescription("Filter: showing unread topics only. Tap to change.").assertIsDisplayed()
    }
}
