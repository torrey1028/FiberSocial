package com.myhobbyislearning.fibersocial.settings

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.notifications.PollCadence
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
    fun `tapping sign out opens a confirmation dialog without signing out yet`() {
        // Issue #262: a single tap must not sign out directly — it's the only other
        // tappable row on this screen and has no undo.
        var signOuts = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = { signOuts++ })
        }
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Sign out of FiberSocial?").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, signOuts) }
    }

    @Test
    fun `confirming the sign out dialog invokes onSignOut`() {
        var signOuts = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = { signOuts++ })
        }
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithTag("ConfirmSignOut").performClick()
        compose.runOnIdle { assertEquals(1, signOuts) }
        compose.onNodeWithText("Sign out of FiberSocial?").assertDoesNotExist()
    }

    @Test
    fun `canceling the sign out dialog does not sign out`() {
        var signOuts = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = { signOuts++ })
        }
        compose.onNodeWithText("Sign out").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(0, signOuts) }
        compose.onNodeWithText("Sign out of FiberSocial?").assertDoesNotExist()
    }

    @Test
    fun `system back press while the sign out dialog is open does not navigate out of Settings`() {
        // Regression: BackHandler(onBack = onBack) covering the whole screen was still
        // enabled while the dialog was up, so a back press invoked onBack directly —
        // navigating out of Settings with the confirmation dialog left dangling open over
        // whatever screen came next, instead of the back press being reserved for the
        // dialog (whose own dismiss-on-back is a framework/library concern, not something
        // this composable's own BackHandler should also be racing to handle).
        var signOuts = 0
        var backs = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = { backs++ }, onSignOut = { signOuts++ })
        }
        compose.onNodeWithText("Sign out").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle {
            assertEquals(0, signOuts)
            assertEquals(0, backs)
        }
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
    fun `about row is always shown and invokes its handler`() {
        var opened = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {}, onOpenAbout = { opened++ })
        }
        compose.onNodeWithText("About FiberSocial").assertIsDisplayed()
        compose.onNodeWithText("About FiberSocial").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test
    fun `debug panel row is hidden when no handler is provided`() {
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {})
        }
        compose.onNodeWithText("Debug panel").assertDoesNotExist()
    }

    @Test
    fun `debug panel row shows and invokes its handler on debug builds`() {
        var opened = 0
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {}, onOpenDebugPanel = { opened++ })
        }
        compose.onNodeWithText("Debug panel").assertIsDisplayed()
        compose.onNodeWithText("Debug panel").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
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

    @Test
    fun `notification kind toggles are hidden while settings load`() {
        // They share the cadence row's loaded-settings gate, so a null cadence hides them.
        compose.setContent {
            SettingsScreen(user = user, onBack = {}, onSignOut = {}, pollCadence = null)
        }
        compose.onNodeWithText("Event reminders").assertDoesNotExist()
        compose.onNodeWithText("New group events").assertDoesNotExist()
        compose.onNodeWithText("Replies to your topics").assertDoesNotExist()
        compose.onNodeWithText("New messages").assertDoesNotExist()
    }

    @Test
    fun `notification kind toggles show once settings load`() {
        compose.setContent {
            SettingsScreen(
                user = user, onBack = {}, onSignOut = {},
                pollCadence = PollCadence.A_FEW_TIMES_A_DAY,
            )
        }
        compose.onNodeWithText("Event reminders").assertIsDisplayed()
        compose.onNodeWithText("New group events").assertIsDisplayed()
        compose.onNodeWithText("Replies to your topics").assertIsDisplayed()
        compose.onNodeWithText("New messages").assertIsDisplayed()
    }

    @Test
    fun `toggling a notification kind invokes its callback with the flipped value`() {
        var replies: Boolean? = null
        compose.setContent {
            SettingsScreen(
                user = user, onBack = {}, onSignOut = {},
                pollCadence = PollCadence.A_FEW_TIMES_A_DAY,
                topicRepliesEnabled = true,
                onTopicRepliesEnabledChange = { replies = it },
            )
        }
        compose.onNodeWithText("Replies to your topics").performClick()
        compose.runOnIdle { assertEquals(false, replies) }
    }

    @Test
    fun `toggling new messages invokes its callback with the flipped value`() {
        var messages: Boolean? = null
        compose.setContent {
            SettingsScreen(
                user = user, onBack = {}, onSignOut = {},
                pollCadence = PollCadence.A_FEW_TIMES_A_DAY,
                newMessagesEnabled = true,
                onNewMessagesEnabledChange = { messages = it },
            )
        }
        compose.onNodeWithText("New messages").performClick()
        compose.runOnIdle { assertEquals(false, messages) }
    }

    @Test
    fun `the new messages row reflects a disabled setting`() {
        compose.setContent {
            SettingsScreen(
                user = user, onBack = {}, onSignOut = {},
                pollCadence = PollCadence.A_FEW_TIMES_A_DAY,
                newMessagesEnabled = false,
            )
        }
        compose.onNodeWithText("New messages").assertIsOff()
    }
}
