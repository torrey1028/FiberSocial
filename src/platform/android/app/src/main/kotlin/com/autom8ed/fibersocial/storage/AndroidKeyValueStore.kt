package com.autom8ed.fibersocial.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/** [KeyValueStore] over a [SharedPreferences] instance, plain or encrypted alike. */
class SharedPreferencesKeyValueStore(private val prefs: SharedPreferences) : KeyValueStore {
    override suspend fun getString(key: String): String? = prefs.getString(key, null)

    override suspend fun putString(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    override suspend fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

/** `SharedPreferences` file names, centralized so every call site agrees on them. */
const val AUTH_PREFS_NAME = "fibersocial_auth"
const val NOTIFICATION_STATE_PREFS_NAME = "notification_state"
const val NOTIFICATION_SETTINGS_PREFS_NAME = "notification_settings"

/**
 * Plain `SharedPreferences`-backed store — for non-sensitive data.
 *
 * Uses [Context.getApplicationContext] regardless of what [context] is, so a store built
 * from (and potentially `remember`-cached alongside) an Activity context never retains a
 * reference to it — matching [encryptedKeyValueStore]'s existing convention.
 */
fun plainKeyValueStore(context: Context, name: String): KeyValueStore =
    SharedPreferencesKeyValueStore(context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE))

/** `EncryptedSharedPreferences`-backed store — for secrets like OAuth tokens. */
fun encryptedKeyValueStore(context: Context, name: String): KeyValueStore =
    SharedPreferencesKeyValueStore(
        EncryptedSharedPreferences.create(
            name,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ),
    )
