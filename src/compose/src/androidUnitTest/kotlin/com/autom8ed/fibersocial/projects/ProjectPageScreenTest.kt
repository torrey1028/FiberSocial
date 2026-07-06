package com.autom8ed.fibersocial.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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

    private fun screen(
        state: ProjectPageState,
        commentsState: ProjectCommentsState = ProjectCommentsState.Loaded(emptyList()),
        postState: CommentPostState = CommentPostState.Idle,
        pattern: PatternInfo? = null,
        currentUsername: String? = null,
        onBack: () -> Unit = {},
        onRetry: () -> Unit = {},
        onPostComment: (String) -> Unit = {},
        onPostErrorShown: () -> Unit = {},
        onDeleteComment: (ProjectComment) -> Unit = {},
    ): @androidx.compose.runtime.Composable () -> Unit = {
        ProjectPageScreen(
            state = state,
            commentsState = commentsState,
            postState = postState,
            pattern = pattern,
            currentUsername = currentUsername,
            onBack = onBack,
            onRetry = onRetry,
            onPostComment = onPostComment,
            onPostErrorShown = onPostErrorShown,
            onDeleteComment = onDeleteComment,
        )
    }

    @Test
    fun `hidden state renders nothing`() {
        compose.setContent { screen(ProjectPageState.Hidden)() }
        compose.onNodeWithText("Project").assertDoesNotExist()
    }

    @Test
    fun `loaded state shows the project facts and rendered notes`() {
        compose.setContent { screen(ProjectPageState.Loaded(link, project))() }
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
        compose.setContent { screen(ProjectPageState.Error(link, "private project"), onRetry = { retries++ })() }
        compose.onNodeWithText("private project").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `pattern name links out and shows the designer when the pattern resolved`() {
        var opened: String? = null
        val pattern = PatternInfo(id = 5, name = "Vanilla Socks", permalink = "vanilla-socks",
            author = PatternAuthor(name = "Jane Designer"))
        compose.setContent {
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalUriHandler provides object : androidx.compose.ui.platform.UriHandler {
                    override fun openUri(uri: String) { opened = uri }
                },
            ) { screen(ProjectPageState.Loaded(link, project), pattern = pattern)() }
        }
        compose.onNodeWithText("Vanilla Socks").performClick()
        compose.runOnIdle { assertEquals("https://www.ravelry.com/patterns/library/vanilla-socks", opened) }
        compose.onNodeWithText("by Jane Designer").assertExists()
    }

    @Test
    fun `posting a comment invokes the callback and comments render`() {
        var posted: String? = null
        val comments = ProjectCommentsState.Loaded(
            listOf(ProjectComment(id = 1, commentHtml = "<p>lovely work</p>",
                user = com.autom8ed.fibersocial.feed.models.RavelryUser(username = "fan"))),
        )
        compose.setContent {
            screen(
                ProjectPageState.Loaded(link, project),
                commentsState = comments,
                onPostComment = { posted = it },
            )()
        }
        compose.onNodeWithText("lovely work").assertExists()
        compose.onNodeWithText("@fan").assertExists()
        compose.onNodeWithText("Add a comment…").performTextInput("nice!")
        compose.onNodeWithContentDescription("Post comment").performClick()
        compose.runOnIdle { assertEquals("nice!", posted) }
    }

    @Test
    fun `a comment-permission error is shown`() {
        compose.setContent {
            screen(
                ProjectPageState.Loaded(link, project),
                postState = CommentPostState.Error(ProjectPageViewModel.COMMENT_PERMISSION_MESSAGE),
            )()
        }
        compose.onNodeWithText(ProjectPageViewModel.COMMENT_PERMISSION_MESSAGE).assertExists()
    }

    @Test
    fun `tapping a photo opens the full-screen viewer`() {
        compose.setContent { screen(ProjectPageState.Loaded(link, project))() }
        // Two "Project photo" nodes would exist once the viewer is open (thumb + full).
        compose.onAllNodesWithContentDescription("Project photo").assertCountEquals(1)
        compose.onAllNodesWithContentDescription("Project photo")[0].performClick()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun `own comment shows a delete affordance that confirms before deleting`() {
        var deleted: ProjectComment? = null
        val mine = ProjectComment(id = 1, commentHtml = "<p>my comment</p>",
            user = com.autom8ed.fibersocial.feed.models.RavelryUser(username = "yarnie"))
        compose.setContent {
            screen(
                // Minimal project so the comment sits near the top (on-screen).
                ProjectPageState.Loaded(link, project.copy(photos = emptyList(), notes = null)),
                commentsState = ProjectCommentsState.Loaded(listOf(mine)),
                currentUsername = "yarnie",
                onDeleteComment = { deleted = it },
            )()
        }
        compose.onNodeWithContentDescription("Delete comment").performClick()
        compose.waitForIdle()
        // Confirmation required first.
        compose.onNodeWithText("Delete this comment?").assertExists()
        compose.runOnIdle { assertEquals(null, deleted) }
        compose.onNodeWithText("Delete").performClick()
        compose.runOnIdle { assertEquals(1L, deleted?.id) }
    }

    @Test
    fun `others comments have no delete affordance`() {
        val theirs = ProjectComment(id = 2, commentHtml = "<p>hi</p>",
            user = com.autom8ed.fibersocial.feed.models.RavelryUser(username = "someone-else"))
        compose.setContent {
            screen(
                ProjectPageState.Loaded(link, project.copy(photos = emptyList(), notes = null)),
                commentsState = ProjectCommentsState.Loaded(listOf(theirs)),
                currentUsername = "yarnie",
            )()
        }
        compose.onNodeWithContentDescription("Delete comment").assertDoesNotExist()
    }

    @Test
    fun `no delete affordance when the signed-in user is unknown`() {
        val mine = ProjectComment(id = 1, commentHtml = "<p>hi</p>",
            user = com.autom8ed.fibersocial.feed.models.RavelryUser(username = "yarnie"))
        compose.setContent {
            screen(
                ProjectPageState.Loaded(link, project.copy(photos = emptyList(), notes = null)),
                commentsState = ProjectCommentsState.Loaded(listOf(mine)),
                currentUsername = null,
            )()
        }
        compose.onNodeWithContentDescription("Delete comment").assertDoesNotExist()
    }

    @Test
    fun `full-screen viewer shows a page counter for multiple photos`() {
        val multi = project.copy(
            photos = listOf(
                ProjectPhoto(id = 1, medium2Url = "https://img/1.jpg"),
                ProjectPhoto(id = 2, medium2Url = "https://img/2.jpg"),
            ),
        )
        compose.setContent { screen(ProjectPageState.Loaded(link, multi))() }
        compose.onAllNodesWithContentDescription("Project photo")[0].performClick()
        compose.onNodeWithText("1 / 2").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close").assertIsDisplayed()
    }

    @Test
    fun `back arrow and system back invoke onBack`() {
        var backs = 0
        compose.setContent { screen(ProjectPageState.Loading(link), onBack = { backs++ })() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.runOnIdle { assertEquals(2, backs) }
    }
}
