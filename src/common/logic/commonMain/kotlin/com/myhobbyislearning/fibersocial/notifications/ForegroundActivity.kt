package com.myhobbyislearning.fibersocial.notifications

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Decides where a sync's detected activity should surface (issue #339): a system banner
 * when the user is away, an in-app indicator when they're present.
 *
 * Pure so both platforms share one rule and it's unit-testable. A sync triggered from the
 * debug panel's "Run sync now" always posts real banners regardless of foreground state —
 * that's the tool for verifying the notification pipeline itself.
 */
object ForegroundNotificationPolicy {

    /**
     * Whether this sync should post system banners (reply / new-event) at all.
     * Foreground syncs suppress them (surfacing in-app instead) unless forced.
     */
    fun shouldPostBanners(forceNotifications: Boolean, appInForeground: Boolean): Boolean =
        forceNotifications || !appInForeground

    /**
     * Whether detected reply activity should be surfaced in-app (the "Your Posts" badge /
     * a feed refresh) instead of as a banner: only when it was actually suppressed —
     * foreground, unforced, and there was something to show.
     */
    fun shouldSurfaceRepliesInApp(
        forceNotifications: Boolean,
        appInForeground: Boolean,
        hasReplyActivity: Boolean,
    ): Boolean = hasReplyActivity && appInForeground && !forceNotifications
}

/**
 * Process-wide, in-memory signal that a foreground sync detected new reply activity but
 * suppressed its banner (issue #339), so the UI can surface it instead — a badge on the
 * drawer's "Your Posts" row and a refresh of the My Posts feed.
 *
 * In-memory (not persisted) on purpose: it only matters while the app is foregrounded and
 * alive. If the process died the user wasn't present, and a background sync would have
 * posted a real banner, so there's nothing to carry across a restart. Both the platform
 * sync (Android's `EventSyncWorker`, iOS's `EventSync`) and the Compose UI reference this
 * single object; they run in the same process.
 */
object ForegroundActivitySignal {
    private val _hasUnseenReplies = MutableStateFlow(false)

    /** True while a suppressed foreground reply detection is waiting to be seen. */
    val hasUnseenReplies: StateFlow<Boolean> = _hasUnseenReplies.asStateFlow()

    /** Marks that suppressed reply activity is waiting to be surfaced in-app. */
    fun markUnseenReplies() {
        _hasUnseenReplies.value = true
    }

    /** Clears the badge once the user has seen the My Posts feed. */
    fun clearUnseenReplies() {
        _hasUnseenReplies.value = false
    }
}
