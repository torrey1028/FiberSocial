package com.autom8ed.fibersocial.storage

import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

private val defaultJson = Json { ignoreUnknownKeys = true }

/**
 * Persists one JSON-serialized value under [key] in a [KeyValueStore]. Missing or corrupt
 * stored data falls back to `null` from [load] rather than throwing — the store classes
 * built on this decide their own default from there.
 */
class JsonKeyValueEntry<T>(
    private val store: KeyValueStore,
    private val key: String,
    private val serializer: KSerializer<T>,
    private val json: Json = defaultJson,
) {
    suspend fun load(): T? {
        val raw = store.getString(key) ?: return null
        return runCatching { json.decodeFromString(serializer, raw) }.getOrNull()
    }

    suspend fun save(value: T) {
        store.putString(key, json.encodeToString(serializer, value))
    }

    suspend fun clear() {
        store.remove(key)
    }
}
