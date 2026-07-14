package com.myhobbyislearning.fibersocial.auth

import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore

/**
 * [TokenStorage] backed by a [KeyValueStore]. Callers should pass a store backed by
 * secure storage (e.g. `EncryptedSharedPreferences` on Android, Keychain on iOS) since
 * this persists OAuth credentials.
 */
class KeyValueTokenStorage(store: KeyValueStore) : TokenStorage {
    private val entry = JsonKeyValueEntry(store, KEY, AuthToken.serializer())

    override suspend fun save(token: AuthToken) = entry.save(token)

    override suspend fun load(): AuthToken? = entry.load()

    override suspend fun clear() = entry.clear()

    private companion object {
        const val KEY = "token"
    }
}
