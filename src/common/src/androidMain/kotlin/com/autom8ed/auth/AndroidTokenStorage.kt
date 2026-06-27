package com.autom8ed.auth

import android.content.Context

class AndroidTokenStorage(private val context: Context) : TokenStorage {

    override suspend fun save(token: AuthToken) {
        TODO("Phase 3: save to EncryptedSharedPreferences backed by Android Keystore")
    }

    override suspend fun load(): AuthToken? {
        TODO("Phase 3: load from EncryptedSharedPreferences")
    }

    override suspend fun clear() {
        TODO("Phase 3: clear from EncryptedSharedPreferences")
    }
}
