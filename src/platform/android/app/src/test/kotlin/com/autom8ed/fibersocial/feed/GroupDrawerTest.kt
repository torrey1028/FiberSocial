package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
class GroupDrawerTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val user = RavelryUser(username = "knitwit")

    @Test
    fun `profile footer shows username and opens settings on click`() {
        var settingsClicks = 0
        compose.setContent {
            GroupDrawer(
                groups = emptyList(),
                selectedGroup = null,
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = { settingsClicks++ },
            )
        }
        compose.onNodeWithText("knitwit").assertIsDisplayed()
        compose.onNodeWithText("knitwit").performClick()
        compose.runOnIdle { assertEquals(1, settingsClicks) }
    }

    @Test
    fun `drawer lists groups in the given order with no All Groups entry`() {
        // Issue #97: the synthetic All Groups row is gone; the list order is the
        // caller's (stored) order, verbatim.
        val groups = listOf(
            Group(id = 2L, name = "Sock Society", permalink = "sock", forumId = 43L),
            Group(id = 1L, name = "KAL Hub", permalink = "kal-hub", forumId = 42L),
        )
        var selected: Group? = null
        compose.setContent {
            GroupDrawer(
                groups = groups,
                selectedGroup = groups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = { selected = it },
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }
        compose.runOnIdle {
            assertEquals(
                0,
                compose.onAllNodes(hasText("All Groups")).fetchSemanticsNodes().size,
            )
        }
        val positions = groups.map { group ->
            compose.onNodeWithText(group.name).fetchSemanticsNode()
                .positionInRoot.y
        }
        compose.runOnIdle { assertEquals(positions, positions.sorted()) }

        compose.onNodeWithText("KAL Hub").performClick()
        compose.runOnIdle { assertEquals(1L, selected?.id) }
    }

    @Test
    fun `footer falls back to Account label when user is not loaded yet`() {
        compose.setContent {
            GroupDrawer(
                groups = emptyList(),
                selectedGroup = null,
                eventCounts = emptyMap(),
                user = null,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }
        compose.onNodeWithText("Account").assertIsDisplayed()
    }
}
