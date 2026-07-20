package com.myhobbyislearning.fibersocial.notifications

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.Json

class NotificationSettingsTest {
    @Test
    fun `default settings resolve to the default cadence`() {
        assertEquals(NotificationSettings.DEFAULT_POLL_CADENCE, NotificationSettings().effectivePollCadence)
    }

    @Test
    fun `explicit cadence takes precedence`() {
        for (cadence in PollCadence.entries) {
            assertEquals(cadence, NotificationSettings(pollCadence = cadence).effectivePollCadence)
        }
    }

    @Test
    fun `a legacy pre-migration value with no cadence set migrates to the nearest bucket`() {
        // Simulates deserializing JSON saved before this migration: pollCadence is
        // absent (null), only the old precise-hours field is populated.
        assertEquals(
            PollCadence.ONCE_A_DAY,
            NotificationSettings(pollIntervalHours = 12).effectivePollCadence,
        )
        assertEquals(
            PollCadence.HOURLY,
            NotificationSettings(pollIntervalHours = 1).effectivePollCadence,
        )
    }

    @Test
    fun `deserializing pre-migration JSON with no pollCadence key migrates cleanly`() {
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<NotificationSettings>("""{"pollIntervalHours":12}""")
        assertEquals(PollCadence.ONCE_A_DAY, legacy.effectivePollCadence)
    }

    @Test
    fun `pollCadence holds exactly what was constructed with`() {
        assertEquals(PollCadence.HOURLY, NotificationSettings(pollCadence = PollCadence.HOURLY).pollCadence)
        assertEquals(null, NotificationSettings().pollCadence)
    }

    @Test
    fun `pollIntervalHours holds exactly what was constructed with`() {
        assertEquals(12, NotificationSettings(pollIntervalHours = 12).pollIntervalHours)
        assertEquals(6, NotificationSettings().pollIntervalHours)
    }

    @Test
    fun `every notification kind defaults on`() {
        val settings = NotificationSettings()
        assertEquals(true, settings.eventRemindersEnabled)
        assertEquals(true, settings.newGroupEventsEnabled)
        assertEquals(true, settings.topicRepliesEnabled)
        assertEquals(true, settings.newMessagesEnabled)
    }

    @Test
    fun `JSON saved before the per-kind toggles deserializes with every kind on`() {
        // Pre-#335 stored settings have no per-kind keys; the defaulted fields must keep
        // the old always-notify behavior rather than silently muting a kind.
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<NotificationSettings>("""{"pollCadence":"HOURLY"}""")
        assertEquals(true, legacy.eventRemindersEnabled)
        assertEquals(true, legacy.newGroupEventsEnabled)
        assertEquals(true, legacy.topicRepliesEnabled)
        assertEquals(true, legacy.newMessagesEnabled)
    }

    @Test
    fun `JSON saved before the new-messages toggle deserializes with it on`() {
        // The exact shape a pre-#375 install has on disk: every #335 key present, no
        // newMessagesEnabled key. It must default on rather than silently shipping the
        // messages leg disabled for every upgrading user.
        val json = Json { ignoreUnknownKeys = true }
        val legacy = json.decodeFromString<NotificationSettings>(
            """{"pollCadence":"HOURLY","eventRemindersEnabled":true,""" +
                """"newGroupEventsEnabled":false,"topicRepliesEnabled":true}""",
        )
        assertEquals(true, legacy.newMessagesEnabled)
        // ...without disturbing the toggles that WERE stored.
        assertEquals(false, legacy.newGroupEventsEnabled)
    }

    @Test
    fun `per-kind toggles survive a JSON round trip`() {
        val json = Json
        val settings = NotificationSettings(
            pollCadence = PollCadence.ONCE_A_DAY,
            eventRemindersEnabled = false,
            newGroupEventsEnabled = true,
            topicRepliesEnabled = false,
            newMessagesEnabled = false,
        )
        assertEquals(settings, json.decodeFromString(json.encodeToString(settings)))
    }

    @Test
    fun `the new-messages toggle holds exactly what was constructed with`() {
        assertEquals(false, NotificationSettings(newMessagesEnabled = false).newMessagesEnabled)
        assertEquals(true, NotificationSettings().newMessagesEnabled)
    }
}

class PollCadenceFromHoursTest {
    @Test
    fun `an hour or less buckets to hourly`() {
        assertEquals(PollCadence.HOURLY, PollCadence.fromHours(1))
        assertEquals(PollCadence.HOURLY, PollCadence.fromHours(0))
    }

    @Test
    fun `a few hours to just under half a day buckets to a few times a day`() {
        assertEquals(PollCadence.A_FEW_TIMES_A_DAY, PollCadence.fromHours(3))
        assertEquals(PollCadence.A_FEW_TIMES_A_DAY, PollCadence.fromHours(6))
        assertEquals(PollCadence.A_FEW_TIMES_A_DAY, PollCadence.fromHours(11))
    }

    @Test
    fun `half a day or more buckets to once a day`() {
        assertEquals(PollCadence.ONCE_A_DAY, PollCadence.fromHours(12))
        assertEquals(PollCadence.ONCE_A_DAY, PollCadence.fromHours(24))
        assertEquals(PollCadence.ONCE_A_DAY, PollCadence.fromHours(1000))
    }
}

class PollCadenceLabelTest {
    @Test
    fun `every cadence has a distinct label`() {
        val labels = PollCadence.entries.map { pollCadenceLabel(it) }
        assertEquals(PollCadence.entries.size, labels.toSet().size)
    }

    @Test
    fun `labels match the copy from the plan`() {
        assertEquals("Hourly", pollCadenceLabel(PollCadence.HOURLY))
        assertEquals("A few times a day", pollCadenceLabel(PollCadence.A_FEW_TIMES_A_DAY))
        assertEquals("Once a day", pollCadenceLabel(PollCadence.ONCE_A_DAY))
    }
}
