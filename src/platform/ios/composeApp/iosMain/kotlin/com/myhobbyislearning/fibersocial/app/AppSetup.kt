package com.myhobbyislearning.fibersocial.app

import com.myhobbyislearning.fibersocial.notifications.EventSync
import com.myhobbyislearning.fibersocial.notifications.NOTIFICATION_EVENT_PERMALINK_KEY
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
 * Event permalink from a tapped notification; consumed by FeedScreen's deep link —
 * the iOS analog of MainActivity's `deepLinkEvent` intent-extra flow.
 */
val deepLinkEvent = MutableStateFlow<String?>(null)

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

    /** Notification tapped: surface its event to the UI's deep-link flow. */
    override fun userNotificationCenter(
        center: UNUserNotificationCenter,
        didReceiveNotificationResponse: UNNotificationResponse,
        withCompletionHandler: () -> Unit,
    ) {
        val permalink = didReceiveNotificationResponse.notification.request.content.userInfo[
            NOTIFICATION_EVENT_PERMALINK_KEY,
        ] as? String
        println("FiberSocial: notification tapped, event=$permalink")
        if (permalink != null) deepLinkEvent.value = permalink
        withCompletionHandler()
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
