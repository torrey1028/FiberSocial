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

/**
 * `EncryptedSharedPreferences`-backed store — for secrets like OAuth tokens.
 *
 * Self-healing: an uninstall/reinstall cycle can leave the Tink keyset in the prefs
 * file undecryptable (its Keystore master key is gone, or auto-backup restored a stale
 * keyset), which used to crash the app on launch with AndroidKeysetManager errors
 * until a manual `adb shell pm clear`. Opening the store now recovers by wiping the
 * corrupted prefs file and recreating it — the stored token is unreadable either way,
 * so the only cost is the sign-in the user would have needed anyway.
 */
fun encryptedKeyValueStore(context: Context, name: String): KeyValueStore {
    val appContext = context.applicationContext
    return SharedPreferencesKeyValueStore(
        createSelfHealing(
            create = { encryptedPrefs(appContext, name) },
            wipeCorrupted = { appContext.deleteSharedPreferences(name) },
        ),
    )
}

private fun encryptedPrefs(appContext: Context, name: String): SharedPreferences =
    EncryptedSharedPreferences.create(
        name,
        MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
        appContext,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )

/**
 * Runs [create], and on failure wipes the corrupted state via [wipeCorrupted] and tries
 * once more. A second failure propagates — that's a genuine bug, not stale state, and
 * must stay loud.
 */
internal fun <T> createSelfHealing(create: () -> T, wipeCorrupted: () -> Unit): T =
    try {
        create()
    } catch (e: Exception) {
        println("FiberSocial: encrypted prefs unreadable (${e.message}); wiping and recreating")
        wipeCorrupted()
        create()
    }
