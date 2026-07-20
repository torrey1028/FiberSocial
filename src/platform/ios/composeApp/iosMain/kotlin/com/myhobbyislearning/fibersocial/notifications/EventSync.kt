package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.app.ravelryClientId
import com.myhobbyislearning.fibersocial.app.ravelryClientSecret
import com.myhobbyislearning.fibersocial.auth.KeyValueTokenStorage
import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.net.ravelryApiClient
import com.myhobbyislearning.fibersocial.net.ravelryAuthRepository
import com.myhobbyislearning.fibersocial.net.ravelryHttpClient
import com.myhobbyislearning.fibersocial.storage.AUTH_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.KeychainKeyValueStore
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_SETTINGS_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.NOTIFICATION_STATE_STORE_NAME
import com.myhobbyislearning.fibersocial.storage.NsUserDefaultsKeyValueStore
import kotlin.time.Clock
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import platform.BackgroundTasks.BGAppRefreshTask
import platform.BackgroundTasks.BGAppRefreshTaskRequest
import platform.BackgroundTasks.BGTaskScheduler
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.dateWithTimeIntervalSinceNow

/**
 * iOS event-notification sync: the counterpart of Android's `EventSyncWorker`.
 *
 * One sync cycle scrapes, plans, persists, then applies the [SyncPlan] through
 * [IosEventNotifier]. Triggers:
 * - every foreground activation (registered in AppSetup) — the primary path, since
 *   iOS background refresh is opportunistic;
 * - `BGAppRefreshTask` when the OS grants a background slot;
 * - RSVP changes and the debug panel's "Run sync now" (immediate).
 */
@OptIn(ExperimentalForeignApi::class)
object EventSync {

    const val REFRESH_TASK_IDENTIFIER = "com.myhobbyislearning.fibersocial.refresh"

    private val scope: CoroutineScope = MainScope()
    private var inFlight: Job? = null

    /**
     * Runs one sync, cancelling any sync already in flight — same last-trigger-wins
     * rule as Android's unique-work REPLACE policy: rapid triggers (RSVP toggled on
     * then off) must not let a stale sync save last and resurrect reminders for a
     * withdrawn RSVP.
     */
    fun runOnce(onFinished: (Boolean) -> Unit = {}) {
        inFlight?.cancel()
        inFlight = scope.launch {
            val ok = syncNow()
            onFinished(ok)
        }
    }

    /** One sync cycle; returns false on failure. Skips (successfully) when logged out. */
    private suspend fun syncNow(): Boolean {
        println("FiberSocial: EventSync starting")
        val httpClient = ravelryHttpClient()
        return try {
            val tokenStorage = KeyValueTokenStorage(KeychainKeyValueStore(AUTH_STORE_NAME))
            if (tokenStorage.load() == null) {
                println("FiberSocial: EventSync skipping — not logged in")
                return true
            }
            val authRepository = ravelryAuthRepository(
                httpClient = httpClient,
                tokenStorage = tokenStorage,
                clientId = ravelryClientId(),
                clientSecret = ravelryClientSecret(),
            )
            val apiClient = ravelryApiClient(httpClient, tokenStorage, authRepository)
            val runner = EventSyncRunner(
                apiClient,
                KeyValueNotificationStateStore(NsUserDefaultsKeyValueStore(NOTIFICATION_STATE_STORE_NAME)),
                KeyValueNotificationSettingsStore(NsUserDefaultsKeyValueStore(NOTIFICATION_SETTINGS_STORE_NAME)),
                KeyValueMutedTopicsStore(NsUserDefaultsKeyValueStore(NOTIFICATION_STATE_STORE_NAME)),
            )
            val plan = runner.sync(Clock.System.now(), TimeZone.currentSystemDefault())
            apply(plan)
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: SessionExpiredException) {
            // Re-login needs the WebView; syncs resume after the user next signs in.
            println("FiberSocial: EventSync skipping — session expired")
            true
        } catch (e: Exception) {
            println("FiberSocial: EventSync failed: ${e.message}")
            false
        } finally {
            httpClient.close()
        }
    }

    private fun apply(plan: SyncPlan) {
        val notifier = IosEventNotifier()
        plan.newEventNotifications.forEach { notifier.showNewEvent(it) }
        plan.newReplyNotifications.forEach { notifier.showNewReplies(it) }
        // Whole batch, not forEach: the messages notifier collapses several messages in one
        // conversation into a single banner (see IosEventNotifier.showNewMessages).
        notifier.showNewMessages(plan.newMessageNotifications)
        plan.remindersToCancel.forEach { notifier.cancelReminder(it) }
        // Re-arm everything still in the future, not just the plan's diff — same
        // crash-safety reasoning as Android's EventSyncWorker: state persists before
        // these effects, and re-adding an identical pending request is idempotent.
        val now = Clock.System.now().toEpochMilliseconds()
        plan.newState.scheduledReminders
            .filter { it.fireAtEpochMs > now }
            .forEach { notifier.scheduleReminder(it) }
    }

    /**
     * Registers the background-refresh handler. MUST be called before the app
     * finishes launching (the Swift AppDelegate does) — later registration throws.
     */
    fun registerBackgroundTask() {
        BGTaskScheduler.sharedScheduler.registerForTaskWithIdentifier(
            identifier = REFRESH_TASK_IDENTIFIER,
            usingQueue = null,
        ) { task ->
            handleBackgroundRefresh(task as BGAppRefreshTask)
        }
    }

    private fun handleBackgroundRefresh(task: BGAppRefreshTask) {
        println("FiberSocial: BGAppRefreshTask granted")
        // The OS grants one launch per submitted request; ask for the next one first
        // so a crash mid-sync doesn't silently end all future background refreshes.
        scheduleBackgroundRefresh()
        val job = scope.launch {
            val ok = syncNow()
            task.setTaskCompletedWithSuccess(ok)
        }
        task.expirationHandler = {
            println("FiberSocial: BGAppRefreshTask expired before the sync finished")
            job.cancel()
            task.setTaskCompletedWithSuccess(false)
        }
    }

    /**
     * Submits the next background-refresh request, [cadence] hours out (the user's
     * qualitative cadence mapped to the same hour buckets as Android's WorkManager
     * interval). `earliestBeginDate` is a floor, not a schedule — iOS decides the
     * real cadence from usage patterns, which is why the setting's wording is
     * qualitative (issue #113).
     */
    fun scheduleBackgroundRefresh(cadence: PollCadence? = null) {
        scope.launch {
            val effective = cadence
                ?: KeyValueNotificationSettingsStore(NsUserDefaultsKeyValueStore(NOTIFICATION_SETTINGS_STORE_NAME))
                    .load().effectivePollCadence
            val hours = when (effective) {
                PollCadence.HOURLY -> 1
                PollCadence.A_FEW_TIMES_A_DAY -> 6
                PollCadence.ONCE_A_DAY -> 24
            }
            val request = BGAppRefreshTaskRequest(identifier = REFRESH_TASK_IDENTIFIER).apply {
                earliestBeginDate = NSDate.dateWithTimeIntervalSinceNow(hours * 3600.0)
            }
            memScoped {
                val error = alloc<ObjCObjectVar<NSError?>>()
                val submitted = BGTaskScheduler.sharedScheduler.submitTaskRequest(request, error.ptr)
                if (submitted) {
                    println("FiberSocial: background refresh requested, earliest in ${hours}h ($effective)")
                } else {
                    // Expected on the simulator (unsupported) and when the user disabled
                    // Background App Refresh; foreground syncs still cover them.
                    println("FiberSocial: background refresh request failed: ${error.value?.localizedDescription}")
                }
            }
        }
    }
}
