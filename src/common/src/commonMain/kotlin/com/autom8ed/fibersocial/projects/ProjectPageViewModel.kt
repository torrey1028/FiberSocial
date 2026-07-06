package com.autom8ed.fibersocial.projects

import com.autom8ed.fibersocial.auth.ForbiddenException
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
    /** ID of the linked Ravelry database pattern, or null for a free-text pattern name. */
    @SerialName("pattern_id") val patternId: Long? = null,
    /** Public project notes as raw Markdown source (canonical, like post bodies). */
    val notes: String? = null,
    /** Ravelry's HTML rendering of [notes]; used for emoji resolution. */
    @SerialName("notes_html") val notesHtml: String? = null,
    @SerialName("tag_names") val tagNames: List<String> = emptyList(),
    val photos: List<ProjectPhoto> = emptyList(),
)

/**
 * A Ravelry database pattern, enough to link a project's pattern to its library page and
 * name the designer (issue #103).
 */
@Serializable
data class PatternInfo(
    val id: Long = 0,
    val name: String = "",
    val permalink: String = "",
    @SerialName("pattern_author") val author: PatternAuthor? = null,
) {
    /** The pattern's library page URL. */
    val webUrl: String get() = "https://www.ravelry.com/patterns/library/$permalink"
}

/** A pattern's designer. */
@Serializable
data class PatternAuthor(
    val id: Long = 0,
    val name: String = "",
    val permalink: String = "",
)

/**
 * A comment on a project. Ravelry serves the body only as rendered HTML ([commentHtml]);
 * there's no Markdown source field for comments.
 */
@Serializable
data class ProjectComment(
    val id: Long = 0,
    @SerialName("comment_html") val commentHtml: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val user: com.autom8ed.fibersocial.feed.models.RavelryUser? = null,
)

/** State of the in-app project page. [Hidden] until a project link is tapped. */
sealed class ProjectPageState {
    /** No project page open. */
    object Hidden : ProjectPageState()

    /** Fetching [link]'s project. */
    data class Loading(val link: ProjectLink) : ProjectPageState()

    /** Project loaded and displayable. The linked pattern (if any) loads separately. */
    data class Loaded(val link: ProjectLink, val project: ProjectDetail) : ProjectPageState()

    /**
     * The fetch failed — commonly a private/deleted project or a permissions wall.
     * The page offers retry and the open-on-Ravelry escape hatch.
     * @property message Human-readable error description.
     */
    data class Error(val link: ProjectLink, val message: String) : ProjectPageState()
}

/** State of a project's comment thread, loaded after the project itself. */
sealed class ProjectCommentsState {
    /** Fetching comments (or none loaded yet). */
    object Loading : ProjectCommentsState()

    /** Comments loaded, oldest first. */
    data class Loaded(val comments: List<ProjectComment>) : ProjectCommentsState()

    /** Comments couldn't be loaded; the rest of the page still shows. */
    data class Error(val message: String) : ProjectCommentsState()
}

/** State of an in-flight comment submission. */
sealed class CommentPostState {
    /** No comment in flight. */
    object Idle : CommentPostState()

    /** A comment is being posted. */
    object Sending : CommentPostState()

    /**
     * The comment posted; the composer clears its text and acknowledges via
     * [ProjectPageViewModel.acknowledgePosted]. Clearing on this signal (not eagerly on
     * tap) is what keeps the text if the post instead fails.
     */
    object Posted : CommentPostState()

    /**
     * Submission failed; the composer keeps its text so nothing is lost.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : CommentPostState()
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
    private val _pattern = MutableStateFlow<PatternInfo?>(null)
    private val _commentsState = MutableStateFlow<ProjectCommentsState>(ProjectCommentsState.Loading)
    private val _postState = MutableStateFlow<CommentPostState>(CommentPostState.Idle)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable page state. */
    val state: StateFlow<ProjectPageState> = _state.asStateFlow()

    /** The linked pattern's info once resolved (best-effort), or null. */
    val pattern: StateFlow<PatternInfo?> = _pattern.asStateFlow()

    /** Observable comment-thread state. */
    val commentsState: StateFlow<ProjectCommentsState> = _commentsState.asStateFlow()

    /** Observable state of the current comment submission. */
    val postState: StateFlow<CommentPostState> = _postState.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /** A monotonically increasing token; results from a dismissed page may not surface. */
    private var generation = 0

    /** Comment ids with a delete in flight, to drop a double-tapped repeat delete. */
    private val deletingIds = mutableSetOf<Long>()

    /** Opens the page for [link] and fetches its project (then pattern + comments). */
    fun open(link: ProjectLink) {
        _state.value = ProjectPageState.Loading(link)
        _pattern.value = null
        _commentsState.value = ProjectCommentsState.Loading
        _postState.value = CommentPostState.Idle
        val gen = ++generation
        scope.launch {
            try {
                val project = apiClient.getProjectDetail(link.username, link.permalink)
                println("FiberSocial: ProjectPageViewModel loaded ${link.username}/${link.permalink}")
                if (gen != generation) return@launch
                _state.value = ProjectPageState.Loaded(link, project)
                // Pattern info and comments are secondary: their failure must not fail
                // the page, so they load after (and independently of) the project.
                loadPattern(project, gen)
                loadComments(link, project.id, gen)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: ProjectPageViewModel.open session expired")
                if (gen == generation) {
                    _state.value = ProjectPageState.Hidden
                    _sessionExpired.trySend(Unit)
                }
            } catch (e: Exception) {
                println("FiberSocial: ProjectPageViewModel.open error: ${e.message}")
                if (gen == generation) {
                    _state.value = ProjectPageState.Error(link, e.message ?: "Couldn't load the project")
                }
            }
        }
    }

    private fun loadPattern(project: ProjectDetail, gen: Int) {
        val patternId = project.patternId ?: return
        scope.launch {
            try {
                val pattern = apiClient.getPatternInfo(patternId)
                if (gen == generation) _pattern.value = pattern
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Best-effort: leave the pattern name unlinked (issue #103).
                println("FiberSocial: ProjectPageViewModel.loadPattern($patternId) failed: ${e.message}")
            }
        }
    }

    private fun loadComments(link: ProjectLink, projectId: Long, gen: Int) {
        scope.launch {
            try {
                val comments = apiClient.getProjectComments(link.username, projectId)
                if (gen == generation) _commentsState.value = ProjectCommentsState.Loaded(comments)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                if (gen == generation) {
                    _commentsState.value = ProjectCommentsState.Loading
                    _sessionExpired.trySend(Unit)
                }
            } catch (e: Exception) {
                println("FiberSocial: ProjectPageViewModel.loadComments($projectId) failed: ${e.message}")
                if (gen == generation) {
                    _commentsState.value = ProjectCommentsState.Error(e.message ?: "Couldn't load comments")
                }
            }
        }
    }

    /**
     * Posts [body] as a comment on the loaded project. On success the comment is appended
     * to the thread. Blank bodies and double-submits are ignored. A 403 (older token
     * without the `message-write` scope) surfaces a re-login prompt.
     */
    fun postComment(body: String) {
        val trimmed = body.trim()
        val current = _state.value
        if (trimmed.isEmpty() || current !is ProjectPageState.Loaded) return
        if (_postState.value is CommentPostState.Sending) return
        _postState.value = CommentPostState.Sending
        val gen = generation
        scope.launch {
            try {
                val comment = apiClient.postProjectComment(current.project.id, trimmed)
                if (gen != generation) return@launch
                val comments = _commentsState.value
                if (comments is ProjectCommentsState.Loaded) {
                    _commentsState.value = ProjectCommentsState.Loaded(comments.comments + comment)
                } else {
                    _commentsState.value = ProjectCommentsState.Loaded(listOf(comment))
                }
                _postState.value = CommentPostState.Posted
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenException) {
                // Older tokens predate the message-write scope; a fresh login grants it.
                println("FiberSocial: ProjectPageViewModel.postComment forbidden: ${e.message}")
                if (gen == generation) _postState.value = CommentPostState.Error(COMMENT_PERMISSION_MESSAGE)
            } catch (e: SessionExpiredException) {
                println("FiberSocial: ProjectPageViewModel.postComment session expired")
                if (gen == generation) {
                    _postState.value = CommentPostState.Idle
                    _sessionExpired.trySend(Unit)
                }
            } catch (e: Exception) {
                println("FiberSocial: ProjectPageViewModel.postComment error: ${e.message}")
                if (gen == generation) {
                    _postState.value = CommentPostState.Error(e.message ?: "Couldn't post your comment")
                }
            }
        }
    }

    /**
     * Deletes the signed-in user's own [comment] from the thread. Pessimistic: the
     * comment is removed from the list only after Ravelry confirms the deletion, so a
     * failure leaves the thread intact. A 403 (older token, or not the user's comment)
     * surfaces the cause-neutral [COMMENT_DELETE_FORBIDDEN_MESSAGE] — re-login only fixes
     * the scope case, not the ownership one (issue #197); other errors surface their message.
     */
    fun deleteComment(comment: ProjectComment) {
        // Deletion is pessimistic (the row stays until the server confirms), so a
        // double-tap could fire two DELETEs for the same id — the second 403s on an
        // already-gone comment and shows a misleading permission error. Ignore a repeat
        // while one is in flight for that id.
        if (!deletingIds.add(comment.id)) return
        val gen = generation
        scope.launch {
            // Failure message set by the catch blocks; applied once, gen-guarded, below —
            // so the "did this outlive its page" check lives in a single place.
            var failure: String? = null
            try {
                apiClient.deleteComment(comment.id)
                if (gen != generation) return@launch
                val comments = _commentsState.value
                if (comments is ProjectCommentsState.Loaded) {
                    _commentsState.value =
                        ProjectCommentsState.Loaded(comments.comments.filterNot { it.id == comment.id })
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: ForbiddenException) {
                // Unlike posting (which 403s only for a missing message-write scope),
                // delete also 403s when the comment isn't the user's to remove — the
                // server disagreeing with the client-side ownership gate. Re-login won't
                // fix that case, so a neutral permission message is safer here (issue #197).
                println("FiberSocial: ProjectPageViewModel.deleteComment forbidden: ${e.message}")
                failure = COMMENT_DELETE_FORBIDDEN_MESSAGE
            } catch (e: SessionExpiredException) {
                println("FiberSocial: ProjectPageViewModel.deleteComment session expired")
                if (gen == generation) _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: ProjectPageViewModel.deleteComment error: ${e.message}")
                failure = e.message ?: "Couldn't delete the comment"
            } finally {
                deletingIds.remove(comment.id)
            }
            if (failure != null && gen == generation) _postState.value = CommentPostState.Error(failure)
        }
    }

    /** Clears a [CommentPostState.Error] after the UI has shown it. */
    fun acknowledgePostError() {
        if (_postState.value is CommentPostState.Error) _postState.value = CommentPostState.Idle
    }

    /** Resets [CommentPostState.Posted] to Idle once the composer has cleared its text. */
    fun acknowledgePosted() {
        if (_postState.value is CommentPostState.Posted) _postState.value = CommentPostState.Idle
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

    companion object {
        /** Shown when a comment POST 403s — the token lacks the message-write scope. */
        const val COMMENT_PERMISSION_MESSAGE =
            "Log out and back in to enable commenting (your session predates comment permission)."

        /**
         * Shown when a comment DELETE 403s. Deliberately cause-neutral: a delete 403 can
         * mean either a missing scope or that the comment isn't the user's to remove, and
         * only the former is fixable by re-login (issue #197).
         */
        const val COMMENT_DELETE_FORBIDDEN_MESSAGE =
            "You don't have permission to delete this comment."
    }
}
