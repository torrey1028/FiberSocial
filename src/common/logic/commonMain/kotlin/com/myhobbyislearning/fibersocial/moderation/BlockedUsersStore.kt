package com.myhobbyislearning.fibersocial.moderation

import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Local blocked-users list (issue #410 — Apple Guideline 1.2's "block abusive users"
 * mechanism). Ravelry has no block/mute API of its own (confirmed during the direct-
 * messages epic, #365), so this is entirely client-side: a set of Ravelry usernames whose
 * content this app hides everywhere it renders — feed cards, topic replies, and
 * conversations.
 *
 * Unlike the plain load/save stores elsewhere in this module (e.g.
 * [com.myhobbyislearning.fibersocial.notifications.MutedTopicsStore]), this store exposes a
 * reactive [blockedUsernames] that updates the instant [block]/[unblock] is called. Apple's
 * requirement is that a blocked user's content disappear *instantly*, not on the next
 * screen refresh or app restart, so every surface that renders user content collects this
 * flow directly rather than reading a one-shot snapshot — see `filterBlocked` in
 * `FeedScreen.kt` and the post-list filtering in `TopicDetailScreen.kt`.
 *
 * A single instance must be shared across the whole authenticated app tree (the same
 * convention `MutedTopicsStore`/`NotificationSettingsStore` already follow — constructed
 * once via `remember` in `MainActivity`/`MainViewController` and threaded down through
 * `FeedScreen`) for that reactivity to reach every screen; a fresh instance per call site
 * would each hold its own, independently-loaded copy of [blockedUsernames].
 */
interface BlockedUsersStore {
    /**
     * The currently blocked usernames. Empty until [load] completes — callers must call
     * [load] once, early (mirroring how `ThemeSettingsStore` callers call `load()` before
     * anything depends on the result), typically right after constructing the store.
     */
    val blockedUsernames: StateFlow<Set<String>>

    /** Loads the persisted set into [blockedUsernames]. Safe to call more than once. */
    suspend fun load()

    /** Adds [username] to the blocked set: updates [blockedUsernames] immediately, then persists. */
    suspend fun block(username: String)

    /** Removes [username] from the blocked set: updates [blockedUsernames] immediately, then persists. */
    suspend fun unblock(username: String)
}

/**
 * True if this set of blocked usernames contains [username]. Compared case-insensitively —
 * Ravelry usernames are case-preserving but not case-distinct (the same convention
 * `MessageThreads.kt` documents for sender/recipient identity). `null` (e.g. a post whose
 * author Ravelry omitted) is never contained. The single shared implementation of the
 * blocked-username check — every surface that filters blocked content (feed cards, topic
 * replies, project comments, messages) goes through this, not its own `any { equals }`.
 */
fun Set<String>.containsUsername(username: String?): Boolean =
    username != null && any { it.equals(username, ignoreCase = true) }

/**
 * True if [username] is currently blocked, per this store's latest known state. See
 * [containsUsername] for the comparison rules.
 */
fun BlockedUsersStore.isBlocked(username: String?): Boolean =
    blockedUsernames.value.containsUsername(username)

/** [BlockedUsersStore] backed by a [KeyValueStore]. */
class KeyValueBlockedUsersStore(store: KeyValueStore) : BlockedUsersStore {
    private val entry = JsonKeyValueEntry(store, KEY, SetSerializer(String.serializer()))
    private val _blockedUsernames = MutableStateFlow<Set<String>>(emptySet())
    override val blockedUsernames: StateFlow<Set<String>> = _blockedUsernames.asStateFlow()

    override suspend fun load() {
        mutex.withLock {
            _blockedUsernames.value = entry.load() ?: emptySet()
        }
    }

    // Ravelry usernames are case-preserving but not case-distinct (see isBlocked's doc):
    // the casing a post's author renders with can differ from the casing a later profile
    // fetch echoes back for the same account. Set's default equals()/hashCode() is
    // case-sensitive, so block/unblock must compare case-insensitively themselves rather
    // than delegate to plain Set +/-, or unblock() from a differently-cased call site
    // would silently no-op instead of removing the stored entry.
    override suspend fun block(username: String) = mutate { current ->
        if (current.containsUsername(username)) current else current + username
    }

    override suspend fun unblock(username: String) = mutate { current ->
        current.filterNot { it.equals(username, ignoreCase = true) }.toSet()
    }

    // Process-wide (not per-instance), mirroring KeyValueMutedTopicsStore.mutate's
    // reasoning: guards the load-modify-save sequence against two rapid block/unblock taps
    // (or a concurrent load()) racing each other and silently losing one. Re-reads from
    // the backing store rather than transforming this instance's in-memory
    // _blockedUsernames — the app only ever shares ONE instance in practice (see the class
    // KDoc), but a stale in-memory read would silently lose a concurrent instance's write
    // even though the mutex correctly serializes the two calls in time, since "serialized"
    // isn't "merged" when each side transforms its own stale snapshot.
    private suspend fun mutate(transform: (Set<String>) -> Set<String>) {
        mutex.withLock {
            val current = entry.load() ?: emptySet()
            val updated = transform(current)
            // Published before the persistence write completes, so every collector
            // (feed, topic detail, messages, the blocked-users list) reacts to this call
            // directly rather than waiting on a disk round-trip — the "instant" half of
            // Apple's requirement.
            _blockedUsernames.value = updated
            if (updated != current) entry.save(updated)
        }
    }

    private companion object {
        const val KEY = "blocked_usernames"
        val mutex = Mutex()
    }
}
