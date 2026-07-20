package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.feed.parseRavelryTimestamp

/**
 * Client-side conversation threading for Ravelry PMs (issue #368, epic #365).
 *
 * Ravelry has no conversation object, no thread id, and no thread endpoint — the inbox is
 * FLAT. The only link between a reply and what it replies to is [Message.parentMessageId].
 * Every "thread" this file produces is our construct, not Ravelry's, reconstructed from
 * whatever messages the caller happened to fetch.
 *
 * ## Cross-folder merge: [groupIntoThreads] takes a PRE-MERGED list
 *
 * A real conversation spans `inbox` and `sent` (and `archived`), but Ravelry serves those
 * as three separate `folder=` calls. **The caller concatenates them and passes one list**;
 * this function knows nothing about folders and never fetches. Rationale:
 *
 * - Paging, retry, and partial failure are the caller's problem already — if the `sent`
 *   fetch fails, only the caller can decide whether to show inbox-only threads or an
 *   error, and a folder-aware grouper would have to invent that policy.
 * - It keeps this file pure and trivially testable: a list in, a list out, no network.
 * - Grouping is idempotent and order-independent, so a caller may regroup a growing
 *   accumulated list as more pages arrive without special-casing anything.
 *
 * Consequence for #370 (the conversation list screen): the screen must fetch at least
 * `inbox` + `sent` and concatenate before calling this, or every conversation will look
 * one-sided. Duplicates (the same [Message.id] arriving from two folders) are collapsed
 * here by id, so concatenating overlapping pages is safe.
 *
 * ## Identity is by USERNAME, not id
 *
 * [Message.sender]/[Message.recipient] are `RavelryUser?`, and [RavelryUser] carries no
 * `id` — only [RavelryUser.username]. So the signed-in user and the counterpart are both
 * identified by username, compared case-insensitively (Ravelry usernames are
 * case-preserving but not case-distinct). Either party can also be `null` on a given
 * message (system notices, deleted accounts); that is handled as "unknown", never as a
 * crash and never as a guess.
 */

/** How far [groupIntoThreads] will walk a `parentMessageId` chain before giving up. */
private const val MAX_PARENT_WALK_DEPTH = 64

/**
 * Which way a message travelled, relative to the signed-in user.
 *
 * [UNKNOWN] is a real, expected state — a message whose sender is absent and whose
 * recipient isn't us tells us nothing, and inventing a direction there would invent an
 * unread dot. It is treated as neither inbound nor outbound.
 *
 * PUBLIC because two callers outside this file need exactly the classification the
 * unread rule already depends on, and a second implementation of it would be free to
 * drift: the conversation detail screen (#371) styles sent messages differently from
 * received ones, and its mark-read pass must POST for INBOUND messages only. Marking an
 * outbound message read would be meaningless (its `read_message` describes whether the
 * *recipient* read it), and marking an [UNKNOWN] one would be a guess.
 */
enum class MessageDirection { INBOUND, OUTBOUND, UNKNOWN }

/**
 * One reconstructed conversation.
 *
 * @property rootId Stable thread identity: the [Message.id] of the chain's root. For an
 *   orphaned reply (parent not in the supplied list) this is the reply's own id — see
 *   [groupIntoThreads].
 * @property messages The thread's messages, oldest → newest. Never empty.
 * @property subject The ROOT's subject, not the newest message's — replies accrete `Re:`
 *   prefixes and the leaf subject is not what the conversation is called. Falls back to
 *   the oldest non-blank subject in the thread when the root's own subject is blank.
 * @property counterpart The other participant — whichever of sender/recipient is not the
 *   signed-in user. `null` when no message in the thread names anyone else (both parties
 *   absent, or a self-addressed message).
 * @property lastActivityAt Epoch millis of the newest message whose `sent_at` parsed, or
 *   `null` when NO message in the thread carried a parseable timestamp. Deliberately
 *   nullable rather than defaulting to `0L`: an unparseable timestamp is unknown, and
 *   `0L` would silently sort a live conversation to 1970. This mirrors `resolveGroupDots`,
 *   which shows nothing for a group whose activity is unknown rather than guessing.
 * @property hasUnread Whether any INBOUND message in the thread is unread. An outbound
 *   message's [Message.readMessage] describes whether the *recipient* read it, which is
 *   not our unread and never lights our dot.
 */
data class MessageThread(
    val rootId: Long,
    val messages: List<Message>,
    val subject: String,
    val counterpart: RavelryUser?,
    val lastActivityAt: Long?,
    val hasUnread: Boolean,
)

/**
 * Reconstructs conversations from a flat, pre-merged list of [messages].
 *
 * Each message is walked up its [Message.parentMessageId] chain to a root, bucketed by
 * that root's id, and each bucket ordered oldest → newest. Threads come back
 * newest-activity-first.
 *
 * Three defensive rules, each of which is routine rather than exotic:
 *
 * - **Orphaned parent → the message becomes its own root.** A page will regularly contain
 *   a reply whose parent is older than the page, archived, or deleted. Such a message
 *   surfaces as its own single-message thread; it is never dropped. The alternative —
 *   fetching missing parents — is an unbounded fetch chain for a cosmetic gain, so it is
 *   deliberately not done.
 * - **Cycles and self-parenting terminate.** A malformed chain (`a → b → a`, or `a → a`)
 *   is abandoned as soon as an id repeats, and the message is treated as its own root.
 *   Chains deeper than [MAX_PARENT_WALK_DEPTH] are treated the same way. Grouping is
 *   O(n · depth) and cannot hang.
 * - **Duplicate ids collapse.** The same message arriving from two folders (or two
 *   overlapping pages) is de-duplicated by [Message.id], first occurrence winning.
 *
 * Ordering within a thread is by parsed `sent_at` ascending, tie-broken by [Message.id].
 * Messages with an unparseable or absent `sent_at` sort LAST (by id) rather than to the
 * epoch — again, unknown is not "oldest possible". They also do not contribute to
 * [MessageThread.lastActivityAt].
 *
 * @param messages inbox + sent (+ archived) concatenated by the caller. Order irrelevant.
 * @param currentUsername the signed-in user's [RavelryUser.username]; compared
 *   case-insensitively. A blank value simply makes every message look inbound, which is
 *   the safe direction to be wrong in (it can over-report unread, never under-report).
 * @param mergeBySubjectFallback Pass `true` when any of [messages] came from the website
 *   scrape fallback ([com.myhobbyislearning.fibersocial.feed.MessagesPage.viaScrape],
 *   issue #396): scraped messages carry NO `parent_message_id`, so without this every
 *   message would surface as its own single-message thread. The fallback merges buckets
 *   consisting entirely of parentless messages when they share a counterpart (by
 *   username, case-insensitive) and a subject (case-insensitive, `Re:` prefixes
 *   stripped). It is a heuristic: two genuinely separate conversations with the same
 *   person under the same subject will merge — accepted, because that is also how the
 *   messages *read*, and the alternative (no conversations at all in scrape mode) is
 *   strictly worse. Buckets with a null counterpart or a blank subject never merge.
 *   JSON-sourced buckets (any member with a parent id) are never touched.
 */
fun groupIntoThreads(
    messages: List<Message>,
    currentUsername: String,
    mergeBySubjectFallback: Boolean = false,
): List<MessageThread> {
    if (messages.isEmpty()) return emptyList()

    val byId = LinkedHashMap<Long, Message>(messages.size)
    for (message in messages) if (!byId.containsKey(message.id)) byId[message.id] = message

    val roots = LinkedHashMap<Long, Message>()
    val buckets = LinkedHashMap<Long, MutableList<Message>>()
    for (message in byId.values) {
        val root = rootOf(message, byId)
        roots[root.id] = root
        buckets.getOrPut(root.id) { mutableListOf() } += message
    }

    if (mergeBySubjectFallback) mergeParentlessBucketsBySubject(roots, buckets, currentUsername)

    return buckets.map { (rootId, bucket) ->
        buildThread(roots.getValue(rootId), bucket, currentUsername)
    }.sortedWith(
        // Newest activity first; threads whose activity is entirely unknown sink to the
        // bottom rather than pretending to be from 1970. rootId breaks ties so the order
        // is stable across regroupings of the same data.
        compareByDescending<MessageThread, Long?>(nullsFirst()) { it.lastActivityAt }
            .thenByDescending { it.rootId },
    )
}

/**
 * Walks [message] up to its root, returning the root message itself.
 *
 * Two kinds of stop, deliberately different:
 *
 * - **No parent pointer, or a parent absent from [byId] (orphan)** — the highest message
 *   we could actually reach is the root. An orphaned reply therefore roots itself.
 * - **A cycle (including self-parenting), or a chain deeper than
 *   [MAX_PARENT_WALK_DEPTH]** — the chain is malformed, so [message] is treated as its
 *   own root. Rooting it at some arbitrary node inside the cycle instead would produce a
 *   thread whose `rootId` isn't one of its own messages.
 *
 * Always returns a message present in [byId], so every bucket contains its own root.
 */
private fun rootOf(message: Message, byId: Map<Long, Message>): Message {
    var current = message
    val visited = mutableSetOf(current.id)
    var depth = 0
    while (true) {
        val parentId = current.parentMessageId ?: return current
        // Checked here, gating the NEXT hop, rather than as the loop condition: the loop
        // condition form stops one hop short of the cap, because it never lets the walk
        // take its final permitted hop and then re-examine the message it lands on for a
        // null parent — a chain needing EXACTLY MAX_PARENT_WALK_DEPTH hops would land on
        // its true root but never get to check that root's own (null) parent, and would
        // incorrectly self-root instead. Gating the hop itself means a chain of exactly
        // the cap length still resolves fully; only a chain strictly longer bails.
        if (depth >= MAX_PARENT_WALK_DEPTH) return message
        val parent = byId[parentId] ?: return current
        if (!visited.add(parentId)) return message
        current = parent
        depth++
    }
}

/**
 * Whether this thread contains anything the signed-in user can actually reply to.
 *
 * Ravelry only accepts a reply to a message the user RECEIVED (`reply.json` rejects a
 * parent that doesn't list the current user as recipient), so a thread with no inbound
 * message — one the user started and nobody has answered — has no reply target. Shared
 * by `MessagesViewModel.sendReply`'s guard and the thread screen's disabled Reply
 * button so the two can never disagree about when a reply is possible.
 */
fun MessageThread.hasReplyTarget(currentUsername: String): Boolean =
    messages.any { messageDirection(it, currentUsername) == MessageDirection.INBOUND }

/**
 * The user-facing explanation for a conversation with no reply target — one string,
 * shared by the composer's error state and the thread screen's disabled-Reply notice.
 */
fun noReplyTargetMessage(counterpartUsername: String?): String {
    // "the other person" rather than "they": the sentence reads "once <name> writes
    // back", which needs a singular noun phrase.
    val name = counterpartUsername?.takeIf { it.isNotBlank() } ?: "the other person"
    return "Ravelry only lets you reply to a message someone sent you, and this " +
        "conversation has none yet — you can reply once $name writes back."
}

/**
 * The scrape-mode merge described on `groupIntoThreads`' `mergeBySubjectFallback`:
 * buckets made entirely of parentless messages are grouped by
 * `(counterpart username lowercase, subject with Re:-prefixes stripped and lowercased)`
 * and each group collapses into one bucket. The merged bucket's root is the oldest
 * merged root (by parsed `sent_at`, unknowns last, then id) so the resulting `rootId` is
 * deterministic across regroupings of the same data — the detail screen's identity for
 * the conversation depends on that stability.
 */
private fun mergeParentlessBucketsBySubject(
    roots: MutableMap<Long, Message>,
    buckets: LinkedHashMap<Long, MutableList<Message>>,
    currentUsername: String,
) {
    val mergeGroups = LinkedHashMap<Pair<String, String>, MutableList<Long>>()
    for ((rootId, bucket) in buckets) {
        if (bucket.any { it.parentMessageId != null }) continue
        val root = roots.getValue(rootId)
        val counterpart = counterpartOf(root, currentUsername)
            ?: bucket.firstNotNullOfOrNull { counterpartOf(it, currentUsername) }
            ?: continue
        val subjectSource = root.subject.takeIf { it.isNotBlank() }
            ?: bucket.firstOrNull { it.subject.isNotBlank() }?.subject
            ?: continue
        val normalized = normalizedSubject(subjectSource)
        if (normalized.isEmpty()) continue
        mergeGroups.getOrPut(counterpart.username.lowercase() to normalized) { mutableListOf() } += rootId
    }

    val oldestFirst = compareBy<Message, Long?>(nullsLast()) { sentAtMillis(it) }.thenBy { it.id }
    for (rootIds in mergeGroups.values) {
        if (rootIds.size < 2) continue
        val merged = rootIds.flatMap { buckets.getValue(it) }.toMutableList()
        val newRoot = rootIds.map { roots.getValue(it) }.minWith(oldestFirst)
        rootIds.forEach {
            buckets.remove(it)
            if (it != newRoot.id) roots.remove(it)
        }
        buckets[newRoot.id] = merged
    }
}

/** [subject] with every leading `Re:` stripped, trimmed, and lowercased. */
private fun normalizedSubject(subject: String): String =
    subject.replace(LEADING_RE_PREFIXES, "").trim().lowercase()

private val LEADING_RE_PREFIXES = Regex("""^(\s*re:\s*)+""", RegexOption.IGNORE_CASE)

/** Assembles one [MessageThread] from its root and the messages that resolved to it. */
private fun buildThread(
    root: Message,
    bucket: List<Message>,
    currentUsername: String,
): MessageThread {
    val ordered = bucket.sortedWith(
        compareBy<Message, Long?>(nullsLast()) { sentAtMillis(it) }.thenBy { it.id },
    )

    val subject = root.subject.takeIf { it.isNotBlank() }
        ?: ordered.firstOrNull { it.subject.isNotBlank() }?.subject
        ?: ""

    val counterpart = counterpartOf(root, currentUsername)
        ?: ordered.firstNotNullOfOrNull { counterpartOf(it, currentUsername) }

    val lastActivityAt = ordered.mapNotNull { sentAtMillis(it) }.maxOrNull()

    val hasUnread = ordered.any {
        messageDirection(it, currentUsername) == MessageDirection.INBOUND && !it.readMessage
    }

    return MessageThread(
        rootId = root.id,
        messages = ordered,
        subject = subject,
        counterpart = counterpart,
        lastActivityAt = lastActivityAt,
        hasUnread = hasUnread,
    )
}

/** Epoch millis of [message]'s `sent_at`, or `null` when absent or unparseable. */
private fun sentAtMillis(message: Message): Long? =
    parseRavelryTimestamp(message.sentAt)?.toEpochMilliseconds()

/** True when [user] is the signed-in user. Null users are nobody, so never a match. */
private fun isCurrentUser(user: RavelryUser?, currentUsername: String): Boolean =
    user != null && user.username.equals(currentUsername, ignoreCase = true)

/**
 * Whichever of sender/recipient is not the signed-in user, or `null` when neither names
 * someone else (both absent, or a message the user sent to themselves).
 */
private fun counterpartOf(message: Message, currentUsername: String): RavelryUser? {
    val sender = message.sender
    val recipient = message.recipient
    return when {
        sender != null && !isCurrentUser(sender, currentUsername) -> sender
        recipient != null && !isCurrentUser(recipient, currentUsername) -> recipient
        else -> null
    }
}

/**
 * Which way [message] travelled. Falls back to the recipient when the sender is absent,
 * and reports [MessageDirection.UNKNOWN] rather than guessing when neither party
 * identifies us.
 *
 * @param currentUsername the signed-in user's [RavelryUser.username], compared
 *   case-insensitively. A blank value makes every message look inbound — the same safe
 *   direction to be wrong in that [groupIntoThreads] documents.
 */
fun messageDirection(message: Message, currentUsername: String): MessageDirection {
    val sender = message.sender
    if (sender != null) {
        return if (isCurrentUser(sender, currentUsername)) {
            MessageDirection.OUTBOUND
        } else {
            MessageDirection.INBOUND
        }
    }
    if (isCurrentUser(message.recipient, currentUsername)) return MessageDirection.INBOUND
    return MessageDirection.UNKNOWN
}
