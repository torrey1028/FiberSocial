package com.autom8ed.fibersocial.storage

/**
 * Platform-agnostic key-value string storage. Implementations wrap the platform's
 * preferred mechanism (`SharedPreferences` on Android; `NSUserDefaults`/Keychain on iOS).
 */
interface KeyValueStore {
    /** Returns the stored value for [key], or `null` if nothing is stored. */
    suspend fun getString(key: String): String?

    /** Persists [value] under [key], overwriting any previous value. */
    suspend fun putString(key: String, value: String)

    /** Removes any stored value for [key]. A no-op if nothing was stored. */
    suspend fun remove(key: String)
}
