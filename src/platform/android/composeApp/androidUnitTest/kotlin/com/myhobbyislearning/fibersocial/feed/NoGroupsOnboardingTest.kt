package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class NoGroupsOnboardingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the welcome copy and the drawer pointer`() {
        compose.setContent { NoGroupsOnboarding(onFindGroups = {}) }
        compose.onNodeWithText("Welcome to FiberSocial").assertIsDisplayed()
        compose.onNodeWithText(
            "FiberSocial shows the discussions from your Ravelry groups, " +
                "and you aren't in any groups yet. Find a group or two " +
                "to get started.",
        ).assertIsDisplayed()
        // The pull-to-refresh hint is now scoped to joins made on Ravelry's website:
        // joining through the in-app browser refreshes the feed itself (issue #232), so
        // telling every user to pull down would be stale advice for the common path.
        compose.onNodeWithText(
            "Groups you join appear in the menu automatically. " +
                "Joined one on Ravelry's website instead? Pull down to refresh here.",
        ).assertIsDisplayed()
    }

    @Test
    fun `onboarding column is scrollable so pull-to-refresh can engage`() {
        // The verticalScroll modifier is load-bearing, not cosmetic: PullToRefreshBox
        // needs a nested-scrolling child for the pull gesture the copy still advertises
        // for groups joined on Ravelry's website.
        compose.setContent { NoGroupsOnboarding(onFindGroups = {}) }
        compose.onNode(hasScrollAction()).assertExists()
    }

    @Test
    fun `find groups button opens the in-app browser`() {
        // Renamed from "invokes the link-out": issue #232 was originally answered by
        // opening ravelry.com/groups/search in a real browser, and this button now opens
        // the native group browser instead. Same callback, different destination — and
        // the label no longer says "on Ravelry", since it no longer goes there.
        var findGroups = 0
        compose.setContent { NoGroupsOnboarding(onFindGroups = { findGroups++ }) }
        compose.onNodeWithText("Find groups").performClick()
        compose.runOnIdle { assertEquals(1, findGroups) }
    }
}
