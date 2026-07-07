package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import kotlin.test.assertEquals
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.VoteType
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Declutter of the post reactions (issue #219): only reactions that already have votes
 * show; the rest hide behind a [+] the user can expand to add a new reaction.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class VoteRowTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun post(votes: Map<String, Int>) = Post(
        id = 1L,
        bodyHtml = "<p>hi</p>",
        user = RavelryUser(username = "a"),
        voteTotals = votes,
    )

    @Test
    fun `hides unused reactions behind a plus and reveals them on tap`() {
        compose.setContent { ReplyItem(post = post(mapOf("love" to 2)), onVote = {}) }

        // The voted reaction shows; an unused one (funny) is hidden behind the add button,
        // and the collapse control isn't shown yet.
        compose.onNodeWithText("❤️").assertIsDisplayed()
        compose.onNodeWithText("😂").assertDoesNotExist()
        compose.onNodeWithContentDescription("Add a reaction").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide reactions").assertDoesNotExist()

        // Tapping it actually reveals the not-yet-used reactions (not just flips the icon):
        // the previously-hidden funny reaction is now on screen and the control flips.
        compose.onNodeWithContentDescription("Add a reaction").performClick()
        compose.onNodeWithText("😂").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide reactions").assertIsDisplayed()
    }

    @Test
    fun `picking a hidden reaction casts the vote and collapses the picker`() {
        var voted: VoteType? = null
        compose.setContent { ReplyItem(post = post(mapOf("love" to 2)), onVote = { voted = it }) }

        // Expand, then tap the previously-hidden funny reaction.
        compose.onNodeWithContentDescription("Add a reaction").performClick()
        compose.onNodeWithText("😂").performClick()

        // It cast that reaction and collapsed the picker back to [+].
        assertEquals(VoteType.FUNNY, voted)
        compose.onNodeWithContentDescription("Add a reaction").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide reactions").assertDoesNotExist()
    }

    @Test
    fun `shows no add button when every reaction already has votes`() {
        val allVoted = VoteType.entries.associate { it.wireValue to 1 }
        compose.setContent { ReplyItem(post = post(allVoted), onVote = {}) }

        compose.onNodeWithContentDescription("Add a reaction").assertDoesNotExist()
    }
}
