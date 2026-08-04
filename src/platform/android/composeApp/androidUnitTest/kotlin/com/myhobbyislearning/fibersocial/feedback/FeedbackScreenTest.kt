package com.myhobbyislearning.fibersocial.feedback

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import com.myhobbyislearning.fibersocial.feed.ImageAttachmentState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

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
    fun `Ready attachment appends its markdown to the description and acknowledges`() {
        var inserted = 0
        var sentDescription: String? = null
        var attachment by mutableStateOf<ImageAttachmentState>(ImageAttachmentState.Idle)
        compose.setContent {
            FeedbackScreen(
                state = FeedbackState.Idle,
                deviceInfo = "device info",
                onBack = {},
                onSend = { _, description, _ -> sentDescription = description },
                onSent = {},
                onOpenSupportGroup = {},
                attachment = attachment,
                onAttachmentInserted = { inserted++ },
            )
        }
        compose.onNodeWithText("Title").performTextInput("Crash on login")
        compose.onNodeWithText("Describe the issue or idea").performTextInput("It crashed.")
        compose.runOnIdle { attachment = ImageAttachmentState.Ready("![](/attached/me/1.jpg)") }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, inserted) }
        compose.onNode(hasText("Send feedback") and hasClickAction()).performScrollTo().performClick()
        compose.runOnIdle { assertEquals("It crashed.\n\n![](/attached/me/1.jpg)", sentDescription) }
    }

    @Test
    fun `send is disabled while a screenshot upload is in flight`() {
        var attachment by mutableStateOf<ImageAttachmentState>(ImageAttachmentState.Idle)
        compose.setContent {
            FeedbackScreen(
                state = FeedbackState.Idle,
                deviceInfo = "device info",
                onBack = {},
                onSend = { _, _, _ -> },
                onSent = {},
                onOpenSupportGroup = {},
                attachment = attachment,
            )
        }
        compose.onNodeWithText("Title").performTextInput("Crash on login")
        compose.onNodeWithText("Describe the issue or idea").performTextInput("It crashed.")
        compose.onNode(hasText("Send feedback") and hasClickAction()).performScrollTo().assertIsEnabled()
        compose.runOnIdle { attachment = ImageAttachmentState.Uploading }
        compose.onNode(hasText("Send feedback") and hasClickAction()).assertIsNotEnabled()
        compose.runOnIdle { attachment = ImageAttachmentState.Ready("![](/attached/me/1.jpg)") }
        compose.onNode(hasText("Send feedback") and hasClickAction()).assertIsEnabled()
    }

    @Test
    fun `attachment error message is shown next to the attach button`() {
        compose.setContent {
            FeedbackScreen(
                state = FeedbackState.Idle,
                deviceInfo = "device info",
                onBack = {},
                onSend = { _, _, _ -> },
                onSent = {},
                onOpenSupportGroup = {},
                attachment = ImageAttachmentState.Error("Too big to upload."),
            )
        }
        compose.onNodeWithText("Too big to upload.").assertIsDisplayed()
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
