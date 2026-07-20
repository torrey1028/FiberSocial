package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.feed.models.UserSearchResult
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

/**
 * Composer-side state and pure helpers for writing a private message (issue #374, epic
 * #365). The behaviour lives on [MessagesViewModel] — sending MUTATES the accumulated
 * message list, which only that class owns — so this file holds the types and the parts
 * that are decidable without a network call.
 */

/** Shortest recipient query that is worth a search request. */
const val MIN_RECIPIENT_QUERY_LENGTH = 2

/**
 * How long the composer waits after the last keystroke before searching.
 *
 * Ravelry's global search is not a typeahead endpoint — it is the same call the website's
 * full search page makes — so a request per keystroke would fire five or six times for one
 * three-letter name, and the answers would race each other into the picker out of order.
 */
const val RECIPIENT_SEARCH_DEBOUNCE_MS = 300L

/** Subject used when a conversation genuinely has none, so a reply never sends a blank one. */
private const val NO_SUBJECT = "(no subject)"

/**
 * The recipient picker's state.
 *
 * [Idle] covers both "nothing typed yet" and "too short to search" — from the picker's
 * point of view those are the same nothing-to-show, and giving the sub-minimum case its own
 * arm would only invite the UI to render a scolding message about a query the user is still
 * halfway through typing.
 */
sealed class RecipientSearchState {
    /** No query, or one shorter than [MIN_RECIPIENT_QUERY_LENGTH]. */
    data object Idle : RecipientSearchState()

    /** A search is pending (debouncing) or in flight. */
    data object Searching : RecipientSearchState()

    /**
     * Search finished. An EMPTY [users] is the no-such-person result, not a failure.
     *
     * @property users Matches in Ravelry's relevance order.
     */
    data class Results(val users: List<UserSearchResult>) : RecipientSearchState()

    /**
     * The search call failed. Non-fatal — the composer keeps everything the user typed and
     * they can keep editing the query.
     *
     * @property message Human-readable error description.
     */
    data class Error(val message: String) : RecipientSearchState()
}

/**
 * State of an in-flight send, for both a new conversation and a reply. Mirrors
 * [com.myhobbyislearning.fibersocial.feed.NewTopicState]'s shape so the composer keeps its
 * text on failure and only navigates once the send is confirmed.
 */
sealed class SendMessageState {
    /** No send in flight. */
    data object Idle : SendMessageState()

    /** A send is in flight. */
    data object Sending : SendMessageState()

    /**
     * The message was delivered and is already in the local list — see
     * [MessagesViewModel.sendNewMessage]. The UI navigates out and calls
     * [MessagesViewModel.acknowledgeSent].
     *
     * @property message The created message as Ravelry stored it, after local normalisation.
     */
    data class Sent(val message: Message) : SendMessageState()

    /**
     * The send failed. The composer KEEPS ITS FIELDS — a message cannot be edited after
     * sending, and it certainly cannot be un-typed after a failure.
     *
     * @property message Human-readable error description. Never contains "401"/"403": those
     *   digits route to the session-expired UI (see `MessagesErrorState`), and none of these
     *   failures are a session expiry.
     * @property messagingBlocked True for the messaging-permission refusal (HTTP 403), whose
     *   two indistinguishable causes are documented on
     *   [com.myhobbyislearning.fibersocial.feed.RavelryApiClient] — the flag lets the UI give
     *   it its own treatment without re-parsing the string, and NEVER means "sign the user
     *   out" (issue #82).
     */
    data class Error(val message: String, val messagingBlocked: Boolean = false) : SendMessageState()
}

/**
 * Subject line for a reply, derived from the conversation's ROOT subject.
 *
 * Two rules, both of which exist because the naive version is wrong in a way that
 * compounds:
 *
 *  - Derived from the ROOT, never the newest message. [MessageThread.subject] is already the
 *    root's, so callers pass that; taking the newest message's subject instead would prefix
 *    an already-prefixed string and every round trip would grow another `Re:`.
 *  - Already-prefixed subjects are left alone. The counterpart's client may well have added
 *    its own `Re:` on the way in, so a root subject can arrive prefixed even when we derive
 *    from the root — matched case-insensitively, since other clients write `RE:` and `re:`.
 */
fun replySubject(rootSubject: String): String {
    val base = rootSubject.trim().ifEmpty { NO_SUBJECT }
    return if (base.startsWith("Re:", ignoreCase = true)) base else "Re: $base"
}

/**
 * Formats [epochMillis] the way Ravelry writes `sent_at` (`"yyyy/MM/dd HH:mm:ss Z"`, in
 * UTC), so a just-sent message can be given a timestamp when the server's response omits one.
 *
 * Needed because [MessageThread.lastActivityAt] is null for a thread with no parseable
 * timestamp, and [groupIntoThreads] sorts those LAST — so a freshly sent message with no
 * `sent_at` would drop its conversation to the bottom of the mailbox, which reads as the
 * send having gone somewhere unexpected. This is a fallback only: a real `sent_at` from
 * Ravelry always wins, and the local approximation is replaced by the server's value on the
 * next refresh.
 */
fun ravelryTimestamp(epochMillis: Long): String {
    val at = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC)
    fun pad(value: Int, width: Int = 2) = value.toString().padStart(width, '0')
    return "${pad(at.year, 4)}/${pad(at.monthNumber)}/${pad(at.dayOfMonth)} " +
        "${pad(at.hour)}:${pad(at.minute)}:${pad(at.second)} +0000"
}
