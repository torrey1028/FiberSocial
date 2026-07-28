package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pure-function tests for [blockUserEmailUri] (issue #410's "notify the developer" step) —
 * no Compose/Android runtime involved, mirroring [ReportPostEmailUriTest].
 */
class BlockUserEmailUriTest {

    private val topic = FeedItem(
        id = 10L, groupId = 1L, groupName = "Fiber Fans", lastPostAt = null,
        author = RavelryUser(username = "starter"), title = "Anyone else finish the sock?",
        bodySummary = "", postCount = 3,
    )
    private val post = Post(id = 555L, user = RavelryUser(username = "someone"))

    @Test
    fun `mailto targets the developer address with a subject naming the blocked user`() {
        val uri = blockUserEmailUri("someone")
        assertTrue(uri.startsWith("mailto:myhobbyislearning@gmail.com?"))
        assertTrue(uri.contains("subject=FiberSocial%20user%20block%3A%20someone"))
    }

    @Test
    fun `body identifies the blocked user with no other context`() {
        val uri = blockUserEmailUri("someone")
        val body = uri.substringAfter("body=")
        assertTrue(body.contains("someone"))
        assertFalse(body.contains("Topic%3A"))
    }

    @Test
    fun `body includes a real topic link when topic and group permalink are known`() {
        val uri = blockUserEmailUri("someone", topic = topic, post = post, groupPermalink = "fiber-fans")
        val body = uri.substringAfter("body=")
        assertTrue(body.contains("https%3A%2F%2Fwww.ravelry.com%2Fdiscuss%2Ffiber-fans%2F10"))
        assertTrue(body.contains("555"))
    }

    @Test
    fun `body omits the topic link without a group permalink even with a topic`() {
        val uri = blockUserEmailUri("someone", topic = topic, post = post, groupPermalink = null)
        val body = uri.substringAfter("body=")
        assertTrue(body.contains("Anyone%20else%20finish%20the%20sock"))
        assertFalse(body.contains("Topic%20link"))
    }
}
