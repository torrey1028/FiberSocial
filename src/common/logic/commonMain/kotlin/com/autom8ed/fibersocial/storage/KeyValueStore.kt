package com.autom8ed.fibersocial.storage

/**
 * Platform-agnostic key-value string storage. Implementations wrap the platform's
 * preferred mechanism (`SharedPreferences` on Android; `NSUserDefaults`/Keychain on iOS).
 */
interface KeyValueStore {
    /** Returns the stored value for [key], or `null` if nothing is stored. */
    suspend fun getString(key: String): String?

    /**
     * Persists [value] under [key], overwriting any previous value.
     *
     * Returning does not guarantee the write has reached disk — implementations may queue
     * it asynchronously (Android's `SharedPreferences.Editor.apply()` does). A process
     * killed immediately after this returns can still lose the write; this is fine for the
     * data this store holds (auth tokens, notification state/settings — worst case is a
     * re-login or a resync), but callers needing a durable-on-return guarantee should not
     * assume one from the `suspend` signature alone.
     */
    suspend fun putString(key: String, value: String)

    /** Removes any stored value for [key]. A no-op if nothing was stored. Same durability caveat as [putString]. */
    suspend fun remove(key: String)
}
