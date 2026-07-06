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
 * A Ravelry project's full detail, as returned by `projects/{username}/{id}.json`.
 * Only the fields the in-app project page renders (issue #103).
 */
@Serializable
data class ProjectDetail(
    val id: Long = 0,
    val name: String = "",
    val permalink: String = "",
    @SerialName("pattern_name") val patternName: String? = null,
    @SerialName("status_name") val statusName: String? = null,
    @SerialName("craft_name") val craftName: String? = null,
    /** Percent complete, 0–100, or null when the owner doesn't track it. */
    val progress: Int? = null,
    val started: String? = null,
    val completed: String? = null,
    @SerialName("made_for") val madeFor: String? = null,
    val size: String? = null,
    /** Public project notes as raw Markdown source (canonical, like post bodies). */
    val notes: String? = null,
    /** Ravelry's HTML rendering of [notes]; used for emoji resolution. */
    @SerialName("notes_html") val notesHtml: String? = null,
    @SerialName("tag_names") val tagNames: List<String> = emptyList(),
    val photos: List<ProjectPhoto> = emptyList(),
)

/** State of the in-app project page. [Hidden] until a project link is tapped. */
sealed class ProjectPageState {
    /** No project page open. */
    object Hidden : ProjectPageState()

    /** Fetching [link]'s project. */
    data class Loading(val link: ProjectLink) : ProjectPageState()

    /** Project loaded and displayable. */
    data class Loaded(val link: ProjectLink, val project: ProjectDetail) : ProjectPageState()

    /**
     * The fetch failed — commonly a private/deleted project or a permissions wall.
     * The page offers retry and the open-on-Ravelry escape hatch.
     * @property message Human-readable error description.
     */
    data class Error(val link: ProjectLink, val message: String) : ProjectPageState()
}

/**
 * Drives the in-app project page (issue #103): a tapped
 * `ravelry.com/projects/{username}/{permalink}` link opens here instead of the browser.
 *
 * @param apiClient Used to fetch the project detail.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class ProjectPageViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ProjectPageState>(ProjectPageState.Hidden)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable page state. */
    val state: StateFlow<ProjectPageState> = _state.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /** A monotonically increasing token; results from a dismissed page may not surface. */
    private var generation = 0

    /** Opens the page for [link] and fetches its project. */
    fun open(link: ProjectLink) {
        _state.value = ProjectPageState.Loading(link)
        val gen = ++generation
        scope.launch {
            try {
                val project = apiClient.getProjectDetail(link.username, link.permalink)
                println("FiberSocial: ProjectPageViewModel loaded ${link.username}/${link.permalink}")
                if (gen == generation) _state.value = ProjectPageState.Loaded(link, project)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: ProjectPageViewModel.open session expired")
                if (gen == generation) _state.value = ProjectPageState.Hidden
                _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: ProjectPageViewModel.open error: ${e.message}")
                if (gen == generation) {
                    _state.value = ProjectPageState.Error(link, e.message ?: "Couldn't load the project")
                }
            }
        }
    }

    /** Refetches after an [ProjectPageState.Error]. No-op in other states. */
    fun retry() {
        val current = _state.value
        if (current is ProjectPageState.Error) open(current.link)
    }

    /** Closes the page. In-flight loads are ignored when they land. */
    fun dismiss() {
        generation++
        _state.value = ProjectPageState.Hidden
    }
}
