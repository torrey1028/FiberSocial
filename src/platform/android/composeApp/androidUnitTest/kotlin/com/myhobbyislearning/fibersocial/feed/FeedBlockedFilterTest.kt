package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.messages.Message
import com.myhobbyislearning.fibersocial.messages.MessageThread
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [filterBlocked] backs the feed's instant hide-on-block (issue #410): a topic card is
 * attributed to its starter ([FeedItem.author]), so blocking that person hides the whole
 * card — no separate "unread only"-style toggle, this always applies once anything is
 * blocked. Mirrors [FeedUnreadFilterTest]'s shape for the sibling filter.
 */
class FeedBlockedFilterTest {

    private fun item(id: Long, authorUsername: String) = FeedItem(
        id = id,
        groupId = 1L,
        groupName = "Group",
        lastPostAt = null,
        author = RavelryUser(username = authorUsername),
        title = "Topic $id",
        bodySummary = "",
        postCount = 5,
    )

    @Test
    fun `no blocked users returns every item unchanged`() {
        val items = listOf(item(1, "alice"), item(2, "bob"))
        assertEquals(items, filterBlocked(items, blockedUsernames = emptySet()))
    }

    @Test
    fun `a topic started by a blocked user is dropped`() {
        val fromAlice = item(1, "alice")
        val fromBob = item(2, "bob")

        val result = filterBlocked(listOf(fromAlice, fromBob), blockedUsernames = setOf("alice"))

        assertEquals(listOf(fromBob), result)
    }

    @Test
    fun `matching is case-insensitive`() {
        val fromAlice = item(1, "Alice")
        assertEquals(emptyList(), filterBlocked(listOf(fromAlice), blockedUsernames = setOf("alice")))
    }
}

/**
 * [filterBlockedThreads] is the Messages-list half of issue #410's instant filtering: a
 * conversation whose counterpart is blocked disappears from the mailbox entirely.
 */
class FeedBlockedThreadsFilterTest {

    private fun thread(rootId: Long, counterpartUsername: String?) = MessageThread(
        rootId = rootId,
        messages = listOf(Message(id = rootId, sender = counterpartUsername?.let { RavelryUser(username = it) })),
        subject = "Subject $rootId",
        counterpart = counterpartUsername?.let { RavelryUser(username = it) },
        lastActivityAt = 0L,
        hasUnread = false,
    )

    @Test
    fun `no blocked users returns every thread unchanged`() {
        val threads = listOf(thread(1, "alice"), thread(2, "bob"))
        assertEquals(threads, filterBlockedThreads(threads, blockedUsernames = emptySet()))
    }

    @Test
    fun `a thread with a blocked counterpart is dropped`() {
        val withAlice = thread(1, "alice")
        val withBob = thread(2, "bob")

        val result = filterBlockedThreads(listOf(withAlice, withBob), blockedUsernames = setOf("alice"))

        assertEquals(listOf(withBob), result)
    }

    @Test
    fun `a thread with no resolvable counterpart is never filtered`() {
        val unknown = thread(1, counterpartUsername = null)
        assertEquals(listOf(unknown), filterBlockedThreads(listOf(unknown), blockedUsernames = setOf("alice")))
    }

    @Test
    fun `matching is case-insensitive`() {
        val withAlice = thread(1, "Alice")
        assertEquals(emptyList(), filterBlockedThreads(listOf(withAlice), blockedUsernames = setOf("alice")))
    }
}
