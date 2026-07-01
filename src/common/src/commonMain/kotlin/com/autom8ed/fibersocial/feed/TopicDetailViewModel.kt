package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.Post
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
}
