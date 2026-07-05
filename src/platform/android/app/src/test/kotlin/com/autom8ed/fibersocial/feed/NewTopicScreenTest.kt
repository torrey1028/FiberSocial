package com.autom8ed.fibersocial.feed

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Topic
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class NewTopicScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val kalHub = Group(id = 10, name = "KAL Hub", permalink = "kal-hub", forumId = 42)
    private val circle = Group(id = 11, name = "Fiber Circle", permalink = "fiber-circle", forumId = 43)

    @Test
    fun `post is disabled until group, title, and body are all present`() {
        compose.setContent {
            NewTopicScreen(
                groups = listOf(kalHub, circle),
                initialGroup = null,
                state = NewTopicState.Idle,
                onBack = {},
                onPost = { _, _, _, _ -> },
                onCreated = { _, _ -> },
            )
        }
        compose.onNodeWithText("Post").assertIsNotEnabled()
        compose.onNodeWithText("Title").performTextInput("Show us your WIPs")
        compose.onNodeWithText("Your post").performTextInput("Photos please!")
        // Still no group chosen.
        compose.onNodeWithText("Post").assertIsNotEnabled()
        compose.onNodeWithText("Group").performClick()
        compose.onNodeWithText("KAL Hub").performClick()
        compose.onNodeWithText("Post").assertIsEnabled()
    }

    @Test
    fun `drawer-selected group is preselected and post invokes onPost with it`() {
        data class Posted(val group: Group, val title: String, val body: String, val summary: String)
        var posted: Posted? = null
        compose.setContent {
            NewTopicScreen(
                groups = listOf(kalHub, circle),
                initialGroup = circle,
                state = NewTopicState.Idle,
                onBack = {},
                onPost = { group, title, body, summary -> posted = Posted(group, title, body, summary) },
                onCreated = { _, _ -> },
            )
        }
        compose.onNodeWithText("Fiber Circle").assertIsDisplayed()
        compose.onNodeWithText("Title").performTextInput("Show us your WIPs")
        compose.onNodeWithText("Summary (optional)").performTextInput("Weekly photo thread")
        compose.onNodeWithText("Your post").performTextInput("Photos please!")
        compose.onNodeWithText("Post").performClick()
        compose.runOnIdle {
            assertEquals(
                Posted(circle, "Show us your WIPs", "Photos please!", "Weekly photo thread"),
                posted,
            )
        }
    }

    @Test
    fun `summary is optional - post stays enabled and passes empty summary when left blank`() {
        var postedSummary: String? = null
        compose.setContent {
            NewTopicScreen(
                groups = listOf(kalHub),
                initialGroup = kalHub,
                state = NewTopicState.Idle,
                onBack = {},
                onPost = { _, _, _, summary -> postedSummary = summary },
                onCreated = { _, _ -> },
            )
        }
        compose.onNodeWithText("Title").performTextInput("Show us your WIPs")
        compose.onNodeWithText("Your post").performTextInput("Photos please!")
        // No summary typed — Post is still enabled and fires with an empty summary.
        compose.onNodeWithText("Post").assertIsEnabled()
        compose.onNodeWithText("Post").performClick()
        compose.runOnIdle { assertEquals("", postedSummary) }
    }

    @Test
    fun `Error state shows the failure message and keeps the fields`() {
        var state by mutableStateOf<NewTopicState>(NewTopicState.Idle)
        compose.setContent {
            NewTopicScreen(
                groups = listOf(kalHub),
                initialGroup = kalHub,
                state = state,
                onBack = {},
                onPost = { _, _, _, _ -> },
                onCreated = { _, _ -> },
            )
        }
        compose.onNodeWithText("Title").performTextInput("my precious title")
        compose.onNodeWithText("Your post").performTextInput("my precious draft")
        compose.runOnIdle { state = NewTopicState.Error("boom") }
        compose.waitForIdle()

        compose.onNodeWithText("boom").assertIsDisplayed()
        compose.onNodeWithText("my precious title").assertIsDisplayed()
        compose.onNodeWithText("my precious draft").assertIsDisplayed()
    }

    @Test
    fun `Created state invokes onCreated with the topic and chosen group`() {
        var created: Pair<Topic, Group>? = null
        var state by mutableStateOf<NewTopicState>(NewTopicState.Idle)
        compose.setContent {
            NewTopicScreen(
                groups = listOf(kalHub),
                initialGroup = kalHub,
                state = state,
                onBack = {},
                onPost = { _, _, _, _ -> },
                onCreated = { topic, group -> created = topic to group },
            )
        }
        val topic = Topic(id = 7001L, title = "Show us your WIPs")
        compose.runOnIdle { state = NewTopicState.Created(topic) }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(topic to kalHub, created) }
    }

    @Test
    fun `Sending state disables the fields and hides the post button`() {
        compose.setContent {
            NewTopicScreen(
                groups = listOf(kalHub),
                initialGroup = kalHub,
                state = NewTopicState.Sending,
                onBack = {},
                onPost = { _, _, _, _ -> },
                onCreated = { _, _ -> },
            )
        }
        compose.onNodeWithText("Title").assertIsNotEnabled()
        compose.onNodeWithText("Your post").assertIsNotEnabled()
        compose.onNodeWithText("Post").assertDoesNotExist()
        compose.onNodeWithContentDescription("Back").assertIsNotEnabled()
    }

    @Test
    fun `Idle state leaves the back button enabled`() {
        compose.setContent {
            NewTopicScreen(
                groups = listOf(kalHub),
                initialGroup = kalHub,
                state = NewTopicState.Idle,
                onBack = {},
                onPost = { _, _, _, _ -> },
                onCreated = { _, _ -> },
            )
        }
        compose.onNodeWithContentDescription("Back").assertIsEnabled()
    }
}
