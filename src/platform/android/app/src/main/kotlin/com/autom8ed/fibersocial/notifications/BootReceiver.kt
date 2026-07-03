package com.autom8ed.fibersocial.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reschedules pending reminders after a reboot — alarms don't survive one, but the
 * persisted [NotificationState] knows what should be scheduled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val state = AndroidNotificationStateStore(context).load() ?: return@launch
                val scheduler = ReminderScheduler(context)
                val now = System.currentTimeMillis()
                val future = state.scheduledReminders.filter { it.fireAtEpochMs > now }
                future.forEach { scheduler.schedule(it) }
                println("FiberSocial: BootReceiver rescheduled ${future.size} reminders")
            } finally {
                pending.finish()
            }
        }
    }
}
