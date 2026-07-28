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
class NoGroupsOnboardingTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the welcome copy and the drawer pointer`() {
        compose.setContent { NoGroupsOnboarding(onFindGroups = {}) }
        compose.onNodeWithText("Welcome to FiberSocial").assertIsDisplayed()
        compose.onNodeWithText(
            "FiberSocial shows the discussions from your Ravelry groups, " +
                "and you aren't in any groups yet. Join a group or two on Ravelry " +
                "to get started.",
        ).assertIsDisplayed()
        compose.onNodeWithText(
            "Groups you join appear in the menu — pull down to refresh here when you're done.",
        ).assertIsDisplayed()
    }

    @Test
    fun `find groups button invokes the link-out`() {
        var findGroups = 0
        compose.setContent { NoGroupsOnboarding(onFindGroups = { findGroups++ }) }
        compose.onNodeWithText("Find groups on Ravelry").performClick()
        compose.runOnIdle { assertEquals(1, findGroups) }
    }
}
