package com.autom8ed.fibersocial.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ProjectPageScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val link = ProjectLink("yarnie", "autumn-socks")
    private val project = ProjectDetail(
        id = 7L,
        name = "Autumn Socks",
        permalink = "autumn-socks",
        patternName = "Vanilla Socks",
        statusName = "In progress",
        progress = 60,
        craftName = "Knitting",
        notes = "So *cozy* and warm",
        photos = listOf(ProjectPhoto(id = 901L, mediumUrl = "https://img.example/m1.jpg")),
    )

    @Test
    fun `hidden state renders nothing`() {
        compose.setContent {
            ProjectPageScreen(state = ProjectPageState.Hidden, onBack = {}, onRetry = {})
        }
        compose.onNodeWithText("Project").assertDoesNotExist()
    }

    @Test
    fun `loaded state shows the project facts and rendered notes`() {
        compose.setContent {
            ProjectPageScreen(state = ProjectPageState.Loaded(link, project), onBack = {}, onRetry = {})
        }
        // Title is in the app bar; the rest lives in a vertical scroll, so assert they
        // exist in the tree rather than that they're on-screen in the test viewport.
        compose.onNodeWithText("Autumn Socks").assertIsDisplayed()
        compose.onNodeWithText("by yarnie").assertExists()
        compose.onNodeWithText("Vanilla Socks").assertExists()
        compose.onNodeWithText("In progress · 60%").assertExists()
        compose.onNodeWithText("Knitting").assertExists()
        // Markdown notes render styled, without the asterisks.
        compose.onNodeWithText("So cozy and warm").assertExists()
        compose.onNodeWithText("Open on Ravelry").assertExists()
        compose.onNodeWithContentDescription("Project photo").assertExists()
    }

    @Test
    fun `error state offers retry`() {
        var retries = 0
        compose.setContent {
            ProjectPageScreen(
                state = ProjectPageState.Error(link, "private project"),
                onBack = {},
                onRetry = { retries++ },
            )
        }
        compose.onNodeWithText("private project").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `back arrow and system back invoke onBack`() {
        var backs = 0
        compose.setContent {
            ProjectPageScreen(state = ProjectPageState.Loading(link), onBack = { backs++ }, onRetry = {})
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(2, backs) }
    }
}
