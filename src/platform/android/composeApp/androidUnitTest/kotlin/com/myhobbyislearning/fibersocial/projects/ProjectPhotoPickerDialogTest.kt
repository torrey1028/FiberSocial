package com.myhobbyislearning.fibersocial.projects

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
class ProjectPhotoPickerDialogTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val socks = ProjectSummary(
        id = 1L,
        name = "Autumn Socks",
        permalink = "autumn-socks",
        firstPhoto = ProjectPhoto(id = 901L, squareUrl = "https://img.example/sq.jpg"),
        photosCount = 2,
    )
    private val photos = listOf(
        ProjectPhoto(id = 901L, smallUrl = "https://img.example/s1.jpg"),
        ProjectPhoto(id = 902L, smallUrl = "https://img.example/s2.jpg"),
    )

    private fun setDialog(
        state: ProjectPickerState,
        onProjectSelected: (ProjectSummary) -> Unit = {},
        onPhotoPicked: (ProjectSummary, ProjectPhoto) -> Unit = { _, _ -> },
        onBackToProjects: () -> Unit = {},
        onDismiss: () -> Unit = {},
    ) {
        compose.setContent {
            ProjectPhotoPickerDialog(
                state = state,
                onProjectSelected = onProjectSelected,
                onPhotoPicked = onPhotoPicked,
                onBackToProjects = onBackToProjects,
                onDismiss = onDismiss,
            )
        }
    }

    @Test
    fun `Hidden renders nothing`() {
        setDialog(ProjectPickerState.Hidden)
        compose.onNodeWithText("Cancel").assertDoesNotExist()
    }

    @Test
    fun `project list shows names and photo counts and selects on tap`() {
        var selected: ProjectSummary? = null
        setDialog(
            ProjectPickerState.ProjectList(listOf(socks)),
            onProjectSelected = { selected = it },
        )
        compose.onNodeWithText("Autumn Socks").assertIsDisplayed()
        compose.onNodeWithText("2 photos").assertIsDisplayed()
        compose.onNodeWithText("Autumn Socks").performClick()
        compose.runOnIdle { assertEquals(socks, selected) }
    }

    @Test
    fun `empty project list explains itself`() {
        setDialog(ProjectPickerState.ProjectList(emptyList()))
        compose.onNodeWithText("None of your projects have photos yet.").assertIsDisplayed()
    }

    @Test
    fun `photo grid picks a photo on tap`() {
        var picked: Pair<ProjectSummary, ProjectPhoto>? = null
        setDialog(
            ProjectPickerState.PhotoGrid(socks, photos),
            onPhotoPicked = { project, photo -> picked = project to photo },
        )
        compose.onAllNodesWithContentDescription("Photo from Autumn Socks")[0].performClick()
        compose.runOnIdle { assertEquals(socks to photos[0], picked) }
    }

    @Test
    fun `empty photo grid explains itself instead of showing a blank panel`() {
        setDialog(ProjectPickerState.PhotoGrid(socks, emptyList()))
        compose.onNodeWithText("This project has no photos to pick.").assertIsDisplayed()
    }

    @Test
    fun `photo grid title navigates back to the project list`() {
        var backs = 0
        setDialog(
            ProjectPickerState.PhotoGrid(socks, photos),
            onBackToProjects = { backs++ },
        )
        compose.onNodeWithContentDescription("Back to projects").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    @Test
    fun `error state shows the message and Cancel dismisses`() {
        var dismissed = 0
        setDialog(ProjectPickerState.Error("boom"), onDismiss = { dismissed++ })
        compose.onNodeWithText("boom").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle { assertEquals(1, dismissed) }
    }
}
