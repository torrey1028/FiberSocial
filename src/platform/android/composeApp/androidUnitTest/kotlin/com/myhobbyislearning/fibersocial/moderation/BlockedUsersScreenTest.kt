package com.myhobbyislearning.fibersocial.moderation

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

/**
 * The manage/unblock list reached from Settings (issue #410). Mirrors
 * [com.myhobbyislearning.fibersocial.messages.MessagesScreen]'s empty/populated-list test
 * shape for the sibling screen.
 */
@RunWith(RobolectricTestRunner::class)
class BlockedUsersScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun `empty list shows the empty state`() {
        compose.setContent {
            BlockedUsersScreen(blockedUsernames = emptyList(), onBack = {}, onUnblock = {})
        }
        compose.onNodeWithText("You haven't blocked anyone.").assertIsDisplayed()
    }

    @Test
    fun `populated list shows every blocked username`() {
        compose.setContent {
            BlockedUsersScreen(
                blockedUsernames = listOf("alice", "bob"),
                onBack = {},
                onUnblock = {},
            )
        }
        compose.onNodeWithText("@alice").assertIsDisplayed()
        compose.onNodeWithText("@bob").assertIsDisplayed()
    }

    @Test
    fun `tapping unblock invokes the callback with that username`() {
        var unblocked: String? = null
        compose.setContent {
            BlockedUsersScreen(
                blockedUsernames = listOf("alice", "bob"),
                onBack = {},
                onUnblock = { unblocked = it },
            )
        }
        compose.onNodeWithTag("Unblock-alice").performClick()
        compose.runOnIdle { assertEquals("alice", unblocked) }
    }

    @Test
    fun `back arrow invokes onBack`() {
        var backs = 0
        compose.setContent {
            BlockedUsersScreen(blockedUsernames = emptyList(), onBack = { backs++ }, onUnblock = {})
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }
}
