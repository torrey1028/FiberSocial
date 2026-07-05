package com.autom8ed.fibersocial.feedback

import com.autom8ed.fibersocial.auth.ForbiddenException
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.feed.models.Topic
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

/** Ravelry's topic-title length limit; the derived title is clamped to it as a backstop. */
private const val MAX_TITLE_LENGTH = 250

/** State of an in-flight feedback submission. Mirrors [com.autom8ed.fibersocial.feed.NewTopicState]. */
sealed class FeedbackState {
    /** No submission in flight. */
    object Idle : FeedbackState()

    /** Feedback is being posted. */
    object Sending : FeedbackState()

    /**
     * Feedback was posted as a topic.
     * @property topic The created topic as returned by Ravelry.
     */
    data class Sent(val topic: Topic) : FeedbackState()

    /**
     * Ravelry refused the post with 403 — the user isn't a posting member of the support
     * group. Surfaced distinctly so the UI can prompt them to join rather than showing a
     * generic error (and, unlike a 401, re-authenticating wouldn't help — see issue #82).
     */
    object NeedsMembership : FeedbackState()

    /**
     * Submission failed; the composer keeps its text so nothing is lost.
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : FeedbackState()
}

/**
 * Drives the "Send feedback" screen: posts the user's report as a new topic in the
 * FiberSocial App Support group's forum ([SupportGroup.FORUM_ID]).
 *
 * @param apiClient Used to create the topic.
 * @param scope Coroutine scope tied to the host ViewModel's lifecycle.
 */
class FeedbackViewModel(
    private val apiClient: RavelryApiClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<FeedbackState>(FeedbackState.Idle)
    private val _sessionExpired = Channel<Unit>(Channel.BUFFERED)

    /** Observable state of the current feedback submission. */
    val state: StateFlow<FeedbackState> = _state.asStateFlow()

    /**
     * Emits [Unit] when a [SessionExpiredException] is caught. Each emission is consumed
     * exactly once — no replay on re-subscription. Collect to navigate to login.
     */
    val sessionExpired: Flow<Unit> = _sessionExpired.receiveAsFlow()

    /**
     * Posts feedback as a new topic in the support group's forum. [description] is the
     * user's report; [details] is optional app/device context appended under a divider
     * (the user can edit or clear it in the composer). The topic title is derived from the
     * first line of [description]. Blank descriptions and double-submits are ignored; a 403
     * (not a posting member) becomes [FeedbackState.NeedsMembership] rather than an error.
     */
    fun send(description: String, details: String = "") {
        val desc = description.trim()
        if (desc.isEmpty() || _state.value is FeedbackState.Sending) return
        val title = feedbackTitle(desc)
        val body = feedbackBody(desc, details)
        _state.value = FeedbackState.Sending
        scope.launch {
            try {
                val topic = apiClient.createTopic(SupportGroup.FORUM_ID, title, body)
                println("FiberSocial: FeedbackViewModel sent feedback topic ${topic.id}")
                _state.value = FeedbackState.Sent(topic)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SessionExpiredException) {
                println("FiberSocial: FeedbackViewModel.send session expired")
                _state.value = FeedbackState.Idle
                _sessionExpired.trySend(Unit)
            } catch (e: ForbiddenException) {
                println("FiberSocial: FeedbackViewModel.send forbidden — needs group membership")
                _state.value = FeedbackState.NeedsMembership
            } catch (e: Exception) {
                println("FiberSocial: FeedbackViewModel.send error: ${e.message}")
                _state.value = FeedbackState.Error(e.message ?: "Couldn't send feedback")
            }
        }
    }

    /** Clears [FeedbackState.Sent] back to [FeedbackState.Idle] once the UI has acknowledged it. */
    fun acknowledgeSent() {
        if (_state.value is FeedbackState.Sent) _state.value = FeedbackState.Idle
    }

    /**
     * Clears a stale terminal state ([Error]/[NeedsMembership]/[Sent]) when the screen opens
     * or closes. No-op mid-send: the in-flight result still needs to land somewhere.
     */
    fun reset() {
        if (_state.value !is FeedbackState.Sending) _state.value = FeedbackState.Idle
    }
}

/** The topic title for a feedback report: its first non-blank line, clamped, or a default. */
fun feedbackTitle(description: String): String {
    val firstLine = description.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }
    return (firstLine ?: "App feedback").take(MAX_TITLE_LENGTH)
}

/** The topic body: the report, with any app/device [details] appended under a divider. */
fun feedbackBody(description: String, details: String): String {
    val desc = description.trim()
    val info = details.trim()
    return if (info.isEmpty()) desc else "$desc\n\n---\n$info"
}
