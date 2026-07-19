package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * [KeyValueStore]-backed [GroupLastViewedStore] — same JSON blob + decode-fallback shape
 * as Android's `AndroidGroupLastViewedStore`, over the platform store instead of
 * `SharedPreferences`.
 */
class KeyValueGroupLastViewedStore(private val store: KeyValueStore) : GroupLastViewedStore {

    override suspend fun load(): Map<Long, Long>? {
        val raw = store.getString(KEY) ?: return null
        return runCatching { json.decodeFromString<Map<Long, Long>>(raw) }.getOrNull()
    }

    override suspend fun save(lastViewed: Map<Long, Long>) {
        store.putString(KEY, json.encodeToString(lastViewed))
    }

    private companion object {
        const val KEY = "last_viewed"
    }
}
