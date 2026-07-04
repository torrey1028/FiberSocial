package com.autom8ed.fibersocial.notifications

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.Constraints
import com.autom8ed.fibersocial.BuildConfig
import kotlinx.coroutines.CancellationException
import com.autom8ed.fibersocial.auth.AuthRepository
import com.autom8ed.fibersocial.auth.KeyValueTokenStorage
import com.autom8ed.fibersocial.auth.RavelryOAuthClient
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.RavelryApiClient
import com.autom8ed.fibersocial.storage.AUTH_PREFS_NAME
import com.autom8ed.fibersocial.storage.NOTIFICATION_STATE_PREFS_NAME
import com.autom8ed.fibersocial.storage.encryptedKeyValueStore
import com.autom8ed.fibersocial.storage.plainKeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import java.util.concurrent.TimeUnit
import kotlinx.datetime.Clock
import kotlinx.datetime.TimeZone
import kotlinx.serialization.json.Json

/**
 * Periodic background sync for event notifications: scrapes the user's groups and
 * saved events, then applies the [EventSyncRunner]'s plan — posts "new event"
 * notifications and (re)schedules reminder alarms.
 */
class EventSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        println("FiberSocial: EventSyncWorker starting (attempt $runAttemptCount)")
        val httpClient = HttpClient(Android) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            install(HttpTimeout) {
                requestTimeoutMillis = 30_000
                connectTimeoutMillis = 10_000
                socketTimeoutMillis = 30_000
            }
        }
        return try {
            val tokenStorage = KeyValueTokenStorage(encryptedKeyValueStore(applicationContext, AUTH_PREFS_NAME))
            if (tokenStorage.load() == null) {
                println("FiberSocial: EventSyncWorker skipping — not logged in")
                return Result.success()
            }
            val authRepository = AuthRepository(
                RavelryOAuthClient(
                    httpClient = httpClient,
                    clientId = BuildConfig.RAVELRY_CLIENT_ID,
                    clientSecret = BuildConfig.RAVELRY_CLIENT_SECRET,
                ),
                tokenStorage,
            )
            val apiClient = RavelryApiClient(
                httpClient = httpClient,
                tokenStorage = tokenStorage,
                refreshToken = { authRepository.refreshToken() },
            )
            val runner = EventSyncRunner(
                apiClient,
                KeyValueNotificationStateStore(plainKeyValueStore(applicationContext, NOTIFICATION_STATE_PREFS_NAME)),
            )
            val plan = runner.sync(Clock.System.now(), TimeZone.currentSystemDefault())
            apply(plan)
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

    private fun apply(plan: SyncPlan) {
        val notifier = EventNotifier(applicationContext).apply { ensureChannels() }
        plan.newEventNotifications.forEach { notifier.showNewEvent(it) }
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

    companion object {
        private const val UNIQUE_WORK_NAME = "event_sync"
        private const val UNIQUE_ONCE_WORK_NAME = "event_sync_once"

        /**
         * Registers (or re-registers, when [pollIntervalHours] changed) the periodic
         * sync. UPDATE keeps the existing schedule's timing when nothing changed.
         */
        fun schedulePeriodic(context: Context, pollIntervalHours: Int) {
            // Clamp through the settings model: an off-menu persisted value (corrupt
            // JSON, future migration) would make PeriodicWorkRequestBuilder throw and
            // crash the authenticated startup path that calls this.
            val safeHours = NotificationSettings(pollIntervalHours).effectivePollIntervalHours
            val request = PeriodicWorkRequestBuilder<EventSyncWorker>(
                safeHours.toLong(), TimeUnit.HOURS,
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
            println("FiberSocial: EventSyncWorker scheduled every ${safeHours}h")
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
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    )
                    .build(),
            )
        }
    }
}
