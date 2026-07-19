package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
// The default Robolectric screen is ~320x336dp. Since #347 pinned "Posts"/"Groups"
// above the scrolling list, that leaves only ~174dp of pull area — and M3
// pull-to-refresh's 0.5x drag multiplier plus touch slop drops a full-height swipe
// just below the 80dp trigger threshold, so the gesture tests fail for want of screen
// rather than for want of a working drawer. A real phone's drawer is several times
// taller; this qualifier makes the test viewport representative of one.
@Config(qualifiers = "w360dp-h800dp")
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
    fun `my posts row sits above the groups and invokes its callback`() {
        var myPostsClicks = 0
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                myPostsSelected = false,
                onMyPostsSelected = { myPostsClicks++ },
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }
        compose.onNodeWithText("Posts").assertIsDisplayed()
        compose.onNodeWithText("Posts").performClick()
        compose.runOnIdle { assertEquals(1, myPostsClicks) }
    }

    /**
     * Issue #347: unselected NavigationDrawerItems paint no pill, so the Posts/Groups rows
     * read as flat text on the sheet. The bracketing dividers are what make them look like a
     * tappable navigation cluster — assert they render so the treatment can't be dropped
     * silently.
     */
    @Test
    fun `posts and groups are bracketed by dividers`() {
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }

        // A rule above each row, and deliberately none below "Groups" — it heads the
        // group list beneath it, so a line there would cut it off from its own children.
        compose.onNodeWithTag("DrawerNavClusterTop").assertIsDisplayed()
        compose.onNodeWithTag("DrawerNavClusterMiddle").assertIsDisplayed()
        compose.onNodeWithTag("DrawerNavClusterBottom").assertDoesNotExist()
        compose.onNodeWithText("Posts").assertIsDisplayed()
        compose.onNodeWithText("Groups").assertIsDisplayed()
    }

    @Test
    fun `collapsing Groups hides the group rows but keeps Posts`() {
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }

        compose.onNodeWithText("Groups").performClick()

        compose.onNodeWithText(twoGroups[0].name).assertDoesNotExist()
        compose.onNodeWithText(twoGroups[1].name).assertDoesNotExist()
        compose.onNodeWithText("Find groups").assertDoesNotExist()
        compose.onNodeWithText("Posts").assertIsDisplayed()
        // Folded: the Edit affordance yields to the hidden-group count.
        compose.onNodeWithText("Edit").assertDoesNotExist()
        compose.onNodeWithText("2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expand your groups").assertIsDisplayed()
    }

    @Test
    fun `expanding Groups restores the group rows`() {
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }

        compose.onNodeWithText("Groups").performClick()
        compose.onNodeWithText("Groups").performClick()

        compose.onNodeWithText(twoGroups[0].name).assertIsDisplayed()
        compose.onNodeWithText("Find groups").assertIsDisplayed()
        compose.onNodeWithText("Edit").assertIsDisplayed()
        compose.onNodeWithContentDescription("Collapse your groups").assertIsDisplayed()
    }

    @Test
    fun `find groups row invokes onFindGroups`() {
        var found = 0
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                onFindGroups = { found++ },
            )
        }
        compose.onNodeWithText("Find groups").performClick()
        compose.runOnIdle { assertEquals(1, found) }
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
        val positions: List<Float> = groups.map { group ->
            compose.onNodeWithText(group.name).fetchSemanticsNode()
                .positionInRoot.y
        }
        compose.runOnIdle { assertEquals(positions, positions.sorted()) }

        compose.onNodeWithText("KAL Hub").performClick()
        compose.runOnIdle { assertEquals(1L, selected?.id) }
    }

    // Unread indicators (unread dots) — the dot's semantics label; counted in the
    // unmerged tree since NavigationDrawerItem merges its icon into the row node.
    private fun unreadDotCount(): Int =
        compose.onAllNodes(
            androidx.compose.ui.test.hasContentDescription("Unread posts"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().size

    @Test
    fun `only the group whose forum has unread shows a dot`() {
        compose.setContent {
            GroupDrawer(
                groups = twoGroups, // KAL Hub forumId 42, Sock Society forumId 43
                selectedGroup = twoGroups.first(),
                unreadGroupForumIds = setOf(42L),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }
        compose.runOnIdle { assertEquals(1, unreadDotCount()) }
    }

    @Test
    fun `no unread dots when nothing is unread`() {
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }
        compose.runOnIdle { assertEquals(0, unreadDotCount()) }
    }

    @Test
    fun `Posts shows an unread dot when its posts have unread replies`() {
        compose.setContent {
            GroupDrawer(
                groups = emptyList(),
                selectedGroup = null,
                myPostsHasUnread = true,
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
            )
        }
        compose.runOnIdle { assertEquals(1, unreadDotCount()) }
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
        compose.onNodeWithText("Edit").performClick()
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
        compose.onNodeWithText("Edit").performClick()
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
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithText("Sock Society").performClick()
        compose.runOnIdle { assertEquals(null, selected) }

        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Sock Society").performClick()
        compose.runOnIdle { assertEquals(2L, selected?.id) }
    }

    @Test
    fun `edit mode swaps the events badge for a leave control, restored on Done`() {
        var clicked: Group? = null
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = mapOf(1L to 3),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = { clicked = it },
                onSettingsClick = {},
            )
        }
        // In edit mode the events badge yields the trailing slot to the leave control (#231).
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithContentDescription("Upcoming events", useUnmergedTree = true).assertDoesNotExist()

        // Done restores the events badge, clickable again.
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Upcoming events", useUnmergedTree = true).performClick()
        compose.runOnIdle { assertEquals(1L, clicked?.id) }
    }

    @Test
    fun `leaving a group from edit mode confirms then invokes onLeaveGroup`() {
        var left: Group? = null
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                onLeaveGroup = { left = it },
            )
        }
        compose.onNodeWithText("Edit").performClick()
        // Tap the first group's leave control, then confirm.
        compose.onNodeWithContentDescription("Leave KAL Hub", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Leave").performClick()
        compose.runOnIdle { assertEquals(1L, left?.id) }
    }

    @Test
    fun `the leave dialog spins while leaving and auto-dismisses when it completes`() {
        // The VM sets leavingGroupId when the leave starts and clears it when done; drive
        // that transition to exercise the spinner + auto-dismiss (issue #231).
        var leavingGroupId by mutableStateOf<Long?>(null)
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                onLeaveGroup = { leavingGroupId = it.id },
                leavingGroupId = leavingGroupId,
            )
        }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithContentDescription("Leave KAL Hub", useUnmergedTree = true).performClick()
        // Confirming flips leavingGroupId → the dialog turns into a spinner; the confirm/cancel
        // buttons disappear so it can't be dismissed mid-leave.
        compose.onNodeWithText("Leave").performClick()
        compose.onNodeWithText("Leaving KAL Hub…").assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertDoesNotExist()

        // When the leave completes (leavingGroupId cleared), the dialog auto-dismisses.
        leavingGroupId = null
        compose.waitForIdle()
        compose.onNodeWithText("Leaving KAL Hub…").assertDoesNotExist()
        compose.onNodeWithText("Leave KAL Hub?").assertDoesNotExist()
    }

    @Test
    fun `a failed leave keeps the dialog open with the error and a Retry, instead of silently dismissing`() {
        // Issue #263: leaveError previously wasn't surfaced at all, so a non-session
        // failure looked identical to success — the dialog auto-dismissed either way.
        var leavingGroupId by mutableStateOf<Long?>(null)
        var leaveError by mutableStateOf<String?>(null)
        var attempts = 0
        var acknowledged = false
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                onLeaveGroup = {
                    attempts++
                    leavingGroupId = it.id
                },
                leavingGroupId = leavingGroupId,
                leaveError = leaveError,
                onAcknowledgeLeaveError = { acknowledged = true; leaveError = null },
            )
        }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithContentDescription("Leave KAL Hub", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Leave").performClick()

        // The leave finishes, but with an error rather than success.
        leavingGroupId = null
        leaveError = "Couldn't leave the group"
        compose.waitForIdle()

        // The dialog stays open showing the error — it does NOT silently dismiss.
        compose.onNodeWithText("Leave KAL Hub?").assertIsDisplayed()
        compose.onNodeWithText("Couldn't leave the group").assertIsDisplayed()

        // Retry re-invokes onLeaveGroup for the same group.
        compose.onNodeWithText("Retry").performClick()
        compose.runOnIdle { assertEquals(2, attempts) }
    }

    @Test
    fun `canceling a leave dialog with an error acknowledges it`() {
        var leaveError by mutableStateOf<String?>("Couldn't leave the group")
        var acknowledged = false
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                leaveError = leaveError,
                onAcknowledgeLeaveError = { acknowledged = true },
            )
        }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithContentDescription("Leave KAL Hub", useUnmergedTree = true).performClick()
        compose.onNodeWithText("Couldn't leave the group").assertIsDisplayed()

        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(true, acknowledged) }
        compose.onNodeWithText("Leave KAL Hub?").assertDoesNotExist()
    }

    @Test
    fun `opening the leave dialog for a different group acknowledges a stale error first`() {
        // Regression: leaveError lives on the ViewModel (outlives GroupDrawer being torn
        // down, e.g. by a deep link forcing the drawer closed while an error was showing),
        // but pendingLeave is drawer-local and always starts fresh. Without clearing
        // leaveError when a NEW group's dialog opens, a stale error left over from a
        // previous group's unresolved dialog would show up immediately for this group,
        // even though nothing has been attempted for it yet.
        var leaveError by mutableStateOf<String?>("Couldn't leave the group")
        var acknowledged = false
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                leaveError = leaveError,
                onAcknowledgeLeaveError = { acknowledged = true; leaveError = null },
            )
        }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithContentDescription("Leave Sock Society", useUnmergedTree = true).performClick()

        compose.runOnIdle { assertEquals(true, acknowledged) }
        compose.onNodeWithText("Leave Sock Society?").assertIsDisplayed()
        compose.onNodeWithText("Couldn't leave the group").assertDoesNotExist()
        compose.onNodeWithText("You'll stop seeing this group's topics. You can re-join it from Ravelry.")
            .assertIsDisplayed()
    }

    @Test
    fun `swiping down over the group list invokes onRefresh`() {
        // Issue #246: joining a group elsewhere left no way to refresh the drawer's list
        // short of leaving and re-entering the app. Pull-to-refresh matches every other
        // interactable list/drawer in the app (feed, events, topic detail) rather than a
        // one-off icon button.
        var refreshes = 0
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                onRefresh = { refreshes++ },
            )
        }
        compose.onNodeWithTag("GroupList").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, refreshes)
    }

    @Test
    fun `the group list stays visible and interactive while a refresh is in flight`() {
        var refreshing by mutableStateOf(false)
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
                isRefreshing = refreshing,
                onRefresh = { refreshing = true },
            )
        }
        compose.onNodeWithText("KAL Hub").assertIsDisplayed()
        refreshing = true
        compose.waitForIdle()
        compose.onNodeWithText("KAL Hub").assertIsDisplayed()
        compose.onNodeWithText("Sock Society").performClick()
        compose.runOnIdle { assertEquals(2L, selected?.id) }
    }

    @Test
    fun `pull-to-refresh is disabled during reorder mode`() {
        // A refresh mid-drag flips state away from Loaded, which makes reorderGroups()
        // silently no-op and drop the in-progress reorder — suppress the gesture instead.
        var refreshes = 0
        compose.setContent {
            GroupDrawer(
                groups = twoGroups,
                selectedGroup = twoGroups.first(),
                eventCounts = emptyMap(),
                user = user,
                onGroupSelected = {},
                onGroupEventsClick = {},
                onSettingsClick = {},
                onRefresh = { refreshes++ },
            )
        }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("GroupList").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(0, refreshes)

        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithTag("GroupList").performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals(1, refreshes)
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
