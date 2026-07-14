package com.myhobbyislearning.fibersocial.auth

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * A one-shot "the session just expired" signal (issue #270). Several ViewModels
 * (`NewTopicViewModel`, `TopicDetailViewModel`, ...) each independently caught
 * [SessionExpiredException] and hand-rolled an identical `Channel<Unit>(Channel.BUFFERED)` +
 * `receiveAsFlow()` pair to notify the host screen to navigate to login; this collects that
 * boilerplate into one reusable type.
 *
 * @property flow Emits [Unit] on each [signal] call. Each emission is consumed exactly
 *   once — no replay on re-subscription. Collect to navigate to login.
 */
class SessionExpirySignal {
    private val channel = Channel<Unit>(Channel.BUFFERED)

    val flow: Flow<Unit> = channel.receiveAsFlow()

    /** Call from a caught [SessionExpiredException] to notify collectors of [flow]. */
    fun signal() {
        channel.trySend(Unit)
    }
}
