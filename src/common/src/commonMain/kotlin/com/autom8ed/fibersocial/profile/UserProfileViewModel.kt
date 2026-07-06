package com.autom8ed.fibersocial.profile

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.projects.ProjectSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
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
 * A Ravelry user's public profile, from `people/{username}.json` (issue #194).
 * Only the fields the profile header renders.
 */
@Serializable
data class UserProfile(
    val id: Long = 0,
    val username: String = "",
    @SerialName("first_name") val firstName: String? = null,
    val location: String? = null,
    /** "About me" as rendered HTML (there's no plain-markdown field). */
    @SerialName("about_me_html") val aboutHtml: String? = null,
    @SerialName("large_photo_url") val largePhotoUrl: String? = null,
    @SerialName("photo_url") val photoUrl: String? = null,
    @SerialName("small_photo_url") val smallPhotoUrl: String? = null,
) {
    /** Best available avatar for the profile header. */
    val avatarUrl: String? get() = largePhotoUrl ?: photoUrl ?: smallPhotoUrl
}

/** State of the in-app user-profile page. [Hidden] until a username is tapped. */
sealed class UserProfileState {
    /** No profile open. */
    object Hidden : UserProfileState()

    /** Fetching [username]'s profile. */
    data class Loading(val username: String) : UserProfileState()

    /**
     * Profile loaded.
     * @property projects The user's projects (newest first); empty if none or the
     *   fetch failed — a project list is secondary to the header.
     * @property groups The groups the user belongs to; empty if none/failed.
     */
    data class Loaded(
        val profile: UserProfile,
        val projects: List<ProjectSummary>,
        val groups: List<Group>,
    ) : UserProfileState()

    /**
     * The profile fetch failed (private/deleted user, or a network error).
     * @property message Human-readable error description.
     */
    data class Error(val username: String, val message: String) : UserProfileState()
}

/**
 * Drives the in-app user-profile page (issue #194): a tapped username opens here,
 * showing the user's header, their projects, and the groups they're in.
 *
 * @param apiClient Used to fetch the profile, projects, and group memberships.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class UserProfileViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<UserProfileState>(UserProfileState.Hidden)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable profile page state. */
    val state: StateFlow<UserProfileState> = _state.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /** A monotonically increasing token; results from a dismissed page may not surface. */
    private var generation = 0

    /**
     * Opens the profile for [username] and loads it. The profile itself is required (its
     * failure is the page's error); projects and group memberships are best-effort and
     * load alongside it — either failing just yields an empty section.
     */
    fun open(username: String) {
        _state.value = UserProfileState.Loading(username)
        val gen = ++generation
        scope.launch {
            try {
                // Projects and groups are best-effort — each swallows its own failure to
                // an empty list — so their async children never fail the scope. The
                // profile is fetched directly (not in an async child) so a genuine
                // failure surfaces here in the try rather than propagating to the parent
                // job out of reach of this catch. All three still overlap: the asyncs
                // start before the profile request.
                val projectsDeferred = async { runCatching { apiClient.getProjects(username) }.getOrDefault(emptyList()) }
                val groupsDeferred = async { runCatching { apiClient.getUserGroups(username) }.getOrDefault(emptyList()) }
                val profile = apiClient.getUserProfile(username)
                val projects = projectsDeferred.await()
                val groups = groupsDeferred.await()
                if (gen == generation) {
                    _state.value = UserProfileState.Loaded(profile, projects, groups)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: UserProfileViewModel.open session expired")
                if (gen == generation) _state.value = UserProfileState.Hidden
                _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: UserProfileViewModel.open($username) error: ${e.message}")
                if (gen == generation) {
                    _state.value = UserProfileState.Error(username, e.message ?: "Couldn't load the profile")
                }
            }
        }
    }

    /** Refetches after a [UserProfileState.Error]. No-op in other states. */
    fun retry() {
        val current = _state.value
        if (current is UserProfileState.Error) open(current.username)
    }

    /** Closes the page. In-flight loads are ignored when they land. */
    fun dismiss() {
        generation++
        _state.value = UserProfileState.Hidden
    }
}
