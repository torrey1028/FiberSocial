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
}

/** Test wrapper providing the hoisted draft state the screen normally owns. */
@Composable
private fun StatefulReplyComposer(
    replyState: ReplyState,
    onSend: (String) -> Unit,
    onSent: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    ReplyComposer(
        replyState = replyState,
        onSend = onSend,
        onSent = onSent,
        text = text,
        onTextChange = { text = it },
    )
}
