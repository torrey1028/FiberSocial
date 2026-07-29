package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pure-function tests for [reportPostEmailUri] (issue #409's secondary "report to app
 * developer" channel) — no Compose/Android runtime involved, so unlike the rest of this
 * package's UI tests this doesn't need the Robolectric runner (mirrors how
 * [trackReplySent]/[moveItem] are plain functions too).
 */
class ReportPostEmailUriTest {

    private val topic = FeedItem(
        id = 10L, groupId = 1L, groupName = "Fiber Fans", lastPostAt = null,
        author = RavelryUser(username = "starter"), title = "Anyone else finish the sock?",
        bodySummary = "", postCount = 3,
    )
    private val post = Post(id = 555L, user = RavelryUser(username = "someone"))

    @Test
    fun `mailto targets the developer address with a subject naming the app`() {
        val uri = reportPostEmailUri(topic, post, groupPermalink = null)
        assertTrue(uri.startsWith("mailto:myhobbyislearning@gmail.com?"))
        assertTrue(uri.contains("subject=FiberSocial%20post%20report"))
    }

    @Test
    fun `body includes a real topic link when the group permalink is known`() {
        val uri = reportPostEmailUri(topic, post, groupPermalink = "fiber-fans")
        val body = uri.substringAfter("body=")
        assertTrue(body.contains("https%3A%2F%2Fwww.ravelry.com%2Fdiscuss%2Ffiber-fans%2F10"))
    }

    @Test
    fun `body falls back to identifying details without a group permalink`() {
        val uri = reportPostEmailUri(topic, post, groupPermalink = null)
        val body = uri.substringAfter("body=")
        assertTrue(body.contains("Fiber%20Fans"))
        assertTrue(body.contains("555"))
        assertTrue(body.contains("someone"))
        assertEquals(false, body.contains("Topic%20link"))
    }

    @Test
    fun `body identifies the post author and ID regardless of permalink`() {
        val uri = reportPostEmailUri(topic, post, groupPermalink = "fiber-fans")
        val body = uri.substringAfter("body=")
        assertTrue(body.contains("someone"))
        assertTrue(body.contains("555"))
    }

    @Test
    fun `unknown post author degrades gracefully`() {
        val uri = reportPostEmailUri(topic, post.copy(user = null), groupPermalink = null)
        val body = uri.substringAfter("body=")
        assertTrue(body.contains("unknown"))
    }
}
