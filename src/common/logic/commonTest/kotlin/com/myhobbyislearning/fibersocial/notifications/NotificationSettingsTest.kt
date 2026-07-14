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
