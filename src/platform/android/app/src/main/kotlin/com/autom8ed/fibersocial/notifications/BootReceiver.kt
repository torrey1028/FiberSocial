package com.autom8ed.fibersocial.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.autom8ed.fibersocial.storage.NOTIFICATION_STATE_PREFS_NAME
import com.autom8ed.fibersocial.storage.plainKeyValueStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Reschedules pending reminders after a reboot or an app update — alarms aren't
 * guaranteed to survive either (a package replacement clears them outright on some
 * OEMs/versions), but the persisted [NotificationState] knows what should be scheduled.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in RESCHEDULE_ACTIONS) return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val store = KeyValueNotificationStateStore(plainKeyValueStore(context, NOTIFICATION_STATE_PREFS_NAME))
                val state = store.load() ?: return@launch
                val scheduler = ReminderScheduler(context)
                val now = System.currentTimeMillis()
                val future = state.scheduledReminders.filter { it.fireAtEpochMs > now }
                future.forEach { scheduler.schedule(it) }
                println("FiberSocial: BootReceiver rescheduled ${future.size} reminders")
            } catch (e: Exception) {
                // A scheduling failure must degrade to a missed reminder, not an
                // app crash on every boot while the state still holds the entry.
                println("FiberSocial: BootReceiver failed to reschedule: ${e.message}")
            } finally {
                pending.finish()
            }
        }
    }

    private companion object {
        /**
         * Both trigger the same "reschedule from persisted state" logic below: a
         * reboot clears alarms unconditionally, and a package replacement clears
         * them on some OEMs/versions (not guaranteed, but not guaranteed *not* to).
         */
        val RESCHEDULE_ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
