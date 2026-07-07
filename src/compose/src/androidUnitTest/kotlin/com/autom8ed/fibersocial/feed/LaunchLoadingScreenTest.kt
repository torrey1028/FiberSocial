package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The full-page launch loading screen (issue #233). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class LaunchLoadingScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the app name and tagline while the feed loads`() {
        compose.setContent { LaunchLoadingScreen() }

        compose.onNodeWithText("FiberSocial").assertIsDisplayed()
        compose.onNodeWithText("A community companion for Ravelry").assertIsDisplayed()
    }
}
