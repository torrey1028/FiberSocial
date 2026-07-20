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
    fun `a new-message child maps to its conversation and message`() {
        val intent = Intent()
            .putExtra(EXTRA_MESSAGE_THREAD_ID, 7L)
            .putExtra(EXTRA_MESSAGE_ID, 70L)
        assertEquals(DeepLink.Message(7L, 70L), intent.toDeepLink())
    }

    @Test
    fun `a conversation with no message id still maps`() {
        // Nothing posts this today, but a caller with only a conversation to point at
        // shouldn't be forced to invent a message id.
        assertEquals(
            DeepLink.Message(7L, 0L),
            Intent().putExtra(EXTRA_MESSAGE_THREAD_ID, 7L).toDeepLink(),
        )
    }

    @Test
    fun `the new-message group summary maps to the Messages destination`() {
        assertEquals(DeepLink.Messages, Intent().putExtra(EXTRA_OPEN_MESSAGES, true).toDeepLink())
    }

    @Test
    fun `a zero conversation id is treated as absent`() {
        assertNull(Intent().putExtra(EXTRA_MESSAGE_THREAD_ID, 0L).toDeepLink())
    }

    @Test
    fun `a conversation extra beats the bare Messages flag`() {
        val intent = Intent()
            .putExtra(EXTRA_MESSAGE_THREAD_ID, 7L)
            .putExtra(EXTRA_MESSAGE_ID, 70L)
            .putExtra(EXTRA_OPEN_MESSAGES, true)
        assertEquals(DeepLink.Message(7L, 70L), intent.toDeepLink())
    }

    @Test
    fun `an event or topic still beats a conversation`() {
        // Most-specific-first ordering across ALL kinds, not just within messages.
        assertEquals(
            DeepLink.Event("cozy-meetup", null),
            Intent()
                .putExtra(EXTRA_EVENT_PERMALINK, "cozy-meetup")
                .putExtra(EXTRA_MESSAGE_THREAD_ID, 7L)
                .toDeepLink(),
        )
        assertEquals(
            DeepLink.Topic(7L),
            Intent()
                .putExtra(EXTRA_TOPIC_ID, 7L)
                .putExtra(EXTRA_MESSAGE_THREAD_ID, 9L)
                .toDeepLink(),
        )
    }

    @Test
    fun `the Messages flag beats the My Posts flag`() {
        assertEquals(
            DeepLink.Messages,
            Intent()
                .putExtra(EXTRA_OPEN_MESSAGES, true)
                .putExtra(EXTRA_OPEN_MY_POSTS, true)
                .toDeepLink(),
        )
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
