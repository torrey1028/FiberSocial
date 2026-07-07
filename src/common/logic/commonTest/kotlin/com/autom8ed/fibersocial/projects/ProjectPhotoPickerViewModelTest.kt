package com.autom8ed.fibersocial.projects

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.FakeFeedTokenStorage
import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.feed.errorApiClient
import com.autom8ed.fibersocial.feed.nullMessageApiClient
import com.autom8ed.fibersocial.feed.routingApiClient
import com.autom8ed.fibersocial.feed.suspendableRoutingApiClient
import com.autom8ed.fibersocial.feed.sessionExpiredApiClient
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class ProjectPhotoPickerViewModelTest {
    private suspend fun awaitChildren(job: Job) =
        job.children.toList().forEach { it.join() }

    private fun projectsApiClient(): RavelryApiClient = routingApiClient { path ->
        when {
            path.endsWith("/list.json") -> PROJECTS_JSON
            else -> PROJECT_DETAIL_JSON
        }
    }

    @Test
    fun `initial state is Hidden`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        assertIs<ProjectPickerState.Hidden>(vm.state.value)
    }

    @Test
    fun `open loads projects and drops the photoless ones`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val state = assertIs<ProjectPickerState.ProjectList>(vm.state.value)
        // "Empty Cowl" has photos_count 0; the nameless project 3 keeps its defaults.
        assertEquals(listOf(1L, 3L), state.projects.map { it.id })
        assertEquals(listOf("Autumn Socks", ""), state.projects.map { it.name })
    }

    @Test
    fun `selectProject loads the photo grid and drops photos with no usable URL`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val projects = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects
        vm.selectProject(projects.first())
        awaitChildren(coroutineContext[Job]!!)
        val grid = assertIs<ProjectPickerState.PhotoGrid>(vm.state.value)
        // The fixture's third photo is a bare `{}` (no URLs, id defaults to 0); it is
        // filtered out so every remaining tile is insertable and no two share key 0.
        assertEquals(listOf(901L, 902L), grid.photos.map { it.id })
    }

    @Test
    fun `selectProject yields an empty grid when no photo has a usable URL`() = runTest(UnconfinedTestDispatcher()) {
        val client = routingApiClient { path ->
            if (path.endsWith("/list.json")) PROJECTS_JSON
            else """{"project":{"id":1,"name":"Autumn Socks","photos":[{},{"id":902}]}}"""
        }
        val vm = ProjectPhotoPickerViewModel(client, this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val projects = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects
        vm.selectProject(projects.first())
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(emptyList(), assertIs<ProjectPickerState.PhotoGrid>(vm.state.value).photos)
    }

    @Test
    fun `backToProjects returns to the loaded list without refetching`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val projects = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects
        vm.selectProject(projects.first())
        awaitChildren(coroutineContext[Job]!!)
        vm.backToProjects()
        val state = assertIs<ProjectPickerState.ProjectList>(vm.state.value)
        assertEquals(projects, state.projects)
    }

    @Test
    fun `dismiss hides the dialog and a late load result is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        vm.open("yarnie")
        vm.dismiss()
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPickerState.Hidden>(vm.state.value)
    }

    @Test
    fun `load failure reports the error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(errorApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPickerState.Error>(vm.state.value)
    }

    @Test
    fun `session expiry hides the dialog and signals sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(sessionExpiredApiClient(FakeFeedTokenStorage()), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPickerState.Hidden>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `load failure without a message uses a fallback`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(nullMessageApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("Failed to load your projects", assertIs<ProjectPickerState.Error>(vm.state.value).message)
    }

    @Test
    fun `a project load that outlives its dialog is ignored`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val client = suspendableRoutingApiClient { url ->
            gate.await()
            PROJECTS_JSON
        }
        val vm = ProjectPhotoPickerViewModel(client, this)
        vm.open("yarnie")
        assertIs<ProjectPickerState.LoadingProjects>(vm.state.value)
        vm.dismiss()
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        // The load finished after dismissal; the dialog must not pop back open.
        assertIs<ProjectPickerState.Hidden>(vm.state.value)
    }

    @Test
    fun `a session expiry from a dismissed load does not signal sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val client = suspendableRoutingApiClient { _ ->
            gate.await()
            throw SessionExpiredException("Token expired")
        }
        val vm = ProjectPhotoPickerViewModel(client, this)
        vm.open("yarnie")
        assertIs<ProjectPickerState.LoadingProjects>(vm.state.value)
        vm.dismiss()          // supersedes the load
        gate.complete(Unit)   // the now-stale load throws SessionExpiredException
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPickerState.Hidden>(vm.state.value)
        // A load the user already dismissed must not yank them to the login screen.
        assertEquals(null, withTimeoutOrNull(1_000) { vm.sessionExpired.first() })
    }

    @Test
    fun `a photo load that outlives its grid is ignored after backing out`() = runTest(UnconfinedTestDispatcher()) {
        val gate = CompletableDeferred<Unit>()
        val client = suspendableRoutingApiClient { url ->
            if (!url.encodedPath.endsWith("/list.json")) gate.await()
            if (url.encodedPath.endsWith("/list.json")) PROJECTS_JSON else PROJECT_DETAIL_JSON
        }
        val vm = ProjectPhotoPickerViewModel(client, this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val projects = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects
        vm.selectProject(projects.first())
        assertIs<ProjectPickerState.LoadingPhotos>(vm.state.value)
        vm.backToProjects()
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)
        // The stale photo grid must not clobber the list the user backed out to.
        assertIs<ProjectPickerState.ProjectList>(vm.state.value)
    }

    @Test
    fun `selectProject failure reports the error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(
            routingApiClient { path -> if (path.endsWith("/list.json")) PROJECTS_JSON else error("boom") },
            this,
        )
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val projects = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects
        vm.selectProject(projects.first())
        awaitChildren(coroutineContext[Job]!!)
        assertEquals("boom", assertIs<ProjectPickerState.Error>(vm.state.value).message)
    }

    @Test
    fun `selectProject failure without a message uses a fallback`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(
            routingApiClient { path ->
                if (path.endsWith("/list.json")) PROJECTS_JSON else throw RuntimeException()
            },
            this,
        )
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val projects = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects
        vm.selectProject(projects.first())
        awaitChildren(coroutineContext[Job]!!)
        assertEquals(
            "Failed to load the project's photos",
            assertIs<ProjectPickerState.Error>(vm.state.value).message,
        )
    }

    @Test
    fun `selectProject session expiry hides the dialog and signals sessionExpired`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(
            routingApiClient { path ->
                if (path.endsWith("/list.json")) PROJECTS_JSON
                else throw com.autom8ed.fibersocial.auth.SessionExpiredException("expired")
            },
            this,
        )
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val projects = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects
        vm.selectProject(projects.first())
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPickerState.Hidden>(vm.state.value)
        assertEquals(Unit, vm.sessionExpired.first())
    }

    @Test
    fun `selectProject before open is a no-op`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(errorApiClient(), this)
        vm.selectProject(ProjectSummary(id = 1L, name = "Socks"))
        awaitChildren(coroutineContext[Job]!!)
        assertIs<ProjectPickerState.Hidden>(vm.state.value)
    }

    @Test
    fun `model constructors default every optional field`() {
        val barePhoto = ProjectPhoto()
        assertEquals(0L, barePhoto.id)
        assertEquals(null, barePhoto.squareUrl)
        assertEquals(null, barePhoto.medium2Url)
        val partialPhoto = ProjectPhoto(id = 1L, squareUrl = "sq", thumbnailUrl = "th", smallUrl = "s", mediumUrl = "m")
        assertEquals(null, partialPhoto.medium2Url)
        assertEquals("m2", ProjectPhoto(medium2Url = "m2").medium2Url)

        val bareProject = ProjectSummary(id = 1L)
        assertEquals("", bareProject.name)
        assertEquals("", bareProject.permalink)
        assertEquals(null, bareProject.firstPhoto)
        assertEquals(0, bareProject.photosCount)
        val partialProject = ProjectSummary(id = 2L, name = "n", permalink = "p", firstPhoto = barePhoto)
        assertEquals(0, partialProject.photosCount)
        assertEquals(3, ProjectSummary(id = 3L, photosCount = 3).photosCount)
    }

    @Test
    fun `photo url fallbacks prefer larger sizes for embedding and smaller for the grid`() {
        val full = ProjectPhoto(
            id = 1L,
            squareUrl = "sq", thumbnailUrl = "th", smallUrl = "s", mediumUrl = "m", medium2Url = "m2",
        )
        assertEquals("m2", full.embedUrl)
        assertEquals("s", full.gridUrl)
        assertEquals("m", ProjectPhoto(id = 2L, mediumUrl = "m", smallUrl = "s").embedUrl)
        assertEquals("s", ProjectPhoto(id = 3L, smallUrl = "s", thumbnailUrl = "th").embedUrl)
        assertEquals("th", ProjectPhoto(id = 4L, thumbnailUrl = "th", squareUrl = "sq").embedUrl)
        assertEquals("sq", ProjectPhoto(id = 5L, squareUrl = "sq").embedUrl)
        assertEquals(null, ProjectPhoto(id = 6L).embedUrl)
        assertEquals("th", ProjectPhoto(id = 7L, thumbnailUrl = "th", mediumUrl = "m").gridUrl)
        assertEquals("sq", ProjectPhoto(id = 8L, squareUrl = "sq", medium2Url = "m2").gridUrl)
        assertEquals("m2", ProjectPhoto(id = 9L, medium2Url = "m2").gridUrl)
        assertEquals(null, ProjectPhoto(id = 10L).gridUrl)
        // A blank URL is skipped, not treated as present: Ravelry sometimes serves ""
        // for a size it hasn't generated, and stopping there would yield a broken image.
        assertEquals("m", ProjectPhoto(id = 11L, medium2Url = "", mediumUrl = "m").embedUrl)
        assertEquals(null, ProjectPhoto(id = 12L, medium2Url = "", mediumUrl = "").embedUrl)
    }

    @Test
    fun `markdownFor links the largest medium image to the project page`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val project = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects.first()
        val photo = ProjectPhoto(id = 901L, mediumUrl = "https://img.example/m.jpg")
        assertEquals(
            "[![Autumn Socks](https://img.example/m.jpg)](https://www.ravelry.com/projects/yarnie/autumn-socks)",
            vm.markdownFor(project, photo),
        )
    }

    @Test
    fun `markdownFor falls back to a bare image when the project has no permalink`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val project = ProjectSummary(id = 1L, name = "Autumn Socks", permalink = "")
        val photo = ProjectPhoto(id = 901L, smallUrl = "https://img.example/s.jpg")
        assertEquals("![Autumn Socks](https://img.example/s.jpg)", vm.markdownFor(project, photo))
    }

    @Test
    fun `markdownFor before open falls back to a bare image`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(errorApiClient(), this)
        val project = ProjectSummary(id = 1L, name = "Socks", permalink = "socks")
        val photo = ProjectPhoto(id = 901L, mediumUrl = "https://img.example/m.jpg")
        // No username yet — a project link can't be built, but the image still can.
        assertEquals("![Socks](https://img.example/m.jpg)", vm.markdownFor(project, photo))
    }

    @Test
    fun `markdownFor returns null for a photo with no URLs`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        val project = assertIs<ProjectPickerState.ProjectList>(vm.state.value).projects.first()
        assertEquals(null, vm.markdownFor(project, ProjectPhoto(id = 901L)))
    }

    @Test
    fun `markdownFor strips markdown-breaking characters from the project name`() = runTest(UnconfinedTestDispatcher()) {
        val vm = ProjectPhotoPickerViewModel(projectsApiClient(), this)
        vm.open("yarnie")
        awaitChildren(coroutineContext[Job]!!)
        // Brackets/parens in the name would close the ![alt](url) syntax early.
        val project = ProjectSummary(id = 1L, name = "Cowl [WIP] (v2)", permalink = "cowl")
        val photo = ProjectPhoto(id = 901L, mediumUrl = "https://img.example/m.jpg")
        assertEquals(
            "[![Cowl WIP v2](https://img.example/m.jpg)](https://www.ravelry.com/projects/yarnie/cowl)",
            vm.markdownFor(project, photo),
        )
    }
}

// Field variety is deliberate: the third project and third photo omit optional
// fields so the models' deserialization defaults are exercised, not just the
// all-fields-present path.
private const val PROJECTS_JSON = """{"projects":[
  {"id":1,"name":"Autumn Socks","permalink":"autumn-socks",
   "first_photo":{"id":901,"square_url":"https://img.example/sq.jpg"},"photos_count":2},
  {"id":2,"name":"Empty Cowl","permalink":"empty-cowl","first_photo":null,"photos_count":0},
  {"id":3,"photos_count":1},
  {"id":4,"name":"No Count Hat"}
]}"""

private const val PROJECT_DETAIL_JSON = """{"project":{"id":1,"name":"Autumn Socks","photos":[
  {"id":901,"square_url":"https://img.example/sq1.jpg","thumbnail_url":"https://img.example/t1.jpg",
   "small_url":"https://img.example/s1.jpg","medium_url":"https://img.example/m1.jpg",
   "medium2_url":"https://img.example/m21.jpg"},
  {"id":902,"square_url":"https://img.example/sq2.jpg","medium_url":"https://img.example/m2.jpg"},
  {}
]}}"""
