package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.storage.FakeKeyValueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KeyValueNotificationStateStoreTest {
    @Test
    fun `load returns null when nothing saved`() = runTest {
        assertNull(KeyValueNotificationStateStore(FakeKeyValueStore()).load())
    }

    @Test
    fun `save then load round-trips`() = runTest {
        val store = KeyValueNotificationStateStore(FakeKeyValueStore())
        val state = NotificationState(
            knownEvents = mapOf("cozy-meetup" to 123L),
            scheduledReminders = listOf(
                ScheduledReminder("cozy-meetup", "Cozy Meetup", 456L, ReminderKind.SOON),
            ),
        )
        store.save(state)
        assertEquals(state, store.load())
    }

    @Test
    fun `corrupt data degrades to null`() = runTest {
        val fake = FakeKeyValueStore()
        fake.putString("state", "not json")
        assertNull(KeyValueNotificationStateStore(fake).load())
    }

    @Test
    fun `state persisted before knownTopics existed still loads`() = runTest {
        // JSON captured from the pre-my-posts schema: no knownTopics key.
        val fake = FakeKeyValueStore()
        fake.putString(
            "state",
            """{"knownEvents":{"cozy-meetup":123},"scheduledReminders":[]}""",
        )
        val state = KeyValueNotificationStateStore(fake).load()
        assertEquals(mapOf("cozy-meetup" to 123L), state?.knownEvents)
        assertEquals(emptyMap(), state?.knownTopics)
    }

    @Test
    fun `knownTopics round-trips`() = runTest {
        val store = KeyValueNotificationStateStore(FakeKeyValueStore())
        val state = NotificationState(
            knownTopics = mapOf(500L to KnownTopicActivity(postCount = 7, lastSeenMs = 123L)),
        )
        store.save(state)
        assertEquals(state, store.load())
    }
}

class KeyValueNotificationSettingsStoreTest {
    @Test
    fun `load defaults when nothing saved`() = runTest {
        val store = KeyValueNotificationSettingsStore(FakeKeyValueStore())
        assertEquals(NotificationSettings.DEFAULT_POLL_CADENCE, store.load().effectivePollCadence)
    }

    @Test
    fun `save then load round-trips`() = runTest {
        val store = KeyValueNotificationSettingsStore(FakeKeyValueStore())
        store.save(NotificationSettings(pollCadence = PollCadence.ONCE_A_DAY))
        assertEquals(PollCadence.ONCE_A_DAY, store.load().effectivePollCadence)
    }

    @Test
    fun `corrupt data degrades to defaults`() = runTest {
        val fake = FakeKeyValueStore()
        fake.putString("settings", "not json")
        assertEquals(
            NotificationSettings.DEFAULT_POLL_CADENCE,
            KeyValueNotificationSettingsStore(fake).load().effectivePollCadence,
        )
    }
}

class KeyValueMutedTopicsStoreTest {
    @Test
    fun `load returns empty when nothing saved`() = runTest {
        assertEquals(emptySet(), KeyValueMutedTopicsStore(FakeKeyValueStore()).load())
    }

    @Test
    fun `save then load round-trips`() = runTest {
        val store = KeyValueMutedTopicsStore(FakeKeyValueStore())
        store.save(setOf(500L, 731L))
        assertEquals(setOf(500L, 731L), store.load())
    }

    @Test
    fun `corrupt data degrades to empty`() = runTest {
        val fake = FakeKeyValueStore()
        fake.putString("muted_topics", "not json")
        assertEquals(emptySet(), KeyValueMutedTopicsStore(fake).load())
    }

    @Test
    fun `it shares the state store without colliding on keys`() = runTest {
        // Both stores are backed by the same KeyValueStore in production (they use
        // distinct keys); saving one must not disturb the other.
        val backing = FakeKeyValueStore()
        KeyValueNotificationStateStore(backing).save(
            NotificationState(knownTopics = mapOf(500L to KnownTopicActivity(7, 123L))),
        )
        KeyValueMutedTopicsStore(backing).save(setOf(500L))

        assertEquals(setOf(500L), KeyValueMutedTopicsStore(backing).load())
        assertEquals(
            mapOf(500L to KnownTopicActivity(7, 123L)),
            KeyValueNotificationStateStore(backing).load()?.knownTopics,
        )
    }
}
