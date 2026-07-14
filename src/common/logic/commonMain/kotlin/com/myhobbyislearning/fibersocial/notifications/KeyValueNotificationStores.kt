package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore

/**
 * [NotificationStateStore] backed by a [KeyValueStore]. Plain (non-secure) storage is
 * fine here — the state is a list of public event permalinks and reminder times.
 */
class KeyValueNotificationStateStore(store: KeyValueStore) : NotificationStateStore {
    private val entry = JsonKeyValueEntry(store, KEY, NotificationState.serializer())

    override suspend fun load(): NotificationState? = entry.load()

    override suspend fun save(state: NotificationState) = entry.save(state)

    private companion object {
        const val KEY = "state"
    }
}

/** [NotificationSettingsStore] backed by a [KeyValueStore]. */
class KeyValueNotificationSettingsStore(store: KeyValueStore) : NotificationSettingsStore {
    private val entry = JsonKeyValueEntry(store, KEY, NotificationSettings.serializer())

    override suspend fun load(): NotificationSettings = entry.load() ?: NotificationSettings()

    override suspend fun save(settings: NotificationSettings) = entry.save(settings)

    private companion object {
        const val KEY = "settings"
    }
}
