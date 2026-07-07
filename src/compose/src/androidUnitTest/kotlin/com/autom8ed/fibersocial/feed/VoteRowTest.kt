package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
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

        // Unused reactions are behind the add button; the collapse control isn't shown yet.
        compose.onNodeWithContentDescription("Add a reaction").assertIsDisplayed()
        compose.onNodeWithContentDescription("Hide reactions").assertDoesNotExist()

        // Tapping it expands the not-yet-used reactions (the control flips to collapse).
        compose.onNodeWithContentDescription("Add a reaction").performClick()
        compose.onNodeWithContentDescription("Hide reactions").assertIsDisplayed()
    }

    @Test
    fun `shows no add button when every reaction already has votes`() {
        val allVoted = VoteType.entries.associate { it.wireValue to 1 }
        compose.setContent { ReplyItem(post = post(allVoted), onVote = {}) }

        compose.onNodeWithContentDescription("Add a reaction").assertDoesNotExist()
    }
}
