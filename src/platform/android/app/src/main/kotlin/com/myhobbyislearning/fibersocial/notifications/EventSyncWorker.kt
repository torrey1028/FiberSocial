package com.myhobbyislearning.fibersocial.notifications

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import androidx.work.workDataOf
import com.myhobbyislearning.fibersocial.BuildConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.myhobbyislearning.fibersocial.auth.KeyValueTokenStorage
import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.net.ravelryApiClient
import com.myhobbyislearning.fibersocial.net.ravelryAuthRepository
import com.myhobbyislearning.fibersocial.net.ravelryHttpClient
import com.myhobbyislearning.fibersocial.storage.AUTH_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_SETTINGS_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_STATE_PREFS_NAME
import com.myhobbyislearning.fibersocial.storage.encryptedKeyValueStore
import com.myhobbyislearning.fibersocial.storage.plainKeyValueStore
import java.util.concurrent.TimeUnit
import kotlin.time.Clock
import kotlinx.datetime.TimeZone

/**
 * Periodic background sync for event and reply notifications: scrapes the user's groups
 * and saved events, then applies the [EventSyncRunner]'s plan — posts "new event" and
 * "new reply" notifications and (re)schedules reminder alarms.
 */
class EventSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        println("FiberSocial: EventSyncWorker starting (attempt $runAttemptCount)")
        val httpClient = ravelryHttpClient()
        return try {
            val tokenStorage = KeyValueTokenStorage(encryptedKeyValueStore(applicationContext, AUTH_PREFS_NAME))
            if (tokenStorage.load() == null) {
                println("FiberSocial: EventSyncWorker skipping — not logged in")
                return Result.success()
            }
            val authRepository = ravelryAuthRepository(
                httpClient = httpClient,
                tokenStorage = tokenStorage,
                clientId = BuildConfig.RAVELRY_CLIENT_ID,
                clientSecret = BuildConfig.RAVELRY_CLIENT_SECRET,
            )
            val apiClient = ravelryApiClient(httpClient, tokenStorage, authRepository)
            val runner = EventSyncRunner(
                apiClient,
                KeyValueNotificationStateStore(plainKeyValueStore(applicationContext, NOTIFICATION_STATE_PREFS_NAME)),
                KeyValueNotificationSettingsStore(plainKeyValueStore(applicationContext, NOTIFICATION_SETTINGS_PREFS_NAME)),
                KeyValueMutedTopicsStore(plainKeyValueStore(applicationContext, NOTIFICATION_STATE_PREFS_NAME)),
            )
            val plan = runner.sync(Clock.System.now(), TimeZone.currentSystemDefault())
            // The debug panel's one-time sync forces real banners regardless of foreground
            // state (issue #339); the periodic sync doesn't set the flag.
            val force = inputData.getBoolean(KEY_FORCE_NOTIFICATIONS, false)
            apply(plan, force)
            Result.success()
        } catch (e: CancellationException) {
            // WorkManager stopped us (constraint lost, timeout); cancellation must
            // propagate, not be converted into a retry result.
            throw e
        } catch (e: SessionExpiredException) {
            // Re-login needs the WebView; polls resume after the user next signs in.
            println("FiberSocial: EventSyncWorker skipping — session expired")
            Result.success()
        } catch (e: Exception) {
            println("FiberSocial: EventSyncWorker failed: ${e.message}")
            if (runAttemptCount < 2) Result.retry() else Result.failure()
        } finally {
            httpClient.close()
        }
    }

    private suspend fun apply(plan: SyncPlan, forceNotifications: Boolean) {
        val foreground = appInForeground()
        val notifier = EventNotifier(applicationContext).apply { ensureChannels() }
        // Reply + new-event banners are suppressed while the app is foregrounded (unless
        // forced): the user is looking at the app, so we surface the activity in-app
        // instead of over it (issue #339). Reminders (exact alarms, scheduled below) are
        // time-critical and keep firing regardless.
        if (ForegroundNotificationPolicy.shouldPostBanners(forceNotifications, foreground)) {
            plan.newEventNotifications.forEach { notifier.showNewEvent(it) }
            notifier.showNewReplies(plan.newReplyNotifications)
        } else {
            println("FiberSocial: EventSyncWorker suppressing banners — app foregrounded")
        }
        if (ForegroundNotificationPolicy.shouldSurfaceRepliesInApp(
                forceNotifications, foreground, plan.newReplyNotifications.isNotEmpty(),
            )
        ) {
            ForegroundActivitySignal.markUnseenReplies()
        }
        val scheduler = ReminderScheduler(applicationContext)
        plan.remindersToCancel.forEach { scheduler.cancel(it) }
        // Re-arm everything still in the future, not just the plan's diff: state is
        // persisted before these effects run (so a crash can't duplicate a "new event"
        // announcement), which means a crash right here would otherwise lose the
        // diffed alarms forever — the next sync's diff already considers them
        // scheduled. Scheduling is idempotent (same identity -> same PendingIntent).
        val now = System.currentTimeMillis()
        plan.newState.scheduledReminders
            .filter { it.fireAtEpochMs > now }
            .forEach { scheduler.schedule(it) }
    }

    /**
     * Whether the app process is currently foregrounded. `ProcessLifecycleOwner`'s state
     * is maintained on the main thread, so read it there — the worker runs on a
     * background dispatcher.
     */
    private suspend fun appInForeground(): Boolean = withContext(Dispatchers.Main.immediate) {
        ProcessLifecycleOwner.get().lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "event_sync"
        private const val UNIQUE_ONCE_WORK_NAME = "event_sync_once"

        /** Input-data flag: post real banners even if foregrounded (debug "Run sync now"). */
        const val KEY_FORCE_NOTIFICATIONS = "force_notifications"

        /**
         * Registers (or re-registers, when [cadence] changed) the periodic sync.
         * UPDATE keeps the existing schedule's timing when nothing changed.
         */
        fun schedulePeriodic(context: Context, cadence: PollCadence) {
            val hours = cadence.workManagerPollIntervalHours()
            val request = PeriodicWorkRequestBuilder<EventSyncWorker>(
                hours.toLong(), TimeUnit.HOURS,
            )
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_WORK_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
            println("FiberSocial: EventSyncWorker scheduled every ${hours}h ($cadence)")
        }

        /** Runs one sync immediately; used by the debug panel. */
        fun runOnce(context: Context) {
            // Same network constraint as the periodic work: offline, an unconstrained
            // request would enter the retry path and keep rescheduling in the
            // background — surprising for a manual debug tap.
            // Unique + REPLACE: rapid triggers (e.g. RSVP toggled on then off) must not
            // race two concurrent syncs — the stale one could save last and resurrect
            // reminders for a withdrawn RSVP until the next poll. REPLACE cancels the
            // in-flight sync so the last trigger's view of the world wins.
            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_ONCE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<EventSyncWorker>()
                    // Debug sync-now posts real banners even when foregrounded, so the
                    // notification pipeline can be verified on-device (issue #339).
                    .setInputData(workDataOf(KEY_FORCE_NOTIFICATIONS to true))
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build(),
            )
        }
    }
}

/** Maps a qualitative cadence to the concrete `WorkManager` periodic interval it runs at. */
private fun PollCadence.workManagerPollIntervalHours(): Int = when (this) {
    PollCadence.HOURLY -> 1
    PollCadence.A_FEW_TIMES_A_DAY -> 6
    PollCadence.ONCE_A_DAY -> 24
}
