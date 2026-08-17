package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.myhobbyislearning.fibersocial.feed.models.Group
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class GroupSearchScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val lace = Group(
        id = 1L,
        name = "Lace Knitters",
        permalink = "lace-knitters",
        forumId = 11L,
        shortDescription = "For lovers of lace",
    )

    private fun loaded(
        groups: List<Group> = listOf(lace),
        query: String = "",
        hasMore: Boolean = false,
        loadingMore: Boolean = false,
    ) = GroupSearchState.Loaded(groups, query, page = 1, hasMore = hasMore, loadingMore = loadingMore)

    @Test
    fun `shows a result with its description`() {
        compose.setContent {
            GroupSearchScreen(state = loaded(), query = "", onQueryChange = {}, onBack = {})
        }
        compose.onNodeWithText("Lace Knitters").assertIsDisplayed()
        compose.onNodeWithText("For lovers of lace").assertIsDisplayed()
    }

    @Test
    fun `the search field reports edits upward`() {
        // The field is stateless — the ViewModel owns the query so a rotation can't lose
        // it — so every edit has to come back out through onQueryChange or typing does
        // nothing at all.
        val typed = mutableListOf<String>()
        compose.setContent {
            GroupSearchScreen(state = loaded(), query = "", onQueryChange = { typed += it }, onBack = {})
        }
        compose.onNodeWithTag("GroupSearchField").performTextInput("lace")
        compose.runOnIdle { assertEquals(listOf("lace"), typed) }
    }

    @Test
    fun `the join button reports which group was tapped`() {
        var joined: String? = null
        compose.setContent {
            GroupSearchScreen(
                state = loaded(),
                query = "",
                onQueryChange = {},
                onBack = {},
                onJoin = { joined = it.permalink },
            )
        }
        compose.onNodeWithTag("Join-lace-knitters").performClick()
        compose.runOnIdle { assertEquals("lace-knitters", joined) }
    }

    @Test
    fun `an already-joined group shows Joined instead of a join button`() {
        // Ravelry's results carry no membership flag, so this label is the only feedback
        // a successful join produces — without it the tap looks like it did nothing.
        compose.setContent {
            GroupSearchScreen(
                state = loaded(),
                query = "",
                onQueryChange = {},
                onBack = {},
                joinedPermalinks = setOf("lace-knitters"),
            )
        }
        compose.onNodeWithText("Joined").assertIsDisplayed()
        compose.onNodeWithTag("Join-lace-knitters").assertDoesNotExist()
    }

    @Test
    fun `a join in flight replaces that row's button with a spinner`() {
        compose.setContent {
            GroupSearchScreen(
                state = loaded(),
                query = "",
                onQueryChange = {},
                onBack = {},
                joiningPermalink = "lace-knitters",
            )
        }
        compose.onNodeWithTag("Join-lace-knitters").assertDoesNotExist()
    }

    @Test
    fun `a join error is shown and can be dismissed`() {
        var dismissed = 0
        compose.setContent {
            GroupSearchScreen(
                state = loaded(),
                query = "",
                onQueryChange = {},
                onBack = {},
                joinError = "Couldn't join Lace Knitters. Please try again.",
                onDismissJoinError = { dismissed++ },
            )
        }
        compose.onNodeWithText("Couldn't join Lace Knitters. Please try again.").assertIsDisplayed()
        compose.onNodeWithText("Dismiss").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }

    @Test
    fun `an empty result names the query rather than looking broken`() {
        compose.setContent {
            GroupSearchScreen(
                state = loaded(groups = emptyList(), query = "zzzz"),
                query = "zzzz",
                onQueryChange = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("No groups match \"zzzz\".").assertIsDisplayed()
    }

    @Test
    fun `an error offers a retry`() {
        var retries = 0
        compose.setContent {
            GroupSearchScreen(
                state = GroupSearchState.Error("Couldn't search groups."),
                query = "",
                onQueryChange = {},
                onBack = {},
                onRetry = { retries++ },
            )
        }
        compose.onNodeWithText("Couldn't search groups.").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `load more shows only while another page exists`() {
        var loads = 0
        compose.setContent {
            GroupSearchScreen(
                state = loaded(hasMore = true),
                query = "",
                onQueryChange = {},
                onBack = {},
                onLoadMore = { loads++ },
            )
        }
        compose.onNodeWithText("Load more").performClick()
        compose.runOnIdle { assertEquals(1, loads) }
    }

    @Test
    fun `load more is replaced by a spinner while the next page is in flight`() {
        compose.setContent {
            GroupSearchScreen(
                state = loaded(hasMore = true, loadingMore = true),
                query = "",
                onQueryChange = {},
                onBack = {},
            )
        }
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test
    fun `the last page offers no load more`() {
        compose.setContent {
            GroupSearchScreen(state = loaded(hasMore = false), query = "", onQueryChange = {}, onBack = {})
        }
        compose.onNodeWithText("Load more").assertDoesNotExist()
    }

    @Test
    fun `tapping a result opens that group`() {
        var opened: String? = null
        compose.setContent {
            GroupSearchScreen(
                state = loaded(),
                query = "",
                onQueryChange = {},
                onBack = {},
                onOpenGroup = { opened = it.permalink },
            )
        }
        compose.onNodeWithText("Lace Knitters").performClick()
        compose.runOnIdle { assertEquals("lace-knitters", opened) }
    }

    @Test
    fun `the back arrow and system back both invoke onBack`() {
        var backs = 0
        compose.setContent {
            GroupSearchScreen(state = loaded(), query = "", onQueryChange = {}, onBack = { backs++ })
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(2, backs) }
    }
}
