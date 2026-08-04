package com.myhobbyislearning.fibersocial.feedback

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FeedbackScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun setScreen() {
        compose.setContent {
            FeedbackScreen(
                state = FeedbackState.Idle,
                deviceInfo = "device info",
                onBack = {},
                onSend = { _, _, _ -> },
                onSent = {},
                onOpenSupportGroup = {},
            )
        }
    }

    @Test
    fun `tapping the background clears field focus so the keyboard can dismiss`() {
        // Pins the tap-to-dismiss handler (issue #428): the pointerInput on the form
        // Column must receive taps that no field consumed and clear focus.
        setScreen()
        compose.onNodeWithText("Title").performClick()
        compose.onNodeWithText("Title").assertIsFocused()
        // The intro copy is a non-clickable Text inside the form Column, so a tap on it
        // falls through to the Column's tap handler (a topBar/root tap would miss it).
        compose.onNodeWithText(
            "Feedback is posted as a topic in the FiberSocial App Support group on " +
                "Ravelry. Give it a short title and describe the issue or idea — the more " +
                "detail, the better.",
        ).performTouchInput { click() }
        compose.onNodeWithText("Title").assertIsNotFocused()
    }

    @Test
    fun `tapping a field keeps its own focus`() {
        // The inverse property the handler's comment promises: fields consume their own
        // taps first, so the background tap handler must never steal a field's focus.
        setScreen()
        compose.onNodeWithText("Title").performClick()
        compose.onNodeWithText("Title").performClick()
        compose.onNodeWithText("Title").assertIsFocused()
    }
}
