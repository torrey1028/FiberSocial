package com.myhobbyislearning.fibersocial.storage

/** In-memory [KeyValueStore] for tests. */
class FakeKeyValueStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()

    override suspend fun getString(key: String): String? = values[key]

    override suspend fun putString(key: String, value: String) {
        values[key] = value
    }

    override suspend fun remove(key: String) {
        values.remove(key)
    }
}
