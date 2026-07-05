package com.autom8ed.fibersocial.notifications

import android.app.AlarmManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Looper
import com.autom8ed.fibersocial.storage.NOTIFICATION_STATE_PREFS_NAME
import com.autom8ed.fibersocial.storage.plainKeyValueStore
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BootReceiverTest {

    private lateinit var app: Application
    private lateinit var alarmManager: AlarmManager

    @Before
    fun setUp() {
        app = RuntimeEnvironment.getApplication()
        alarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    }

    private suspend fun seedState(vararg reminders: ScheduledReminder) {
        KeyValueNotificationStateStore(plainKeyValueStore(app, NOTIFICATION_STATE_PREFS_NAME))
            .save(NotificationState(scheduledReminders = reminders.toList()))
    }

    /**
     * `goAsync()` only returns a usable `PendingResult` when the receiver is invoked
     * through a real broadcast dispatch — Robolectric wires up the pending result as
     * part of delivering the broadcast to the manifest-declared receiver, but calling
     * `onReceive` directly on a bare instance leaves it null and `pending.finish()`
     * blows up. `sendBroadcast` queues delivery on the main looper, so it needs an
     * `idle()` to actually invoke `onReceive` before the background reschedule work
     * (still real `Dispatchers.IO`, hence the polling wait below) can kick off.
     */
    private fun dispatch(action: String) {
        app.sendBroadcast(Intent(action))
        shadowOf(Looper.getMainLooper()).idle()
    }

    /**
     * [BootReceiver] does its work on a real `Dispatchers.IO` thread behind `goAsync()`,
     * not the test dispatcher, so there's nothing for `runTest` to advance — poll for
     * the alarm to show up instead of asserting immediately after `onReceive`.
     */
    private fun awaitScheduledCount(expected: Int, timeoutMs: Long = 5_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (shadowOf(alarmManager).scheduledAlarms.size < expected) {
            check(System.currentTimeMillis() < deadline) {
                "timed out waiting for BootReceiver to reschedule (" +
                    "have ${shadowOf(alarmManager).scheduledAlarms.size}, want $expected)"
            }
            Thread.sleep(10)
        }
    }

    @Test
    fun `BOOT_COMPLETED reschedules future reminders and skips past-due ones`() = runTest {
        val now = System.currentTimeMillis()
        val futureFireAt = now + 60_000
        seedState(
            ScheduledReminder("cozy-meetup", "Cozy Meetup", futureFireAt, ReminderKind.SOON),
            ScheduledReminder("past-event", "Past Event", now - 60_000, ReminderKind.SOON),
        )

        dispatch(Intent.ACTION_BOOT_COMPLETED)
        awaitScheduledCount(1)

        val scheduled = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, scheduled.size)
        // Pins down which reminder got scheduled, not just how many did — a bug that
        // rescheduled the past-due one instead would still leave the count at 1.
        assertEquals(futureFireAt, scheduled.single().triggerAtMs)
    }

    @Test
    fun `MY_PACKAGE_REPLACED reschedules future reminders and skips past-due ones`() = runTest {
        val now = System.currentTimeMillis()
        val futureFireAt = now + 60_000
        seedState(
            ScheduledReminder("cozy-meetup", "Cozy Meetup", futureFireAt, ReminderKind.SOON),
            ScheduledReminder("past-event", "Past Event", now - 60_000, ReminderKind.SOON),
        )

        dispatch(Intent.ACTION_MY_PACKAGE_REPLACED)
        awaitScheduledCount(1)

        val scheduled = shadowOf(alarmManager).scheduledAlarms
        assertEquals(1, scheduled.size)
        assertEquals(futureFireAt, scheduled.single().triggerAtMs)
    }

    @Test
    fun `unrelated actions are ignored`() = runTest {
        seedState(ScheduledReminder("cozy-meetup", "Cozy Meetup", System.currentTimeMillis() + 60_000, ReminderKind.SOON))

        // The manifest doesn't even wire BootReceiver to this action, but the
        // action check inside onReceive is what this test is really pinning down —
        // exercise it directly the way the two dispatch-based tests can't.
        BootReceiver().onReceive(app, Intent(Intent.ACTION_TIME_CHANGED))
        assertTrue(shadowOf(alarmManager).scheduledAlarms.isEmpty())
    }
}
