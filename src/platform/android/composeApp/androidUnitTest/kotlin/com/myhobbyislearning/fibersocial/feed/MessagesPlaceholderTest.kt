package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Issue #369 landed the Messages drawer destination pointing at a placeholder body so the
 * navigation could merge independently of the messages API being built in parallel (epic
 * #365). This asserts the destination actually renders something rather than an empty
 * Scaffold — the failure mode that would make "Messages" look like a dead drawer row.
 */
@RunWith(RobolectricTestRunner::class)
class MessagesPlaceholderTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `shows the empty-inbox message`() {
        compose.setContent { MessagesPlaceholder() }

        compose.onNodeWithTag("MessagesPlaceholder").assertIsDisplayed()
        compose.onNodeWithText("No messages yet").assertIsDisplayed()
    }
}
