package com.autom8ed.fibersocial.notifications

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

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
        // USE_EXACT_ALARM exists only on API 33+; Android 12/12L uses the revocable
        // SCHEDULE_EXACT_ALARM (declared with maxSdkVersion=32). If it was revoked,
        // degrade to an inexact alarm instead of throwing SecurityException — a
        // drifting reminder beats a crashing sync (and a boot-time crash loop).
        val exactAllowed = Build.VERSION.SDK_INT < 31 || alarmManager.canScheduleExactAlarms()
        if (exactAllowed) {
            alarmManager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.fireAtEpochMs,
                pendingIntent(reminder),
            )
        } else {
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP,
                reminder.fireAtEpochMs,
                pendingIntent(reminder),
            )
        }
        println("FiberSocial: ReminderScheduler scheduled ${reminder.kind} for ${reminder.eventPermalink} (exact=$exactAllowed)")
    }

    fun cancel(reminder: ScheduledReminder) {
        alarmManager.cancel(pendingIntent(reminder))
        println("FiberSocial: ReminderScheduler cancelled ${reminder.kind} for ${reminder.eventPermalink}")
    }

    /**
     * One PendingIntent per (event, kind, fireAt) — the same occurrence-aware identity
     * the planner diffs by: a recurring event lists one reminder pair per occurrence
     * under one permalink, and a (event, kind) code would collapse those to a single
     * alarm (and cancelling one occurrence would kill its siblings). Re-scheduling the
     * same reminder is idempotent; a move is a cancel+schedule pair from the planner.
     */
    private fun pendingIntent(reminder: ScheduledReminder): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(EXTRA_EVENT_PERMALINK, reminder.eventPermalink)
            putExtra(ReminderReceiver.EXTRA_EVENT_TITLE, reminder.eventTitle)
            putExtra(ReminderReceiver.EXTRA_REMINDER_KIND, reminder.kind.name)
        }
        return PendingIntent.getBroadcast(
            context,
            (reminder.eventPermalink + reminder.kind.name + reminder.fireAtEpochMs).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }
}
