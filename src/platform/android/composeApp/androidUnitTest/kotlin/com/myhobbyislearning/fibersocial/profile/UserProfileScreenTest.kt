package com.myhobbyislearning.fibersocial.profile

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.projects.ProjectLink
import com.myhobbyislearning.fibersocial.projects.ProjectPhoto
import com.myhobbyislearning.fibersocial.projects.ProjectSummary
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class UserProfileScreenTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val profile = UserProfile(
        id = 9, username = "yarnie", firstName = "Yarnie", location = "Seattle",
        aboutHtml = "<p>I knit socks</p>",
    )
    private val kalHub = Group(id = 10, name = "KAL Hub", permalink = "kal-hub", forumId = 42)
    private val withPhoto = ProjectSummary(
        id = 1, name = "Autumn Socks", permalink = "autumn-socks",
        firstPhoto = ProjectPhoto(id = 901, mediumUrl = "https://img/m.jpg"),
    )
    private val noPhoto = ProjectSummary(id = 2, name = "Cabled Hat", permalink = "cabled-hat")

    @Test
    fun `hidden state renders nothing`() {
        compose.setContent { UserProfileScreen(UserProfileState.Hidden, onBack = {}, onRetry = {}) }
        compose.onNodeWithText("Projects").assertDoesNotExist()
    }

    @Test
    fun `loaded state shows the header, projects, and groups`() {
        compose.setContent {
            UserProfileScreen(
                UserProfileState.Loaded(profile, listOf(withPhoto, noPhoto), listOf(kalHub)),
                onBack = {}, onRetry = {},
            )
        }
        compose.onNodeWithText("Yarnie").assertIsDisplayed()
        compose.onNodeWithText("Seattle").assertExists()
        compose.onNodeWithText("I knit socks").assertExists()
        compose.onNodeWithText("Projects").assertExists()
        compose.onNodeWithText("Groups").assertExists()
        compose.onNodeWithText("KAL Hub").assertExists()
        // The photoless project renders its name on the placeholder tile.
        compose.onNodeWithText("Cabled Hat").assertExists()
    }

    @Test
    fun `tapping a project invokes onOpenProject with an in-app project link`() {
        var opened: ProjectLink? = null
        compose.setContent {
            UserProfileScreen(
                UserProfileState.Loaded(profile, listOf(withPhoto), emptyList()),
                onBack = {}, onRetry = {}, onOpenProject = { opened = it },
            )
        }
        compose.onNodeWithContentDescription("Autumn Socks").performClick()
        compose.runOnIdle { assertEquals(ProjectLink("yarnie", "autumn-socks"), opened) }
    }

    @Test
    fun `tapping a group invokes onGroupClick`() {
        var clicked: Group? = null
        compose.setContent {
            UserProfileScreen(
                UserProfileState.Loaded(profile, emptyList(), listOf(kalHub)),
                onBack = {}, onRetry = {}, onGroupClick = { clicked = it },
            )
        }
        compose.onNodeWithText("KAL Hub").performClick()
        compose.runOnIdle { assertEquals(kalHub, clicked) }
    }

    @Test
    fun `empty projects and groups shows the empty note`() {
        compose.setContent {
            UserProfileScreen(
                UserProfileState.Loaded(profile, emptyList(), emptyList()),
                onBack = {}, onRetry = {},
            )
        }
        compose.onNodeWithText("No public projects or groups to show.").assertExists()
    }

    @Test
    fun `error state offers retry`() {
        var retries = 0
        compose.setContent {
            UserProfileScreen(
                UserProfileState.Error("yarnie", "private profile"),
                onBack = {}, onRetry = { retries++ },
            )
        }
        compose.onNodeWithText("private profile").assertIsDisplayed()
        compose.onNodeWithText("Try again").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test
    fun `back arrow invokes onBack`() {
        var backs = 0
        compose.setContent {
            UserProfileScreen(UserProfileState.Loading("yarnie"), onBack = { backs++ }, onRetry = {})
        }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }
}
