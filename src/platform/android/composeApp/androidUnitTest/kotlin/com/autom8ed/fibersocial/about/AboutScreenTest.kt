package com.autom8ed.fibersocial.about

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

@RunWith(RobolectricTestRunner::class)
class AboutScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the non-affiliation statement`() {
        compose.setContent {
            AboutScreen(onBack = {}, onOpenRepo = {})
        }
        compose.onNodeWithText(
            "FiberSocial is an independent, unofficial app for Ravelry. It is not " +
                "created by, operated by, affiliated with, or endorsed by Ravelry — it's a " +
                "third-party client built by an outside developer using Ravelry's public API " +
                "and website. \"Ravelry\" belongs to its own owners.",
        ).assertIsDisplayed()
    }

    @Test
    fun `top-bar back arrow invokes onBack`() {
        var backs = 0
        compose.setContent {
            AboutScreen(onBack = { backs++ }, onOpenRepo = {})
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `system back press invokes onBack`() {
        var backs = 0
        compose.setContent {
            AboutScreen(onBack = { backs++ }, onOpenRepo = {})
        }
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `tapping the repo link invokes onOpenRepo`() {
        var opened = 0
        compose.setContent {
            AboutScreen(onBack = {}, onOpenRepo = { opened++ })
        }
        compose.onNodeWithText("View source on GitHub").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
