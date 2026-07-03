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

    /** Observable reply thread state. */
    val state: StateFlow<TopicDetailState> = _state.asStateFlow()

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
     * Toggles the current user's vote of [type] on [post]: applies an optimistic local
     * update immediately, then confirms it against the server. On failure, the optimistic
     * update is reverted; on session expiry, [sessionExpired] is also signaled.
     *
     * @param post The post to vote on, as currently rendered (used both to determine
     *   the new vote and as the value to revert to on failure).
     * @param type Which reaction to toggle.
     */
    fun toggleVote(post: Post, type: VoteType) {
        val wantsVoted = !post.hasVoted(type)
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
