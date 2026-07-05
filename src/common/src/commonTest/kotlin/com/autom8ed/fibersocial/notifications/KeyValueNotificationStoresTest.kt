package com.autom8ed.fibersocial.notifications

import com.autom8ed.fibersocial.storage.FakeKeyValueStore
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
}

class KeyValueNotificationSettingsStoreTest {
    @Test
    fun `load defaults when nothing saved`() = runTest {
        val store = KeyValueNotificationSettingsStore(FakeKeyValueStore())
        assertEquals(NotificationSettings.DEFAULT_POLL_INTERVAL_HOURS, store.load().pollIntervalHours)
    }

    @Test
    fun `save then load round-trips`() = runTest {
        val store = KeyValueNotificationSettingsStore(FakeKeyValueStore())
        store.save(NotificationSettings(pollIntervalHours = 12))
        assertEquals(12, store.load().pollIntervalHours)
    }

    @Test
    fun `corrupt data degrades to defaults`() = runTest {
        val fake = FakeKeyValueStore()
        fake.putString("settings", "not json")
        assertEquals(
            NotificationSettings.DEFAULT_POLL_INTERVAL_HOURS,
            KeyValueNotificationSettingsStore(fake).load().pollIntervalHours,
        )
    }
}
