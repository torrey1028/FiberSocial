package com.autom8ed.fibersocial.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class SettingsScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val user = RavelryUser(username = "knitwit")

    @Test
    fun `shows the signed-in username`() {
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {})
        }
        compose.onNodeWithText("knitwit").assertIsDisplayed()
        compose.onNodeWithText("Sign out").assertIsDisplayed()
    }

    @Test
    fun `sign out row invokes onSignOut`() {
        var signOuts = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = { signOuts++ })
        }
        compose.onNodeWithText("Sign out").performClick()
        compose.runOnIdle { assertEquals(1, signOuts) }
    }

    @Test
    fun `top-bar back arrow invokes onBack`() {
        var backs = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = { backs++ }, onSignOut = {})
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `system back press invokes onBack`() {
        var backs = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = { backs++ }, onSignOut = {})
        }
        compose.runOnIdle {
            compose.activity.onBackPressedDispatcher.onBackPressed()
        }
        compose.runOnIdle { assertEquals(1, backs) }
    }
}
