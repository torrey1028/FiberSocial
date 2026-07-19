package com.myhobbyislearning.fibersocial.app

import com.myhobbyislearning.fibersocial.notifications.DeepLink
import com.myhobbyislearning.fibersocial.notifications.EventSync
import com.myhobbyislearning.fibersocial.notifications.NOTIFICATION_EVENT_GROUP_ID_KEY
import com.myhobbyislearning.fibersocial.notifications.NOTIFICATION_EVENT_PERMALINK_KEY
import com.myhobbyislearning.fibersocial.notifications.NOTIFICATION_OPEN_MY_POSTS_KEY
import com.myhobbyislearning.fibersocial.notifications.NOTIFICATION_TOPIC_ID_KEY
import kotlinx.coroutines.flow.MutableStateFlow
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidBecomeActiveNotification
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionSound
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject

/**
 * Destination from a tapped notification; consumed once by FeedScreen — the iOS analog
 * of MainActivity's `deepLink` intent-extra flow (issue #351).
 */
val deepLink = MutableStateFlow<DeepLink?>(null)

// The delegate property on UNUserNotificationCenter is weak; keep the strong ref here.
private val notificationDelegate = NotificationDelegate()

/**
 * One-time launch wiring, called from the Swift `AppDelegate` in
 * `application(_:didFinishLaunchingWithOptions:)` — the only point early enough to
 * register the BGTaskScheduler handler (later registration throws) and to have the
 * notification delegate in place for a cold-start notification tap.
 */
fun onAppLaunch() {
    EventSync.registerBackgroundTask()
    UNUserNotificationCenter.currentNotificationCenter().delegate = notificationDelegate

    val center = NSNotificationCenter.defaultCenter
    // Sync on every foreground activation: with opportunistic background refresh this
    // is the delivery path a user actually experiences (docs/planning/iOSPort.md §5).
    center.addObserverForName(
        UIApplicationDidBecomeActiveNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        EventSync.runOnce()
        // Same activation drives the drawer's unread dots (issue #350 part 1) — reuse
        // this observer rather than registering a second one for the same notification.
        ForegroundActivations.notifyForegrounded()
    }
    // Ask for the next background slot whenever we leave the foreground; each grant
    // consumes the request, and re-submitting keeps exactly one pending.
    center.addObserverForName(
        UIApplicationDidEnterBackgroundNotification,
        `object` = null,
        queue = NSOperationQueue.mainQueue,
    ) { _ ->
        EventSync.scheduleBackgroundRefresh()
    }
}

private class NotificationDelegate : NSObject(), UNUserNotificationCenterDelegateProtocol {

    /** Notification tapped: surface its destination to the UI's deep-link flow. */
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val userInfo = didReceiveNotificationResponse.notification.request.content.userInfo
        val link = userInfo.toDeepLink()
        println("FiberSocial: notification tapped, deep link = $link")
        if (link != null) deepLink.value = link
        withCompletionHandler()
    }

    /**
     * Reads a tapped notification's destination out of its userInfo, most-specific first.
     *
     * The bare My Posts key is still honoured last so a reply notification delivered by a
     * pre-#351 build — one that may still be sitting in Notification Center across the
     * upgrade — keeps working instead of tapping to nothing.
     */
    private fun Map<Any?, *>.toDeepLink(): DeepLink? {
        (this[NOTIFICATION_EVENT_PERMALINK_KEY] as? String)?.let { permalink ->
            return DeepLink.Event(
                permalink = permalink,
                groupId = (this[NOTIFICATION_EVENT_GROUP_ID_KEY] as? String)?.toLongOrNull(),
            )
        }
        (this[NOTIFICATION_TOPIC_ID_KEY] as? String)?.toLongOrNull()?.let { return DeepLink.Topic(it) }
        if (this[NOTIFICATION_OPEN_MY_POSTS_KEY] != null) return DeepLink.MyPosts
        return null
    }

    /** Notifications arriving while the app is foregrounded still show as banners
     *  (Android posts them regardless of foreground state too). */
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        willPresentNotification: UNNotification,
        withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
        withCompletionHandler(UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionSound)
    }
}
