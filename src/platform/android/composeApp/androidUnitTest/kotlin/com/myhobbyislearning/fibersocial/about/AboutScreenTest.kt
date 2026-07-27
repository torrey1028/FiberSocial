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
            AboutScreen(
                onBack = {},
                onOpenRepo = {},
                onOpenPrivacyPolicy = {},
                onOpenTermsOfUse = {},
                onReportChildSafetyConcern = {},
            )
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
            AboutScreen(
                onBack = { backs++ },
                onOpenRepo = {},
                onOpenPrivacyPolicy = {},
                onOpenTermsOfUse = {},
                onReportChildSafetyConcern = {},
            )
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `system back press invokes onBack`() {
        var backs = 0
        compose.setContent {
            AboutScreen(
                onBack = { backs++ },
                onOpenRepo = {},
                onOpenPrivacyPolicy = {},
                onOpenTermsOfUse = {},
                onReportChildSafetyConcern = {},
            )
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
            AboutScreen(
                onBack = {},
                onOpenRepo = { opened++ },
                onOpenPrivacyPolicy = {},
                onOpenTermsOfUse = {},
                onReportChildSafetyConcern = {},
            )
        }
        // Last of four links in the scrollable column (issue #408 added "Terms of Use"
        // above it, on top of #289's "Report a child safety concern"), so it can be
        // scrolled out of the Robolectric viewport — scroll it into view before clicking.
        compose.onNodeWithText("View source on GitHub").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `tapping the privacy policy link invokes onOpenPrivacyPolicy`() {
        var opened = 0
        compose.setContent {
            AboutScreen(
                onBack = {},
                onOpenRepo = {},
                onOpenPrivacyPolicy = { opened++ },
                onOpenTermsOfUse = {},
                onReportChildSafetyConcern = {},
            )
        }
        compose.onNodeWithText("Privacy Policy").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `tapping the terms of use link invokes onOpenTermsOfUse`() {
        var opened = 0
        compose.setContent {
            AboutScreen(
                onBack = {},
                onOpenRepo = {},
                onOpenPrivacyPolicy = {},
                onOpenTermsOfUse = { opened++ },
                onReportChildSafetyConcern = {},
            )
        }
        compose.onNodeWithText("Terms of Use").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `tapping the child safety concern link invokes onReportChildSafetyConcern`() {
        var opened = 0
        compose.setContent {
            AboutScreen(
                onBack = {},
                onOpenRepo = {},
                onOpenPrivacyPolicy = {},
                onOpenTermsOfUse = {},
                onReportChildSafetyConcern = { opened++ },
            )
        }
        // No longer the last link once "Terms of Use" (issue #408) was added above it, so
        // like the GitHub link above, it can be scrolled out of the Robolectric viewport —
        // scroll it into view before clicking.
        compose.onNodeWithText("Report a child safety concern").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }
}
