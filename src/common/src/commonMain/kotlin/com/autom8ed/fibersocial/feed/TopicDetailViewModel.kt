package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.VoteType
import com.autom8ed.fibersocial.feed.models.hasVoted
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

    /** Observable reply thread state. */
    val state: StateFlow<TopicDetailState> = _state.asStateFlow()

    /** Observable state of the current reply submission. */
    val replyState: StateFlow<ReplyState> = _replyState.asStateFlow()

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
        topicGeneration++
        _replyState.value = ReplyState.Idle
        scope.launch {
            println("FiberSocial: TopicDetailViewModel.load(topicId=$topicId)")
            _state.value = TopicDetailState.Loading
            _state.value = try {
                val posts = apiClient.getTopicPosts(topicId)
                println("FiberSocial: TopicDetailViewModel loaded ${posts.size} posts")
                TopicDetailState.Loaded(posts)
            } catch (e: SessionExpiredException) {
                println("FiberSocial: TopicDetailViewModel session expired")
                _sessionExpired.trySend(Unit)
                TopicDetailState.Loading
            } catch (e: Exception) {
                println("FiberSocial: TopicDetailViewModel.load error: ${e.message}")
                TopicDetailState.Error(e.message ?: "Failed to load replies")
            }
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

    /** Resets [replyState] from [ReplyState.Sent] back to [ReplyState.Idle] after the UI has reacted. */
    fun acknowledgeReplySent() {
        if (_replyState.value is ReplyState.Sent) _replyState.value = ReplyState.Idle
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
