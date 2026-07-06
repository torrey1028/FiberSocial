package com.autom8ed.fibersocial.projects

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.RavelryApiClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * One photo on a Ravelry project, at the sizes the API serves.
 * Ravelry photo hosting for projects is free — unlike forum-post attachment
 * hosting, which needs a Ravelry Extras subscription.
 */
@Serializable
data class ProjectPhoto(
    val id: Long = 0,
    /** Tiny 75×75 square thumbnail. */
    @SerialName("square_url") val squareUrl: String? = null,
    /** Thumbnail, 100px on the longest side. */
    @SerialName("thumbnail_url") val thumbnailUrl: String? = null,
    /** Small image, 240px on the longest side. */
    @SerialName("small_url") val smallUrl: String? = null,
    /** Medium image, 500px on the longest side. */
    @SerialName("medium_url") val mediumUrl: String? = null,
    /** Medium2 image, 640px on the longest side. */
    @SerialName("medium2_url") val medium2Url: String? = null,
) {
    /** Best URL to embed in a post body: the largest medium size available. */
    val embedUrl: String? get() = firstNonBlank(medium2Url, mediumUrl, smallUrl, thumbnailUrl, squareUrl)

    /** Best URL for a picker grid thumbnail. */
    val gridUrl: String? get() = firstNonBlank(smallUrl, thumbnailUrl, squareUrl, mediumUrl, medium2Url)
}

// Skips blanks, not just nulls: Ravelry occasionally serves an empty string for
// a size it hasn't generated, and a plain elvis chain would stop at "" and yield
// a broken empty-URL image reference instead of falling through to a real size.
private fun firstNonBlank(vararg candidates: String?): String? =
    candidates.firstOrNull { !it.isNullOrBlank() }

/**
 * A project as returned by `projects/{username}/list.json` — just enough to
 * render a picker row and fetch its photos.
 */
@Serializable
data class ProjectSummary(
    val id: Long,
    val name: String = "",
    val permalink: String = "",
    @SerialName("first_photo") val firstPhoto: ProjectPhoto? = null,
    @SerialName("photos_count") val photosCount: Int = 0,
)

/**
 * State of the pick-a-project-photo dialog. The dialog is [Hidden] until a composer's
 * attach menu opens it.
 */
sealed class ProjectPickerState {
    /** Dialog not shown. */
    object Hidden : ProjectPickerState()

    /** Fetching the user's project list. */
    object LoadingProjects : ProjectPickerState()

    /**
     * Choosing among the user's projects (only those with photos).
     * @property projects Projects to offer, in the API's order.
     */
    data class ProjectList(val projects: List<ProjectSummary>) : ProjectPickerState()

    /** Fetching [project]'s photos. */
    data class LoadingPhotos(val project: ProjectSummary) : ProjectPickerState()

    /**
     * Choosing among [project]'s photos.
     * @property photos All photos on the project.
     */
    data class PhotoGrid(val project: ProjectSummary, val photos: List<ProjectPhoto>) : ProjectPickerState()

    /**
     * A fetch failed; the dialog stays up so the user can dismiss (or reopen to retry).
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : ProjectPickerState()
}

/**
 * Drives the pick-a-photo-from-your-projects dialog: loads the signed-in user's
 * projects, then a chosen project's photos, and builds the markdown a composer
 * inserts for the picked photo.
 *
 * This is the free alternative to uploading a device photo — project photo
 * hosting doesn't need a Ravelry Extras subscription, so the resulting markdown
 * simply references the photo's existing Ravelry URL (linked to the project,
 * matching what the website's own picker inserts).
 *
 * @param apiClient Used to fetch projects and photos.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class ProjectPhotoPickerViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ProjectPickerState>(ProjectPickerState.Hidden)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable dialog state. */
    val state: StateFlow<ProjectPickerState> = _state.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    private var username: String? = null
    private var loadedProjects: List<ProjectSummary> = emptyList()

    /** A monotonically increasing token; results from a dismissed dialog may not surface. */
    private var generation = 0

    /**
     * Opens the dialog and loads [username]'s projects. Projects without photos are
     * dropped — there'd be nothing to pick. Always refetches: the list is one cheap
     * call and the user may have just added photos on the website.
     */
    fun open(username: String) {
        this.username = username
        _state.value = ProjectPickerState.LoadingProjects
        val gen = ++generation
        scope.launch {
            try {
                val projects = apiClient.getProjects(username).filter { it.photosCount > 0 }
                println("FiberSocial: ProjectPhotoPicker loaded ${projects.size} projects with photos")
                if (gen == generation) {
                    loadedProjects = projects
                    _state.value = ProjectPickerState.ProjectList(projects)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: ProjectPhotoPicker.open session expired")
                // Guarded like the state write: a load from a dismissed/superseded
                // dialog must not yank the user to the login screen.
                if (gen == generation) {
                    _state.value = ProjectPickerState.Hidden
                    _sessionExpired.trySend(Unit)
                }
            } catch (e: Exception) {
                println("FiberSocial: ProjectPhotoPicker.open error: ${e.message}")
                if (gen == generation) {
                    _state.value = ProjectPickerState.Error(e.message ?: "Failed to load your projects")
                }
            }
        }
    }

    /** Loads [project]'s photos and advances the dialog to the photo grid. */
    fun selectProject(project: ProjectSummary) {
        val owner = username ?: return
        _state.value = ProjectPickerState.LoadingPhotos(project)
        val gen = ++generation
        scope.launch {
            try {
                // Drop photos with no usable URL so every tile in the grid is
                // insertable — a tap can't then silently dismiss with nothing added.
                val photos = apiClient.getProjectPhotos(owner, project.id).filter { it.embedUrl != null }
                println("FiberSocial: ProjectPhotoPicker loaded ${photos.size} photos for project ${project.id}")
                if (gen == generation) {
                    _state.value = ProjectPickerState.PhotoGrid(project, photos)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: ProjectPhotoPicker.selectProject session expired")
                if (gen == generation) {
                    _state.value = ProjectPickerState.Hidden
                    _sessionExpired.trySend(Unit)
                }
            } catch (e: Exception) {
                println("FiberSocial: ProjectPhotoPicker.selectProject error: ${e.message}")
                if (gen == generation) {
                    _state.value = ProjectPickerState.Error(e.message ?: "Failed to load the project's photos")
                }
            }
        }
    }

    /** Returns from the photo grid to the already-loaded project list. */
    fun backToProjects() {
        generation++
        _state.value = ProjectPickerState.ProjectList(loadedProjects)
    }

    /** Closes the dialog. In-flight loads are ignored when they land. */
    fun dismiss() {
        generation++
        _state.value = ProjectPickerState.Hidden
    }

    /**
     * Markdown a composer inserts for [photo]: the image linked to its project page,
     * matching the `[![alt](img)](project)` shape the website's own picker inserts.
     * Returns `null` if the photo has no usable URL.
     */
    fun markdownFor(project: ProjectSummary, photo: ProjectPhoto): String? {
        val imageUrl = photo.embedUrl ?: return null
        // Project names are user-controlled; a `[`, `]`, or `)` in the name would
        // close the ![alt](url) / [image](link) syntax early and break rendering.
        // The alt text is cosmetic, so drop those structural characters from it.
        val alt = project.name.replace(Regex("""[\[\]()]"""), "")
        val image = "![$alt]($imageUrl)"
        val owner = username
        if (owner.isNullOrBlank() || project.permalink.isBlank()) return image
        return "[$image](https://www.ravelry.com/projects/$owner/${project.permalink})"
    }
}
