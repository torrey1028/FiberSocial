package com.myhobbyislearning.fibersocial.notifications

import android.content.Intent
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Covers the extras → [DeepLink] mapping MainActivity feeds FeedScreen (issue #351). */
@RunWith(RobolectricTestRunner::class)
class IntentDeepLinksTest {

    @Test
    fun `a new-event notification maps to its event and group`() {
        val intent = Intent()
            .putExtra(EXTRA_EVENT_PERMALINK, "cozy-meetup")
            .putExtra(EXTRA_EVENT_GROUP_ID, 4242L)
        assertEquals(DeepLink.Event("cozy-meetup", 4242L), intent.toDeepLink())
    }

    @Test
    fun `a reminder notification maps to an event with no group`() {
        // Reminders are planned from the RSVP'd-events scrape, which records no group,
        // so back from the opened event goes to the feed rather than an events list.
        val intent = Intent().putExtra(EXTRA_EVENT_PERMALINK, "cozy-meetup")
        assertEquals(DeepLink.Event("cozy-meetup", null), intent.toDeepLink())
    }

    @Test
    fun `a reply child maps to its topic`() {
        assertEquals(DeepLink.Topic(7L), Intent().putExtra(EXTRA_TOPIC_ID, 7L).toDeepLink())
    }

    @Test
    fun `the reply group summary maps to the My Posts feed`() {
        assertEquals(DeepLink.MyPosts, Intent().putExtra(EXTRA_OPEN_MY_POSTS, true).toDeepLink())
    }

    @Test
    fun `the most specific extra wins`() {
        // Defensive: the notifier never sets more than one, but a stale PendingIntent
        // rewritten across an upgrade must still resolve deterministically.
        val intent = Intent()
            .putExtra(EXTRA_EVENT_PERMALINK, "cozy-meetup")
            .putExtra(EXTRA_TOPIC_ID, 7L)
            .putExtra(EXTRA_OPEN_MY_POSTS, true)
        assertEquals(DeepLink.Event("cozy-meetup", null), intent.toDeepLink())
        assertEquals(
            DeepLink.Topic(7L),
            Intent().putExtra(EXTRA_TOPIC_ID, 7L).putExtra(EXTRA_OPEN_MY_POSTS, true).toDeepLink(),
        )
    }

    @Test
    fun `a launcher intent carries no deep link`() {
        assertNull(Intent().toDeepLink())
    }

    @Test
    fun `a zero id is treated as absent, not as topic or group zero`() {
        // 0 is the getLongExtra default and not a valid Ravelry id, so it doubles as
        // the "extra wasn't set" sentinel.
        assertNull(Intent().putExtra(EXTRA_TOPIC_ID, 0L).toDeepLink())
        assertEquals(
            DeepLink.Event("cozy-meetup", null),
            Intent()
                .putExtra(EXTRA_EVENT_PERMALINK, "cozy-meetup")
                .putExtra(EXTRA_EVENT_GROUP_ID, 0L)
                .toDeepLink(),
        )
    }
}
