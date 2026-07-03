package com.autom8ed.fibersocial.notifications

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Fires when a scheduled reminder alarm goes off; posts the reminder notification. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val permalink = intent.getStringExtra(EXTRA_EVENT_PERMALINK) ?: return
        val title = intent.getStringExtra(EXTRA_EVENT_TITLE) ?: return
        val kind = intent.getStringExtra(EXTRA_REMINDER_KIND)
            ?.let { name -> ReminderKind.entries.firstOrNull { it.name == name } }
            ?: return
        println("FiberSocial: ReminderReceiver firing $kind for $permalink")
        EventNotifier(context).apply {
            ensureChannels()
            showReminder(permalink, title, kind)
        }
    }

    companion object {
        const val EXTRA_EVENT_TITLE = "event_title"
        const val EXTRA_REMINDER_KIND = "reminder_kind"
    }
}
