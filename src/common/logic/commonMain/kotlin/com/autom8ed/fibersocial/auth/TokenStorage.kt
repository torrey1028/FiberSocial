package com.autom8ed.fibersocial.auth

/**
 * Platform-agnostic contract for persisting and retrieving OAuth tokens.
 *
 * Implementations are expected to store tokens securely (e.g. `EncryptedSharedPreferences`
 * on Android). All methods are suspend to allow I/O on any dispatcher.
 */
interface TokenStorage {
    /** Persists [token], overwriting any previously stored value. */
    suspend fun save(token: AuthToken)

    /**
     * Returns the stored [AuthToken], or `null` if no token has been saved
     * or the stored data is incomplete.
     */
    suspend fun load(): AuthToken?

    /** Removes all stored token data (called on logout). */
    suspend fun clear()
}
