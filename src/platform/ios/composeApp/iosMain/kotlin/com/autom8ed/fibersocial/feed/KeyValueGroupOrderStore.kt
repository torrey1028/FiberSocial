package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.storage.KeyValueStore
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * [KeyValueStore]-backed [GroupOrderStore] — same JSON blob + decode-fallback shape as
 * Android's `AndroidGroupOrderStore`, over the platform store instead of
 * `SharedPreferences`.
 */
class KeyValueGroupOrderStore(private val store: KeyValueStore) : GroupOrderStore {

    override suspend fun load(): List<Long>? {
        val raw = store.getString(KEY) ?: return null
        return runCatching { json.decodeFromString<List<Long>>(raw) }.getOrNull()
    }

    override suspend fun save(order: List<Long>) {
        store.putString(KEY, json.encodeToString(order))
    }

    private companion object {
        const val KEY = "order"
    }
}
