package com.myhobbyislearning.fibersocial.notifications

import android.content.Intent

/**
 * Reads the notification-tap destination this intent carries, if any (issue #351).
 *
 * Checked most-specific first: a reply-notification child carries a topic id and must
 * open that thread, while the group summary carries only the My Posts flag — so an
 * intent that somehow had both would still open the more specific destination. The
 * fall-through to [EXTRA_OPEN_MY_POSTS] alone is also what keeps a reply notification
 * posted by a pre-#351 build working if it's still in the shade across an upgrade.
 *
 * Only the extras are consulted: the app declares no `fibersocial://` intent-filter, and
 * those URIs exist purely to keep the notifications' PendingIntents distinct under
 * `Intent.filterEquals`, which ignores extras.
 *
 * Lives here rather than on `MainActivity` so it can be unit-tested directly.
 */
internal fun Intent.toDeepLink(): DeepLink? {
    getStringExtra(EXTRA_EVENT_PERMALINK)?.let { permalink ->
        // 0 is not a valid Ravelry group id, so it doubles as "absent" — reminder
        // notifications never carry a group (see DeepLink.Event).
        return DeepLink.Event(permalink, getLongExtra(EXTRA_EVENT_GROUP_ID, 0L).takeIf { it != 0L })
    }
    getLongExtra(EXTRA_TOPIC_ID, 0L).takeIf { it != 0L }?.let { return DeepLink.Topic(it) }
    if (getBooleanExtra(EXTRA_OPEN_MY_POSTS, false)) return DeepLink.MyPosts
    return null
}
