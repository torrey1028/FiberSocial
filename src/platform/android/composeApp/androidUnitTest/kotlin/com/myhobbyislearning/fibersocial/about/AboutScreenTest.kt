package com.myhobbyislearning.fibersocial.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
            AboutScreen(onBack = {}, onOpenRepo = {}, onOpenPrivacyPolicy = {}, onReportChildSafetyConcern = {})
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
            AboutScreen(onBack = { backs++ }, onOpenRepo = {}, onOpenPrivacyPolicy = {}, onReportChildSafetyConcern = {})
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `system back press invokes onBack`() {
        var backs = 0
        compose.setContent {
            AboutScreen(onBack = { backs++ }, onOpenRepo = {}, onOpenPrivacyPolicy = {}, onReportChildSafetyConcern = {})
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
            AboutScreen(onBack = {}, onOpenRepo = { opened++ }, onOpenPrivacyPolicy = {}, onReportChildSafetyConcern = {})
        }
        // Now the last of four links in the scrollable column (issue #289 follow-up added
        // "Report a child safety concern" above it), so it can be scrolled out of the
        // Robolectric viewport — scroll it into view before clicking.
        compose.onNodeWithText("View source on GitHub").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `tapping the privacy policy link invokes onOpenPrivacyPolicy`() {
        var opened = 0
        compose.setContent {
            AboutScreen(onBack = {}, onOpenRepo = {}, onOpenPrivacyPolicy = { opened++ }, onReportChildSafetyConcern = {})
        }
        compose.onNodeWithText("Privacy Policy").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `tapping the child safety concern link invokes onReportChildSafetyConcern`() {
        var opened = 0
        compose.setContent {
            AboutScreen(onBack = {}, onOpenRepo = {}, onOpenPrivacyPolicy = {}, onReportChildSafetyConcern = { opened++ })
        }
        compose.onNodeWithText("Report a child safety concern").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
