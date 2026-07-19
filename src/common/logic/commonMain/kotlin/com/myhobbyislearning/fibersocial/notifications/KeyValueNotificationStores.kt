package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer

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

/**
 * [MutedTopicsStore] backed by a [KeyValueStore]. Shares the notification-state store
 * (a distinct [KEY] within it), so no new per-platform prefs/suite name is needed.
 */
class KeyValueMutedTopicsStore(store: KeyValueStore) : MutedTopicsStore {
    private val entry = JsonKeyValueEntry(store, KEY, SetSerializer(Long.serializer()))

    override suspend fun load(): Set<Long> = entry.load() ?: emptySet()

    override suspend fun save(mutedTopicIds: Set<Long>) = entry.save(mutedTopicIds)

    // Process-wide (not per-instance): the UI and the background sync each construct
    // their own KeyValueMutedTopicsStore, so an instance-level lock wouldn't serialize
    // between them — they need to contend for the same lock object.
    override suspend fun mutate(transform: (Set<Long>) -> Set<Long>): Set<Long> = mutex.withLock {
        val current = load()
        val updated = transform(current)
        if (updated != current) save(updated)
        updated
    }

    private companion object {
        const val KEY = "muted_topics"
        val mutex = Mutex()
    }
}
