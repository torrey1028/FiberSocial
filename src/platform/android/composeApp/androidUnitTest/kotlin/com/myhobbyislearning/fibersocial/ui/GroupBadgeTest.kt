package com.myhobbyislearning.fibersocial.ui

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.myhobbyislearning.fibersocial.feed.models.Group
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GroupBadgeTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun group(name: String = "KAL Hub", badgeUrl: String? = null) =
        Group(id = 10, name = name, permalink = "kal-hub", forumId = 42, badgeUrl = badgeUrl)

    @Test
    fun `renders the badge image when the group has one`() {
        compose.setContent {
            GroupBadge(group = group(badgeUrl = "https://img.example/badge.png"), size = 28.dp)
        }
        compose.onNodeWithTag("GroupBadgeImage").assertIsDisplayed()
        compose.onNodeWithTag("GroupBadgeMonogram").assertDoesNotExist()
    }

    @Test
    fun `falls back to a monogram of the group's first letter`() {
        compose.setContent {
            GroupBadge(group = group(name = "kirkland fiber arts"), size = 28.dp)
        }
        compose.onNodeWithTag("GroupBadgeMonogram").assertIsDisplayed()
        compose.onNodeWithText("K").assertIsDisplayed()
    }

    @Test
    fun `a blank group name monograms to a hash`() {
        compose.setContent {
            GroupBadge(group = group(name = "  "), size = 28.dp)
        }
        compose.onNodeWithText("#").assertIsDisplayed()
    }

    @Test
    fun `an emoji-prefixed name monograms to the whole emoji, not a broken surrogate`() {
        // "🧶" is an astral-plane codepoint (a surrogate pair); take(1) would keep only the
        // high surrogate and draw a tofu box. The monogram must be the whole codepoint.
        compose.setContent {
            GroupBadge(group = group(name = "🧶 Yarn Lovers"), size = 28.dp)
        }
        compose.onNodeWithText("🧶").assertIsDisplayed()
    }
}
