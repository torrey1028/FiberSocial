package com.autom8ed.fibersocial.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class AndroidTokenStorage(private val prefs: SharedPreferences) : TokenStorage {

    constructor(context: Context) : this(
        EncryptedSharedPreferences.create(
            "fibersocial_auth",
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    )

    override suspend fun save(token: AuthToken) {
        prefs.edit()
            .putString("access_token", token.accessToken)
            .putString("refresh_token", token.refreshToken)
            .putLong("expires_at", token.expiresAt)
            .putString("session_cookie", token.sessionCookie)
            .apply()
    }

    override suspend fun load(): AuthToken? {
        val accessToken = prefs.getString("access_token", null) ?: return null
        val refreshToken = prefs.getString("refresh_token", null) ?: return null
        val expiresAt = prefs.getLong("expires_at", 0L)
        val sessionCookie = prefs.getString("session_cookie", null)
        return AuthToken(accessToken, refreshToken, expiresAt, sessionCookie)
    }

    override suspend fun clear() {
        prefs.edit().clear().apply()
    }
}
