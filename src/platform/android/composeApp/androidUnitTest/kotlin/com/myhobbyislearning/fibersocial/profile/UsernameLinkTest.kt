package com.myhobbyislearning.fibersocial.profile

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UsernameLinkTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `renders the handle with an at-sign and opens the profile on tap`() {
        var opened: String? = null
        compose.setContent {
            CompositionLocalProvider(LocalProfileOpener provides { opened = it }) {
                UsernameLink(username = "yarnie")
            }
        }
        compose.onNodeWithText("@yarnie").assertIsDisplayed()
        compose.onNodeWithText("@yarnie").performClick()
        compose.runOnIdle { assertEquals("yarnie", opened) }
    }

    @Test
    fun `is plain text with no opener provided`() {
        compose.setContent { UsernameLink(username = "yarnie") }
        // Still shown, just not interactive — a tap does nothing (no opener to record).
        compose.onNodeWithText("@yarnie").assertIsDisplayed()
        compose.onNodeWithText("@yarnie").performClick()
    }
}
