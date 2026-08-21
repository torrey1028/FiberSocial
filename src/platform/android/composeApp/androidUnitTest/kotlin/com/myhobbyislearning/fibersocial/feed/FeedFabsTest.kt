package com.myhobbyislearning.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.myhobbyislearning.fibersocial.feed.models.Group
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FeedFabsTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val kalHub = Group(id = 10, name = "KAL Hub", permalink = "kal-hub", forumId = 42)

    @Test
    fun `calendar button opens the selected group's events`() {
        var opened: Group? = null
        compose.setContent {
            FeedFabs(selectedGroup = kalHub, onGroupEventsClick = { opened = it }, onNewTopicClick = {})
        }
        compose.onNodeWithContentDescription("Group events").performClick()
        compose.runOnIdle { assertEquals(kalHub, opened) }
    }

    @Test
    fun `calendar button is hidden without a selected group`() {
        compose.setContent {
            FeedFabs(selectedGroup = null, onGroupEventsClick = {}, onNewTopicClick = {})
        }
        compose.onNodeWithContentDescription("Group events").assertDoesNotExist()
        compose.onNodeWithContentDescription("New topic").assertIsDisplayed()
    }

    @Test
    fun `new topic button still fires with the calendar button present`() {
        var newTopics = 0
        compose.setContent {
            FeedFabs(selectedGroup = kalHub, onGroupEventsClick = {}, onNewTopicClick = { newTopics++ })
        }
        compose.onNodeWithContentDescription("New topic").performClick()
        compose.runOnIdle { assertEquals(1, newTopics) }
    }

    @Test
    fun `both fabs show with the calendar stacked above the new-topic button`() {
        compose.setContent {
            FeedFabs(selectedGroup = kalHub, onGroupEventsClick = {}, onNewTopicClick = {})
        }
        compose.onNodeWithContentDescription("Group events").assertIsDisplayed()
        compose.onNodeWithContentDescription("New topic").assertIsDisplayed()
        // The calendar shortcut must sit above the primary new-topic FAB, not below or
        // overlapping it — the core layout intent a child-reorder could silently break.
        val calendarTop = compose.onNodeWithContentDescription("Group events").getUnclippedBoundsInRoot().top
        val newTopicTop = compose.onNodeWithContentDescription("New topic").getUnclippedBoundsInRoot().top
        assertTrue(calendarTop < newTopicTop, "calendar FAB ($calendarTop) should sit above new-topic FAB ($newTopicTop)")
    }

    // --- The subscribe bell (issue #510) ---

    @Test
    fun `the bell is hidden when group-activity notifications are off`() {
        // Null subscriptions is how FeedScreen expresses "the kind is off" — hidden, not
        // disabled: a toggle nothing acts on is worse than no toggle.
        compose.setContent {
            FeedFabs(
                selectedGroup = kalHub,
                subscribedGroupIds = null,
                onGroupEventsClick = {},
                onNewTopicClick = {},
            )
        }
        compose.onNodeWithContentDescription("Notify me about new posts in KAL Hub").assertDoesNotExist()
        compose.onNodeWithContentDescription("Group events").assertIsDisplayed()
    }

    @Test
    fun `an unsubscribed group offers to subscribe`() {
        var toggled: Pair<Group, Boolean>? = null
        compose.setContent {
            FeedFabs(
                selectedGroup = kalHub,
                subscribedGroupIds = emptySet(),
                onToggleSubscribe = { group, subscribe -> toggled = group to subscribe },
                onGroupEventsClick = {},
                onNewTopicClick = {},
            )
        }
        compose.onNodeWithContentDescription("Notify me about new posts in KAL Hub").performClick()
        compose.runOnIdle { assertEquals(kalHub to true, toggled) }
    }

    @Test
    fun `a subscribed group offers to unsubscribe`() {
        var toggled: Pair<Group, Boolean>? = null
        compose.setContent {
            FeedFabs(
                selectedGroup = kalHub,
                subscribedGroupIds = setOf(kalHub.id),
                onToggleSubscribe = { group, subscribe -> toggled = group to subscribe },
                onGroupEventsClick = {},
                onNewTopicClick = {},
            )
        }
        compose.onNodeWithContentDescription("Stop notifying me about new posts in KAL Hub").performClick()
        compose.runOnIdle { assertEquals(kalHub to false, toggled) }
    }

    @Test
    fun `the bell is hidden without a selected group`() {
        // My Posts is cross-group: there is nothing to subscribe to.
        compose.setContent {
            FeedFabs(
                selectedGroup = null,
                subscribedGroupIds = emptySet(),
                onGroupEventsClick = {},
                onNewTopicClick = {},
            )
        }
        compose.onNodeWithTag("SubscribeFab").assertDoesNotExist()
    }

    @Test
    fun `the bell stacks above the calendar and the new-topic button`() {
        compose.setContent {
            FeedFabs(
                selectedGroup = kalHub,
                subscribedGroupIds = emptySet(),
                onGroupEventsClick = {},
                onNewTopicClick = {},
            )
        }
        val bellTop = compose.onNodeWithTag("SubscribeFab").getUnclippedBoundsInRoot().top
        val calendarTop = compose.onNodeWithContentDescription("Group events").getUnclippedBoundsInRoot().top
        val newTopicTop = compose.onNodeWithContentDescription("New topic").getUnclippedBoundsInRoot().top
        assertTrue(bellTop < calendarTop, "bell ($bellTop) should sit above the calendar ($calendarTop)")
        assertTrue(calendarTop < newTopicTop, "calendar ($calendarTop) should sit above new topic ($newTopicTop)")
    }
}
