package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
    fun `moveItem moves forward, backward, and ignores invalid indices`() {
        val list = listOf("a", "b", "c")
        assertEquals(listOf("b", "c", "a"), moveItem(list, 0, 2))
        assertEquals(listOf("c", "a", "b"), moveItem(list, 2, 0))
        assertEquals(list, moveItem(list, 1, 1))
        assertEquals(list, moveItem(list, -1, 2))
        assertEquals(list, moveItem(list, 0, 3))
    }

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

    private val twoGroups = listOf(
        Group(id = 1L, name = "KAL Hub", permalink = "kal-hub", forumId = 42L),
        Group(id = 2L, name = "Sock Society", permalink = "sock", forumId = 43L),
    )

    private fun setReorderableDrawer(onReorder: (List<Group>) -> Unit) {
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                onReorder = onReorder,
            )
        }
    }

    @Test
    fun `in reorder mode a long-press drag over the next row commits the new order`() {
        var reordered: List<Group>? = null
        setReorderableDrawer { reordered = it }
        compose.onNodeWithText("Reorder").performClick()
        val rowHeight = compose.onNodeWithText("KAL Hub").fetchSemanticsNode().size.height
        compose.onNodeWithText("KAL Hub").performTouchInput {
            down(center)
            // Hold past the long-press timeout so the drag (not a click) starts.
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, rowHeight * 0.75f))
            moveBy(Offset(0f, rowHeight * 0.5f))
            up()
        }
        compose.runOnIdle {
            assertEquals(listOf(2L, 1L), reordered?.map { it.id })
        }
    }

    @Test
    fun `the drag handle drags immediately, without a long press`() {
        var reordered: List<Group>? = null
        setReorderableDrawer { reordered = it }
        compose.onNodeWithText("Reorder").performClick()
        val rowHeight = compose.onNodeWithText("KAL Hub").fetchSemanticsNode().size.height
        // Unmerged tree: the row merges descendant semantics, so the merged match would
        // be the whole row and the touch would land at the row's center, not the handle.
        compose.onNodeWithContentDescription("Reorder KAL Hub", useUnmergedTree = true)
            .performTouchInput {
                down(center)
                // The first ~touch-slop px are consumed by drag detection.
                repeat(10) { moveBy(Offset(0f, rowHeight * 0.15f)) }
                up()
            }
        compose.runOnIdle {
            assertEquals(listOf(2L, 1L), reordered?.map { it.id })
        }
    }

    @Test
    fun `outside reorder mode the list is locked and shows no handles`() {
        var reordered: List<Group>? = null
        setReorderableDrawer { reordered = it }
        compose.runOnIdle {
            assertEquals(
                0,
                compose.onAllNodes(
                    androidx.compose.ui.test.hasContentDescription("Reorder KAL Hub"),
                ).fetchSemanticsNodes().size,
            )
        }
        val rowHeight = compose.onNodeWithText("KAL Hub").fetchSemanticsNode().size.height
        compose.onNodeWithText("KAL Hub").performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveBy(Offset(0f, rowHeight * 0.75f))
            moveBy(Offset(0f, rowHeight * 0.5f))
            up()
        }
        compose.runOnIdle { assertEquals(null, reordered) }
    }

    @Test
    fun `row taps are disabled while reordering and Done re-enables them`() {
        var selected: Group? = null
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = { selected = it },
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }
        compose.onNodeWithText("Reorder").performClick()
        compose.onNodeWithText("Sock Society").performClick()
        compose.runOnIdle { assertEquals(null, selected) }

        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Sock Society").performClick()
        compose.runOnIdle { assertEquals(2L, selected?.id) }
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
