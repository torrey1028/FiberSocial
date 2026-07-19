package com.myhobbyislearning.fibersocial.notifications

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ForegroundNotificationPolicyTest {

    @Test
    fun `banners post in the background`() {
        assertTrue(ForegroundNotificationPolicy.shouldPostBanners(forceNotifications = false, appInForeground = false))
    }

    @Test
    fun `banners are suppressed in the foreground`() {
        assertFalse(ForegroundNotificationPolicy.shouldPostBanners(forceNotifications = false, appInForeground = true))
    }

    @Test
    fun `forcing posts banners even in the foreground`() {
        assertTrue(ForegroundNotificationPolicy.shouldPostBanners(forceNotifications = true, appInForeground = true))
    }

    @Test
    fun `reply activity surfaces in-app only when suppressed in the foreground`() {
        // foreground + unforced + has activity -> surface in-app
        assertTrue(
            ForegroundNotificationPolicy.shouldSurfaceRepliesInApp(
                forceNotifications = false, appInForeground = true, hasReplyActivity = true,
            ),
        )
        // background -> the banner already covers it
        assertFalse(
            ForegroundNotificationPolicy.shouldSurfaceRepliesInApp(
                forceNotifications = false, appInForeground = false, hasReplyActivity = true,
            ),
        )
        // forced (debug) -> a real banner posted, nothing to surface
        assertFalse(
            ForegroundNotificationPolicy.shouldSurfaceRepliesInApp(
                forceNotifications = true, appInForeground = true, hasReplyActivity = true,
            ),
        )
        // nothing detected -> nothing to surface
        assertFalse(
            ForegroundNotificationPolicy.shouldSurfaceRepliesInApp(
                forceNotifications = false, appInForeground = true, hasReplyActivity = false,
            ),
        )
    }
}

class ForegroundActivitySignalTest {

    @AfterTest
    fun reset() = ForegroundActivitySignal.clearUnseenReplies()

    @Test
    fun `starts clear`() {
        ForegroundActivitySignal.clearUnseenReplies()
        assertEquals(false, ForegroundActivitySignal.hasUnseenReplies.value)
    }

    @Test
    fun `mark then clear`() {
        ForegroundActivitySignal.markUnseenReplies()
        assertEquals(true, ForegroundActivitySignal.hasUnseenReplies.value)
        ForegroundActivitySignal.clearUnseenReplies()
        assertEquals(false, ForegroundActivitySignal.hasUnseenReplies.value)
    }
}
