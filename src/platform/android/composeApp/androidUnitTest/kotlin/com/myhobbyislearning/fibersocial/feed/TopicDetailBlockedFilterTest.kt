package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.Post
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [filterBlockedPosts] backs the topic reply list's instant hide-on-block (issue #410):
 * every post rendered in a thread is checked against the blocked-users set individually,
 * unlike [filterBlocked] which filters whole feed CARDS by their starter.
 */
class TopicDetailBlockedFilterTest {

    @Test
    fun `no blocked users returns every post unchanged`() {
        val posts = listOf(Post(id = 1, user = RavelryUser(username = "alice")), Post(id = 2, user = RavelryUser(username = "bob")))
        assertEquals(posts, filterBlockedPosts(posts, blockedUsernames = emptySet()))
    }

    @Test
    fun `a post from a blocked author is dropped`() {
        val fromAlice = Post(id = 1, user = RavelryUser(username = "alice"))
        val fromBob = Post(id = 2, user = RavelryUser(username = "bob"))

        val result = filterBlockedPosts(listOf(fromAlice, fromBob), blockedUsernames = setOf("alice"))

        assertEquals(listOf(fromBob), result)
    }

    @Test
    fun `a post with no known author is never filtered`() {
        val unattributed = Post(id = 1, user = null)
        assertEquals(
            listOf(unattributed),
            filterBlockedPosts(listOf(unattributed), blockedUsernames = setOf("alice")),
        )
    }

    @Test
    fun `matching is case-insensitive`() {
        val fromAlice = Post(id = 1, user = RavelryUser(username = "Alice"))
        assertEquals(emptyList(), filterBlockedPosts(listOf(fromAlice), blockedUsernames = setOf("alice")))
    }
}
