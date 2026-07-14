package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.feed.models.Topic
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
 * State of an in-flight topic creation. Mirrors [ReplyState]'s shape so the
 * composer UI keeps its text on failure and only clears once creation is confirmed.
 */
sealed class NewTopicState {
    /** No creation in flight. */
    object Idle : NewTopicState()

    /** Topic is being submitted. */
    object Sending : NewTopicState()

    /**
     * Topic was created; UI should navigate into it and acknowledge via
     * [NewTopicViewModel.acknowledgeCreated].
     * @property topic The created topic as returned by Ravelry.
     */
    data class Created(val topic: Topic) : NewTopicState()

    /**
     * Submission failed; the composer should keep its fields so nothing is lost.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : NewTopicState()
}

/**
 * Drives the new-topic composer: submits a title + opening post to a group's forum.
 *
 * @param apiClient Used to create the topic.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class NewTopicViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<NewTopicState>(NewTopicState.Idle)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable state of the current topic creation. */
    val state: StateFlow<NewTopicState> = _state.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /**
     * Creates a topic titled [title] with [body] as its opening post in forum [forumId].
     * The optional [summary] is a short blurb shown in the forum's topic list; a blank
     * one is dropped. Blank titles/bodies and double-submits are ignored. Titles are
     * clamped to [MAX_TITLE_LENGTH] (Ravelry's limit) as a backstop; the UI enforces it
     * while typing. On failure the state carries the error and the composer keeps its fields.
     */
    fun create(forumId: Long, title: String, body: String, summary: String? = null) {
        val trimmedTitle = title.trim().take(MAX_TITLE_LENGTH)
        val trimmedBody = body.trim()
        val trimmedSummary = summary?.trim()?.takeIf { it.isNotEmpty() }
        if (trimmedTitle.isEmpty() || trimmedBody.isEmpty() || _state.value is NewTopicState.Sending) return
        _state.value = NewTopicState.Sending
        scope.launch {
            try {
                val topic = apiClient.createTopic(forumId, trimmedTitle, trimmedBody, trimmedSummary)
                println("FiberSocial: NewTopicViewModel created topic ${topic.id}")
                _state.value = NewTopicState.Created(topic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: NewTopicViewModel.create session expired")
                _state.value = NewTopicState.Idle
                _sessionExpired.trySend(Unit)
            } catch (e: Exception) {
                println("FiberSocial: NewTopicViewModel.create error: ${e.message}")
                _state.value = NewTopicState.Error(e.message ?: "Failed to create topic")
            }
        }
    }

    /** Resets [state] from [NewTopicState.Created] back to [NewTopicState.Idle] after the UI has navigated. */
    fun acknowledgeCreated() {
        if (_state.value is NewTopicState.Created) _state.value = NewTopicState.Idle
    }

    /**
     * Clears a stale [NewTopicState.Error] or [NewTopicState.Created] when the composer
     * opens or closes. No-op mid-send: the in-flight result still needs to land somewhere.
     */
    fun reset() {
        if (_state.value !is NewTopicState.Sending) _state.value = NewTopicState.Idle
    }

    companion object {
        /** Ravelry's topic-title length limit. */
        const val MAX_TITLE_LENGTH = 250
    }
}
