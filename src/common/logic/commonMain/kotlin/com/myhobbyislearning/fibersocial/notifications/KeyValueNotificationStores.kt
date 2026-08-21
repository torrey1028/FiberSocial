package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.storage.JsonKeyValueEntry
import com.myhobbyislearning.fibersocial.storage.KeyValueStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

/**
 * [SubscribedGroupsStore] backed by a [KeyValueStore]. Shares the notification-state store
 * (a distinct [KEY] within it), same as [KeyValueMutedTopicsStore].
 */
class KeyValueSubscribedGroupsStore(store: KeyValueStore) : SubscribedGroupsStore {
    private val entry = JsonKeyValueEntry(store, KEY, SetSerializer(Long.serializer()))
    private val _subscribedGroupIds = MutableStateFlow<Set<Long>>(emptySet())
    override val subscribedGroupIds: StateFlow<Set<Long>> = _subscribedGroupIds.asStateFlow()

    override suspend fun load() {
        mutex.withLock { _subscribedGroupIds.value = entry.load() ?: emptySet() }
    }

    override suspend fun setSubscribed(groupId: Long, subscribed: Boolean) = mutate { current ->
        if (subscribed) current + groupId else current - groupId
    }

    override suspend fun retainAll(groupIds: Set<Long>) = mutate { it.intersect(groupIds) }

    // Process-wide (not per-instance), for the same reason KeyValueMutedTopicsStore.mutate
    // documents: the UI and the background sync each construct their own store, so an
    // instance lock wouldn't serialize between them. Re-reads from the backing store rather
    // than transforming the in-memory flow, so a concurrent instance's write isn't lost.
    private suspend fun mutate(transform: (Set<Long>) -> Set<Long>) {
        mutex.withLock {
            val current = entry.load() ?: emptySet()
            val updated = transform(current)
            // Published before the write lands so the subscribe control flips on the tap.
            _subscribedGroupIds.value = updated
            if (updated != current) entry.save(updated)
        }
    }

    private companion object {
        const val KEY = "subscribed_groups"
        val mutex = Mutex()
    }
}
