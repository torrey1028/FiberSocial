package com.autom8ed.fibersocial.notifications

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.json.Json

private val json = Json { ignoreUnknownKeys = true }

/**
 * SharedPreferences-backed [NotificationStateStore]. Plain (not encrypted) prefs: the
 * state is a list of public event permalinks and reminder times — nothing sensitive.
 */
class AndroidNotificationStateStore(private val prefs: SharedPreferences) : NotificationStateStore {

    constructor(context: Context) :
        this(context.getSharedPreferences("notification_state", Context.MODE_PRIVATE))

    override suspend fun load(): NotificationState? {
        val raw = prefs.getString(KEY, null) ?: return null
        return runCatching { json.decodeFromString<NotificationState>(raw) }.getOrNull()
    }

    override suspend fun save(state: NotificationState) {
        prefs.edit().putString(KEY, json.encodeToString(NotificationState.serializer(), state)).apply()
    }

    private companion object {
        const val KEY = "state"
    }
}

/** SharedPreferences-backed [NotificationSettingsStore]. */
class AndroidNotificationSettingsStore(private val prefs: SharedPreferences) : NotificationSettingsStore {

    constructor(context: Context) :
        this(context.getSharedPreferences("notification_settings", Context.MODE_PRIVATE))

    override suspend fun load(): NotificationSettings {
        val raw = prefs.getString(KEY, null) ?: return NotificationSettings()
        return runCatching { json.decodeFromString<NotificationSettings>(raw) }
            .getOrElse { NotificationSettings() }
    }

    override suspend fun save(settings: NotificationSettings) {
        prefs.edit().putString(KEY, json.encodeToString(NotificationSettings.serializer(), settings)).apply()
    }

    private companion object {
        const val KEY = "settings"
    }
}
