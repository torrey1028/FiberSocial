package com.myhobbyislearning.fibersocial.featureflags

/**
 * Compile-time gates for features still under development on `main`. Each flag is
 * backed by the platform's own debug/release build-type constant — Android's
 * generated `BuildConfig.DEBUG`, iOS's `Platform.isDebugBinary` — so a feature ships to
 * dogfooders in debug builds while staying out of what App Review and public release
 * builds see, with no separate toggle, remote config, or user setting to keep in sync.
 */
expect object FeatureFlags {
    /** Direct messages (epic #365): drawer entry, deep links, and new-message notifications. */
    val messagesEnabled: Boolean

    /**
     * Activity feed (epic #483): the group view's Topics/Activity tabs, the activity unread
     * dot and tab badge, and new-activity notifications.
     *
     * Gating the whole feature on one flag is what lets the tab row be *absent* rather than
     * merely empty in release builds — a release group view stays exactly as it is today,
     * with its topic list flush under the top bar.
     */
    val activityFeedEnabled: Boolean
}
