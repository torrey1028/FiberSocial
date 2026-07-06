package com.autom8ed.fibersocial.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.notifications.PollCadence
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

    @Test
    fun `poll interval row is hidden while loading`() {
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {}, pollCadence = null)
        }
        compose.onNodeWithText("Check for new events").assertDoesNotExist()
    }

    @Test
    fun `poll interval row shows the current cadence`() {
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {}, pollCadence = PollCadence.A_FEW_TIMES_A_DAY)
        }
        compose.onNodeWithText("Check for new events").assertIsDisplayed()
        compose.onNodeWithText("A few times a day").assertIsDisplayed()
    }

    @Test
    fun `choosing a cadence from the dialog invokes the callback and closes it`() {
        var selected: PollCadence? = null
        compose.setContent {
            SettingsScreen(
                user = user, onBack = {}, onSignOut = {},
                pollCadence = PollCadence.A_FEW_TIMES_A_DAY,
                onPollCadenceSelected = { selected = it },
            )
        }
        compose.onNodeWithText("Check for new events").performClick()
        compose.onNodeWithText("Hourly").performClick()
        compose.runOnIdle { assertEquals(PollCadence.HOURLY, selected) }
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `cancel dismisses the dialog without selecting`() {
        var selected: PollCadence? = null
        compose.setContent {
            SettingsScreen(
                user = user, onBack = {}, onSignOut = {},
                pollCadence = PollCadence.A_FEW_TIMES_A_DAY,
                onPollCadenceSelected = { selected = it },
            )
        }
        compose.onNodeWithText("Check for new events").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(null, selected) }
    }

    @Test
    fun `theme row is hidden while loading`() {
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {}, themeMode = null)
        }
        compose.onNodeWithText("App theme").assertDoesNotExist()
    }

    @Test
    fun `theme row shows the current mode`() {
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {}, themeMode = ThemeMode.SYSTEM)
        }
        compose.onNodeWithText("App theme").assertIsDisplayed()
        compose.onNodeWithText("Follow system").assertIsDisplayed()
    }

    @Test
    fun `choosing a theme from the dialog invokes the callback and closes it`() {
        var selected: ThemeMode? = null
        compose.setContent {
            SettingsScreen(
                user = user, onBack = {}, onSignOut = {},
                themeMode = ThemeMode.SYSTEM,
                onThemeModeSelected = { selected = it },
            )
        }
        compose.onNodeWithText("App theme").performClick()
        compose.onNodeWithText("Dark").performClick()
        compose.runOnIdle { assertEquals(ThemeMode.DARK, selected) }
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `cancel dismisses the theme dialog without selecting`() {
        var selected: ThemeMode? = null
        compose.setContent {
            SettingsScreen(
                user = user, onBack = {}, onSignOut = {},
                themeMode = ThemeMode.LIGHT,
                onThemeModeSelected = { selected = it },
            )
        }
        compose.onNodeWithText("App theme").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(null, selected) }
    }
}
