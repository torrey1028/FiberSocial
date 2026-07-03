package com.autom8ed.fibersocial.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * Schedules event reminders as exact alarms.
 *
 * Uses `USE_EXACT_ALARM` (declared in the manifest): auto-granted, irrevocable, and
 * intended for exactly this calendar-reminder use case — a 15-minute warning is useless
 * if it drifts by the ±15 minutes inexact alarms allow. Alarms don't survive reboots;
 * [BootReceiver] reschedules from the persisted state.
 */
class ReminderScheduler(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    fun schedule(reminder: ScheduledReminder) {
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            reminder.fireAtEpochMs,
            pendingIntent(reminder),
        )
        println("FiberSocial: ReminderScheduler scheduled ${reminder.kind} for ${reminder.eventPermalink}")
    }

    fun cancel(reminder: ScheduledReminder) {
        alarmManager.cancel(pendingIntent(reminder))
        println("FiberSocial: ReminderScheduler cancelled ${reminder.kind} for ${reminder.eventPermalink}")
    }

    /**
     * One PendingIntent per (event, kind): the request code encodes the identity, so a
     * reschedule with FLAG_UPDATE_CURRENT replaces the previous alarm's payload and a
     * cancel matches it.
     */
    private fun pendingIntent(reminder: ScheduledReminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_EVENT_PERMALINK, reminder.eventPermalink)
            putExtra(ReminderReceiver.EXTRA_EVENT_TITLE, reminder.eventTitle)
            putExtra(ReminderReceiver.EXTRA_REMINDER_KIND, reminder.kind.name)
        }
        return PendingIntent.getBroadcast(
            context,
            (reminder.eventPermalink + reminder.kind.name).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
