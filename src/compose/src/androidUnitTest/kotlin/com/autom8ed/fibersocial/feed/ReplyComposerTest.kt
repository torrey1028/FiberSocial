package com.autom8ed.fibersocial.feed

import androidx.compose.runtime.remember
import androidx.compose.runtime.Composable
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReplyComposerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `send button is disabled until text is entered`() {
        compose.setContent { StatefulReplyComposer(replyState = ReplyState.Idle, onSend = {}, onSent = {}) }
        compose.onNodeWithContentDescription("Send reply").assertIsNotEnabled()
        compose.onNodeWithText("Write a reply…").performTextInput("Hello!")
        compose.onNodeWithContentDescription("Send reply").assertIsEnabled()
    }

    @Test
    fun `send button invokes onSend with the typed text`() {
        var sent: String? = null
        compose.setContent { StatefulReplyComposer(replyState = ReplyState.Idle, onSend = { sent = it }, onSent = {}) }
        compose.onNodeWithText("Write a reply…").performTextInput("Hello!")
        compose.onNodeWithContentDescription("Send reply").performClick()
        compose.runOnIdle { assertEquals("Hello!", sent) }
    }

    @Test
    fun `Sent state clears the text and acknowledges`() {
        var acknowledged = 0
        var state by mutableStateOf<ReplyState>(ReplyState.Idle)
        compose.setContent { StatefulReplyComposer(replyState = state, onSend = {}, onSent = { acknowledged++ }) }
        compose.onNodeWithText("Write a reply…").performTextInput("Hello!")
        compose.runOnIdle { state = ReplyState.Sent }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, acknowledged) }
        // Placeholder visible again means the field was cleared.
        compose.onNodeWithText("Write a reply…").assertIsDisplayed()
    }

    @Test
    fun `Error state shows the failure message and keeps the text`() {
        var replyState by mutableStateOf<ReplyState>(ReplyState.Idle)
        compose.setContent {
            StatefulReplyComposer(replyState = replyState, onSend = {}, onSent = {})
        }
        compose.onNodeWithText("Write a reply…").performTextInput("my precious draft")
        replyState = ReplyState.Error("boom")
        compose.waitForIdle()

        compose.onNodeWithText("boom").assertIsDisplayed()
        compose.onNodeWithText("my precious draft").assertIsDisplayed()
    }

    @Test
    fun `attach image button is shown and disabled while a reply is sending`() {
        var replyState by mutableStateOf<ReplyState>(ReplyState.Idle)
        compose.setContent { StatefulReplyComposer(replyState = replyState, onSend = {}, onSent = {}) }
        compose.onNodeWithContentDescription("Attach image").assertIsEnabled()
        compose.runOnIdle { replyState = ReplyState.Sending }
        compose.onNodeWithContentDescription("Attach image").assertIsNotEnabled()
    }

    @Test
    fun `send is disabled while an image upload is in flight`() {
        var attachment by mutableStateOf<ImageAttachmentState>(ImageAttachmentState.Idle)
        compose.setContent {
            StatefulReplyComposer(
                replyState = ReplyState.Idle,
                onSend = {},
                onSent = {},
                attachment = attachment,
            )
        }
        compose.onNodeWithText("Write a reply…").performTextInput("look at my socks")
        compose.onNodeWithContentDescription("Send reply").assertIsEnabled()
        // Sending mid-upload would post without the image and strand its markdown
        // in the cleared draft.
        compose.runOnIdle { attachment = ImageAttachmentState.Uploading }
        compose.onNodeWithContentDescription("Send reply").assertIsNotEnabled()
    }

    @Test
    fun `Ready attachment appends its markdown to the draft and acknowledges`() {
        var inserted = 0
        var attachment by mutableStateOf<ImageAttachmentState>(ImageAttachmentState.Idle)
        compose.setContent {
            StatefulReplyComposer(
                replyState = ReplyState.Idle,
                onSend = {},
                onSent = {},
                attachment = attachment,
                onAttachmentInserted = { inserted++ },
            )
        }
        compose.onNodeWithText("Write a reply…").performTextInput("look at my socks")
        compose.runOnIdle { attachment = ImageAttachmentState.Ready("![](/attached/me/1.jpg)") }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, inserted) }
        compose.onNodeWithText("look at my socks\n\n![](/attached/me/1.jpg)").assertIsDisplayed()
    }

    @Test
    fun `attachment upload shows a spinner instead of the attach button`() {
        compose.setContent {
            StatefulReplyComposer(
                replyState = ReplyState.Idle,
                onSend = {},
                onSent = {},
                attachment = ImageAttachmentState.Uploading,
            )
        }
        compose.onNodeWithContentDescription("Attach image").assertDoesNotExist()
    }

    @Test
    fun `attachment error message is shown and the draft is kept`() {
        var attachment by mutableStateOf<ImageAttachmentState>(ImageAttachmentState.Idle)
        compose.setContent {
            StatefulReplyComposer(
                replyState = ReplyState.Idle,
                onSend = {},
                onSent = {},
                attachment = attachment,
            )
        }
        compose.onNodeWithText("Write a reply…").performTextInput("my precious draft")
        compose.runOnIdle {
            attachment = ImageAttachmentState.Error(ImageAttachmentViewModel.EXTRAS_REQUIRED_MESSAGE)
        }
        compose.waitForIdle()
        compose.onNodeWithText(ImageAttachmentViewModel.EXTRAS_REQUIRED_MESSAGE).assertIsDisplayed()
        compose.onNodeWithText("my precious draft").assertIsDisplayed()
    }

    @Test
    fun `attach button opens a source menu when project picking is available`() {
        var fromProjects = 0
        compose.setContent {
            StatefulReplyComposer(
                replyState = ReplyState.Idle,
                onSend = {},
                onSent = {},
                onPickFromProjects = { fromProjects++ },
            )
        }
        compose.onNodeWithContentDescription("Attach image").performClick()
        compose.onNodeWithText("Upload from device").assertIsDisplayed()
        compose.onNodeWithText("From your projects").performClick()
        compose.runOnIdle { assertEquals(1, fromProjects) }
        compose.onNodeWithText("From your projects").assertDoesNotExist()
    }
}

/** Test wrapper providing the hoisted draft state the screen normally owns. */
@Composable
private fun StatefulReplyComposer(
    replyState: ReplyState,
    onSend: (String) -> Unit,
    onSent: () -> Unit,
    attachment: ImageAttachmentState = ImageAttachmentState.Idle,
    onAttachmentInserted: () -> Unit = {},
    onPickFromProjects: (() -> Unit)? = null,
) {
    var text by remember { mutableStateOf("") }
    ReplyComposer(
        replyState = replyState,
        onSend = onSend,
        onSent = onSent,
        text = text,
        onTextChange = { text = it },
        attachment = attachment,
        onAttachmentInserted = onAttachmentInserted,
        onPickFromProjects = onPickFromProjects,
    )
}
