package com.autom8ed.auth

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class AndroidTokenStorage(context: Context) : TokenStorage {

    private val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)

    private val prefs = EncryptedSharedPreferences.create(
        "fibersocial_auth",
        masterKeyAlias,
        context.applicationContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

    override suspend fun save(token: AuthToken) {
        prefs.edit()
            .putString("access_token", token.accessToken)
            .putString("refresh_token", token.refreshToken)
            .putLong("expires_at", token.expiresAt)
            .apply()
    }

    override suspend fun load(): AuthToken? {
        val accessToken = prefs.getString("access_token", null) ?: return null
        val refreshToken = prefs.getString("refresh_token", null) ?: return null
        val expiresAt = prefs.getLong("expires_at", 0L)
        return AuthToken(accessToken, refreshToken, expiresAt)
    }

    override suspend fun clear() {
        prefs.edit().clear().apply()
    }
}
