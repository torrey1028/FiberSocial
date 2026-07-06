package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.VoteType
import com.autom8ed.fibersocial.feed.models.hasVoted
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/**
 * Represents the state of a loaded topic's reply thread.
 */
sealed class TopicDetailState {
    /** Fetching posts from the API. */
    object Loading : TopicDetailState()

    /**
     * Posts successfully loaded.
     * @property posts All replies in the topic, ordered oldest-first.
     */
    data class Loaded(val posts: List<Post>) : TopicDetailState()

    /**
     * Failed to load posts.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : TopicDetailState()
}

/**
 * State of an in-flight reply submission, separate from the thread itself so a
 * failed send never disturbs the loaded posts.
 */
sealed class ReplyState {
    /** No reply in flight. */
    object Idle : ReplyState()

    /** Reply is being submitted. */
    object Sending : ReplyState()

    /** Reply was accepted and appended to the thread; UI should clear its composer. */
    object Sent : ReplyState()

    /**
     * Submission failed; the composer should keep its text so nothing is lost.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : ReplyState()
}

/**
 * State of an in-flight own-post deletion.
 */
sealed class DeleteState {
    /** No deletion in flight. */
    object Idle : DeleteState()

    /** The post with [postId] is being deleted. */
    data class Deleting(val postId: Long) : DeleteState()

    /**
     * Deletion failed; the post remains in the thread.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : DeleteState()
}

/**
 * State of an in-flight own-post edit.
 */
sealed class EditState {
    /** No edit in flight. */
    object Idle : EditState()

    /** The post with [postId] is being saved. */
    data class Saving(val postId: Long) : EditState()

    /**
     * Save failed; the post keeps its original body.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : EditState()
}

/**
 * Loads and exposes the reply thread for a single Ravelry forum topic.
 *
 * @param apiClient Used to fetch posts.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class TopicDetailViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<TopicDetailState>(TopicDetailState.Loading)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    private val _replyState = MutableStateFlow<ReplyState>(ReplyState.Idle)
    private val _deleteState = MutableStateFlow<DeleteState>(DeleteState.Idle)
    private val _editState = MutableStateFlow<EditState>(EditState.Idle)

    /** Observable reply thread state. */
    val state: StateFlow<TopicDetailState> = _state.asStateFlow()

    /** Observable state of the current reply submission. */
    val replyState: StateFlow<ReplyState> = _replyState.asStateFlow()

    /** Observable state of the current own-post deletion. */
    val deleteState: StateFlow<DeleteState> = _deleteState.asStateFlow()

    /** Observable state of the current own-post edit. */
    val editState: StateFlow<EditState> = _editState.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /**
     * Fetches all posts for [topicId], replacing any previously loaded state.
     *
     * @param topicId Ravelry topic ID to load replies for.
     */
    fun load(topicId: Long) {
        // The ViewModel is reused across topics: a new topic invalidates any leftover
        // composer state AND any in-flight send's right to touch state (topicGeneration).
        // A reload of the SAME topic — pull-to-refresh — must not: sendReply/deletePost/
        // editPost all capture topicGeneration before their network call and check it on
        // the way back in, so bumping it here on every refresh silently dropped their
        // outcome (success or failure) whenever a refresh landed while one was in flight.
        val isSameTopic = topicId == loadedTopicId
        loadedTopicId = topicId
        if (!isSameTopic) {
            topicGeneration++
            _replyState.value = ReplyState.Idle
            _editState.value = EditState.Idle
            _deleteState.value = DeleteState.Idle
        }
        scope.launch {
            println("FiberSocial: TopicDetailViewModel.load(topicId=$topicId)")
            _state.value = TopicDetailState.Loading
            try {
                val posts = apiClient.getTopicPosts(topicId)
                println("FiberSocial: TopicDetailViewModel loaded ${posts.size} posts")
                // Show the thread first, THEN mark it read (issue #185). The read POST is
                // best-effort — it advances Ravelry's own marker so the website and the
                // feed's unread count agree next refresh — but emitting Loaded before it
                // means a slow or failed read POST can't delay rendering the thread the
                // user just opened. It stays in this coroutine (which outlives a quick
                // back-out, since the VM scope isn't cancelled) so the marker still syncs.
                _state.value = TopicDetailState.Loaded(posts)
                if (posts.isNotEmpty()) markReadBestEffort(topicId, posts.size)
            } catch (e: SessionExpiredException) {
                println("FiberSocial: TopicDetailViewModel session expired")
                _sessionExpired.trySend(Unit)
                _state.value = TopicDetailState.Loading
            } catch (e: Exception) {
                println("FiberSocial: TopicDetailViewModel.load error: ${e.message}")
                _state.value = TopicDetailState.Error(e.message ?: "Failed to load replies")
            }
        }
    }

    /** Advances Ravelry's read marker for [topicId] to [lastRead]; swallows failures. */
    private suspend fun markReadBestEffort(topicId: Long, lastRead: Int) {
        try {
            apiClient.markTopicRead(topicId, lastRead)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            // A best-effort read must not block the thread, but a genuine expiry still has
            // to route to login rather than be silently swallowed — now that this runs in a
            // detached coroutine, load()'s own catch no longer covers it (matches the
            // session-expiry handling in sendReply/deletePost/editPost).
            println("FiberSocial: markTopicRead($topicId) session expired")
            _sessionExpired.trySend(Unit)
        } catch (e: Exception) {
            println("FiberSocial: markTopicRead($topicId) failed: ${e.message}")
        }
    }

    /**
     * Submits [body] as a new reply to [topicId]. On success the created post is appended
     * to the loaded thread and [replyState] becomes [ReplyState.Sent] (the UI acknowledges
     * via [acknowledgeReplySent]). Blank bodies and double-submits are ignored. On failure
     * the thread state is untouched so the composer can retry without losing text.
     *
     * @param topicId Topic being replied to.
     * @param body Plain-text reply content.
     */
    fun sendReply(topicId: Long, body: String) {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || _replyState.value is ReplyState.Sending) return
        _replyState.value = ReplyState.Sending
        // A send that outlives its topic (user navigated away mid-flight) must not
        // touch state: the reply would append into whichever topic is now loaded, and
        // a stale Sent/Error could wipe the next topic's half-typed draft.
        val generation = topicGeneration
        scope.launch {
            try {
                val post = apiClient.postReply(topicId, trimmed)
                if (generation != topicGeneration) return@launch
                val current = _state.value
                if (current is TopicDetailState.Loaded) {
                    _state.value = TopicDetailState.Loaded(current.posts + post)
                }
                _replyState.value = ReplyState.Sent
            } catch (e: SessionExpiredException) {
                println("FiberSocial: TopicDetailViewModel.sendReply session expired")
                if (generation == topicGeneration) _replyState.value = ReplyState.Idle
                _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: TopicDetailViewModel.sendReply error: ${e.message}")
                if (generation == topicGeneration) {
                    _replyState.value = ReplyState.Error(e.message ?: "Failed to post reply")
                }
            }
        }
    }

    /** Monotonic token: a send from a previous topic may not touch the current one's state. */
    private var topicGeneration = 0

    /** The topic [load] most recently targeted; distinguishes a new topic from a refresh. */
    private var loadedTopicId: Long? = null

    /** Resets [replyState] from [ReplyState.Sent] back to [ReplyState.Idle] after the UI has reacted. */
    fun acknowledgeReplySent() {
        if (_replyState.value is ReplyState.Sent) _replyState.value = ReplyState.Idle
    }

    /**
     * Deletes the current user's own [post] from the thread. Pessimistic: the post is
     * removed from [state] only after Ravelry confirms the deletion. Double-submits are
     * ignored while a deletion is in flight. On failure the thread is untouched and
     * [deleteState] reports the error; session expiry routes through [sessionExpired].
     *
     * @param post The post to delete (must be authored by the signed-in user; the server
     *   rejects deletions of others' posts).
     */
    fun deletePost(post: Post) {
        if (_deleteState.value is DeleteState.Deleting) return
        _deleteState.value = DeleteState.Deleting(post.id)
        // Same in-flight-outlives-its-topic contract as sendReply: after navigating
        // away, this coroutine may not touch the new topic's thread or dialogs.
        val generation = topicGeneration
        scope.launch {
            try {
                apiClient.deletePost(post.id)
                if (generation != topicGeneration) return@launch
                val current = _state.value
                if (current is TopicDetailState.Loaded) {
                    _state.value = TopicDetailState.Loaded(current.posts.filterNot { it.id == post.id })
                }
                _deleteState.value = DeleteState.Idle
            } catch (e: SessionExpiredException) {
                println("FiberSocial: TopicDetailViewModel.deletePost session expired")
                if (generation == topicGeneration) _deleteState.value = DeleteState.Idle
                _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: TopicDetailViewModel.deletePost error: ${e.message}")
                if (generation == topicGeneration) {
                    _deleteState.value = DeleteState.Error(e.message ?: "Failed to delete post")
                }
            }
        }
    }

    /** Clears a [DeleteState.Error] after the UI has shown it. */
    fun acknowledgeDeleteError() {
        if (_deleteState.value is DeleteState.Error) _deleteState.value = DeleteState.Idle
    }

    /**
     * Saves an edit to the current user's own [post]. On success the post's body is replaced
     * in [state] with Ravelry's returned version. Blank bodies and double-submits are ignored.
     * On failure the thread is untouched and [editState] reports the error; session expiry
     * routes through [sessionExpired].
     *
     * @param post The post being edited.
     * @param newBody New body text.
     */
    fun editPost(post: Post, newBody: String) {
        val trimmed = newBody.trim()
        if (trimmed.isEmpty() || _editState.value is EditState.Saving) return
        _editState.value = EditState.Saving(post.id)
        val generation = topicGeneration
        scope.launch {
            try {
                val updated = apiClient.editPost(post.id, trimmed)
                if (generation != topicGeneration) return@launch
                updatePost(post.id) { it.copy(body = updated.body, bodyHtml = updated.bodyHtml) }
                _editState.value = EditState.Idle
            } catch (e: SessionExpiredException) {
                println("FiberSocial: TopicDetailViewModel.editPost session expired")
                if (generation == topicGeneration) _editState.value = EditState.Idle
                _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: TopicDetailViewModel.editPost error: ${e.message}")
                if (generation == topicGeneration) {
                    _editState.value = EditState.Error(e.message ?: "Failed to save edit")
                }
            }
        }
    }

    /** Clears an [EditState.Error] after the UI has shown it. */
    fun acknowledgeEditError() {
        if (_editState.value is EditState.Error) _editState.value = EditState.Idle
    }

    /**
     * Toggles the current user's vote of [type] on [post]: applies an optimistic local
     * update immediately, then confirms it against the server. On failure, the optimistic
     * update is reverted (by applying the inverse of the optimistic change to whatever
     * post is then in state); on session expiry, [sessionExpired] is also signaled.
     *
     * @param post Identifies which post to vote on ([Post.id]). The toggle direction and
     *   all reverts are computed from the post's current value in [state], not from this
     *   parameter, so a stale [post] (e.g. from a rapid double-tap) can't send the wrong
     *   `voted` value or clobber a concurrent local update.
     * @param type Which reaction to toggle.
     */
    fun toggleVote(post: Post, type: VoteType) {
        val wantsVoted = !(currentPost(post.id) ?: post).hasVoted(type)
        updatePost(post.id) { optimisticVote(it, type, wantsVoted) }

        scope.launch {
            try {
                val result = apiClient.voteOnPost(post.id, type, voted = wantsVoted)
                updatePost(post.id) { it.copy(voteTotals = result.voteTotals, userVotes = result.userVotes) }
            } catch (e: SessionExpiredException) {
                println("FiberSocial: TopicDetailViewModel.toggleVote session expired")
                updatePost(post.id) { optimisticVote(it, type, !wantsVoted) }
                _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: TopicDetailViewModel.toggleVote error: ${e.message}")
                updatePost(post.id) { optimisticVote(it, type, !wantsVoted) }
            }
        }
    }

    /** The post with [postId] in the current loaded state, or `null` if not loaded. */
    private fun currentPost(postId: Long): Post? {
        val current = _state.value
        return (current as? TopicDetailState.Loaded)?.posts?.find { it.id == postId }
    }

    /** Applies [transform] to the post with [postId] in the current loaded state, if any. */
    private fun updatePost(postId: Long, transform: (Post) -> Post) {
        val current = _state.value
        if (current is TopicDetailState.Loaded) {
            _state.value = current.copy(posts = current.posts.map { if (it.id == postId) transform(it) else it })
        }
    }
}

/**
 * Returns a copy of [post] with its [type] vote totals/user votes optimistically updated.
 *
 * A count that drops to zero removes the map entry entirely (rather than keeping a
 * `type -> 0` entry) so that applying this twice with opposite [voted] values exactly
 * restores the original post — needed for reverting a failed optimistic update.
 */
private fun optimisticVote(post: Post, type: VoteType, voted: Boolean): Post {
    val currentCount = post.voteTotals[type.wireValue] ?: 0
    val newCount = if (voted) currentCount + 1 else (currentCount - 1).coerceAtLeast(0)
    val newVoteTotals = if (newCount > 0) {
        post.voteTotals + (type.wireValue to newCount)
    } else {
        post.voteTotals - type.wireValue
    }
    val newUserVotes = if (voted) post.userVotes + type.wireValue else post.userVotes - type.wireValue
    return post.copy(
        voteTotals = newVoteTotals,
        userVotes = newUserVotes,
    )
}
