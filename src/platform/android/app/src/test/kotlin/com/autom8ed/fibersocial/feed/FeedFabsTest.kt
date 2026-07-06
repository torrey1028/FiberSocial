package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.autom8ed.fibersocial.feed.models.Group
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

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
}
