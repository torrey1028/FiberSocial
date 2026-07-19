package com.myhobbyislearning.fibersocial.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A shared "the app just came to the foreground" signal each platform emits into, so
 * shared UI can react to a real foreground resume rather than to first composition
 * (issue #350 part 1).
 *
 * Compose trees survive backgrounding, so a `LaunchedEffect(Unit)` in a long-lived screen
 * fires exactly once per process — a user who backgrounds the app and returns hours later
 * never re-runs it. Emitters:
 * - Android: `MainActivity.onResume()` (single-Activity app, so Activity resume *is*
 *   process foreground). Deliberately not `ProcessLifecycleOwner`, which would mean
 *   adding an `androidx.lifecycle-process` dependency to buy nothing here.
 * - iOS: the existing `UIApplicationDidBecomeActiveNotification` observer in `AppSetup`.
 *
 * Exposed as a monotonically increasing counter rather than an event stream on purpose:
 * a [StateFlow] hands its current value to every new collector, so a collector that only
 * starts on first composition still gets one activation immediately. That makes a
 * separate "run once on composition" effect redundant — collecting [ticks] covers both
 * cold start and every later resume with one mechanism, and cannot drop the cold-start
 * activation by subscribing a frame too late.
 */
object ForegroundActivations {

    private val _ticks = MutableStateFlow(0L)

    /**
     * Increments once per foreground activation. The value itself is meaningless — only
     * that it changed. Never resets, so a collector can't mistake a new activation for
     * the one it already handled.
     */
    val ticks: StateFlow<Long> = _ticks.asStateFlow()

    /** Called by each platform's foreground hook. Safe to call from any thread. */
    fun notifyForegrounded() {
        _ticks.update { it + 1 }
    }
}
