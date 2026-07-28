package com.myhobbyislearning.fibersocial.debug

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #395 — the session-cookie switch. The interesting assertions are the negative
 * ones: the row must not exist outside a debug build, and it must start off.
 */
@RunWith(RobolectricTestRunner::class)
class DebugPanelTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // resetForTest is internal to :common and invisible here, but the public API already
    // guarantees this reset: initDebugBuild(false) clears an enabled flag, which
    // DebugFlagsTest pins as behaviour in its own right.
    @After
    fun tearDown() = DebugFlags.initDebugBuild(false)

    private fun showPanel() {
        compose.setContent {
            DebugPanel(
                onForceSessionExpiry = {},
                onRunEventSync = {},
                onDismiss = {},
            )
        }
    }

    @Test
    fun `the session cookie switch is absent outside a debug build`() {
        DebugFlags.initDebugBuild(false)

        showPanel()

        compose.onNodeWithText("Log session cookies").assertDoesNotExist()
    }

    @Test
    fun `the session cookie switch is offered in a debug build and starts off`() {
        DebugFlags.initDebugBuild(true)

        showPanel()

        compose.onNodeWithText("Log session cookies").assertIsOff()
        assertFalse(DebugFlags.sessionCookieLoggingEnabled)
    }

    @Test
    fun `tapping the switch opts in and tapping again opts back out`() {
        DebugFlags.initDebugBuild(true)
        showPanel()

        compose.onNodeWithText("Log session cookies").performClick()
        compose.onNodeWithText("Log session cookies").assertIsOn()
        assertTrue(DebugFlags.sessionCookieLoggingEnabled)

        compose.onNodeWithText("Log session cookies").performClick()
        compose.onNodeWithText("Log session cookies").assertIsOff()
        assertFalse(DebugFlags.sessionCookieLoggingEnabled)
    }

    @Test
    fun `the switch warns that the logged value can impersonate the account`() {
        // The copy is the only thing standing between a developer and pasting a working
        // credential into a bug report, so assert it says so rather than trusting it to
        // survive a future tidy-up.
        DebugFlags.initDebugBuild(true)

        showPanel()

        compose.onNodeWithText("impersonate", substring = true).assertExists()
    }
}
