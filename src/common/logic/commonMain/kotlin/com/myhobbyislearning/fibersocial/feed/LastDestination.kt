package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The feed destination the user was last on, persisted so the app can reopen there
 * (issue #381) instead of always landing on the first group in the drawer's stored order.
 *
 * Deliberately a NEW store rather than an extension of [GroupLastViewedStore]: that
 * store's key space is group ids only (it cannot represent My Posts or Messages), its
 * timestamps are seeded for groups the user never opened, and its persist merges
 * forward-only — none of which can express "moved away from group X to My Posts".
 */
@Serializable
sealed class LastDestination {
    /** A group's topic feed. Only the id is stored — on restore it is resolved against
     *  the freshly fetched groups, so a group left since last launch degrades to the
     *  default group via `fetchFeed`'s existing fallback. */
    @Serializable
    @SerialName("group")
    data class Group(val id: Long) : LastDestination()

    /** The cross-group "My Posts" feed. */
    @Serializable
    @SerialName("my_posts")
    data object MyPosts : LastDestination()

    /** The Messages destination — a screen-level flag in `FeedScreen`, not part of
     *  `FeedState`, so its restore leg is applied there rather than in [FeedViewModel]. */
    @Serializable
    @SerialName("messages")
    data object Messages : LastDestination()
}

/** Persists the last viewed feed destination (issue #381). */
interface LastDestinationStore {
    /** The persisted destination, or `null` when nothing (readable) was ever saved. */
    suspend fun load(): LastDestination?

    suspend fun save(destination: LastDestination)
}

/**
 * [KeyValueStore]-backed [LastDestinationStore], following `KeyValueGroupLastViewedStore`'s
 * common-store shape on both platforms. [JsonKeyValueEntry.load] returns `null` on missing
 * OR corrupt stored data, which is exactly the "fall back to the first group" behaviour
 * the callers want for free.
 */
class KeyValueLastDestinationStore(store: KeyValueStore) : LastDestinationStore {
    private val entry = JsonKeyValueEntry(store, KEY, LastDestination.serializer())

    override suspend fun load(): LastDestination? = entry.load()

    override suspend fun save(destination: LastDestination) = entry.save(destination)

    private companion object {
        const val KEY = "last_destination"
    }
}
