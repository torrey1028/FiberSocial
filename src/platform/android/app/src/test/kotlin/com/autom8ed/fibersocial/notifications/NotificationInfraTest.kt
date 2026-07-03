package com.autom8ed.fibersocial.notifications

import android.app.AlarmManager
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AndroidNotificationStoresTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `state store round-trips and starts null`() = runTest {
        val prefs = context.getSharedPreferences("test_state", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AndroidNotificationStateStore(prefs)
        assertNull(store.load())

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
    fun `corrupt state degrades to null`() = runTest {
        val prefs = context.getSharedPreferences("test_state2", Context.MODE_PRIVATE)
        prefs.edit().putString("state", "not json").commit()
        assertNull(AndroidNotificationStateStore(prefs).load())
    }

    @Test
    fun `settings store defaults to 6 hours and round-trips`() = runTest {
        val prefs = context.getSharedPreferences("test_settings", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        val store = AndroidNotificationSettingsStore(prefs)
        assertEquals(NotificationSettings.DEFAULT_POLL_INTERVAL_HOURS, store.load().pollIntervalHours)

        store.save(NotificationSettings(pollIntervalHours = 12))
        assertEquals(12, store.load().pollIntervalHours)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ReminderSchedulerTest {

    private val context = RuntimeEnvironment.getApplication()
    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val scheduler = ReminderScheduler(context)

    private val reminder = ScheduledReminder(
        eventPermalink = "cozy-meetup",
        eventTitle = "Cozy Meetup",
        fireAtEpochMs = 1_800_000_000_000L,
        kind = ReminderKind.SOON,
    )

    @Test
    fun `schedule registers an exact wakeup alarm at the fire time`() {
        scheduler.schedule(reminder)
        val scheduled = shadowOf(alarmManager).scheduledAlarms.single()
        assertEquals(reminder.fireAtEpochMs, scheduled.triggerAtMs)
        assertEquals(AlarmManager.RTC_WAKEUP, scheduled.type)
    }

    @Test
    fun `the two kinds for one event are separate alarms`() {
        scheduler.schedule(reminder)
        scheduler.schedule(reminder.copy(kind = ReminderKind.DAY_BEFORE, fireAtEpochMs = 1_700_000_000_000L))
        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `cancel removes the matching alarm`() {
        scheduler.schedule(reminder)
        scheduler.cancel(reminder)
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }

    @Test
    fun `rescheduling the identical reminder is idempotent`() {
        scheduler.schedule(reminder)
        scheduler.schedule(reminder)
        assertEquals(1, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `occurrences of one event keep distinct alarms and a move cancels only its own`() {
        // A recurring event lists one reminder pair per occurrence under one
        // permalink: identity is (event, kind, fireAt), so occurrences must not
        // collapse into a single alarm...
        scheduler.schedule(reminder)
        val secondOccurrence = reminder.copy(fireAtEpochMs = reminder.fireAtEpochMs + 60_000)
        scheduler.schedule(secondOccurrence)
        assertEquals(2, shadowOf(alarmManager).scheduledAlarms.size)

        // ...and a move (the planner emits cancel-old + schedule-new) removes exactly
        // the moved occurrence's alarm, not its siblings'.
        scheduler.cancel(reminder)
        val remaining = shadowOf(alarmManager).scheduledAlarms.single()
        assertEquals(secondOccurrence.fireAtEpochMs, remaining.triggerAtMs)
    }
}
