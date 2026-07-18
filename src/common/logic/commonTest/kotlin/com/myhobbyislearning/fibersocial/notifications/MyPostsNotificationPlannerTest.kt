package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.feed.models.Topic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val NOW_MS = 1_800_000_000_000L
private val GROUPS = mapOf(9L to "Kirkland Fiber Arts Circle")

private fun topic(id: Long, postsCount: Int, lastRead: Int = 0, forumId: Long = 9L) = Topic(
    id = id,
    title = "Topic $id",
    forumId = forumId,
    postsCount = postsCount,
    lastRead = lastRead,
)

class MyPostsNotificationPlannerTest {

    @Test
    fun `a null known map seeds silently`() {
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = null,
            myTopics = listOf(topic(1L, postsCount = 7)),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        assertEquals(KnownTopicActivity(7, NOW_MS), plan.newKnownTopics.getValue(1L))
    }

    @Test
    fun `an empty known map seeds silently too`() {
        // One glitchy sync that persisted an empty map must not turn the next healthy
        // one into a notification storm (same rule as EventNotificationPlanner).
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = emptyMap(),
            myTopics = listOf(topic(1L, postsCount = 7)),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
    }

    @Test
    fun `growth beyond the known count and read marker notifies with the unread delta`() {
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = mapOf(1L to KnownTopicActivity(postCount = 3, lastSeenMs = NOW_MS - 1)),
            myTopics = listOf(topic(1L, postsCount = 6, lastRead = 3)),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        val n = plan.notifications.single()
        assertEquals(3, n.newReplyCount)
        assertEquals("Kirkland Fiber Arts Circle", n.groupName)
    }

    @Test
    fun `already-read growth stays quiet`() {
        // The user's own reply grows the count but advances their read marker.
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = mapOf(1L to KnownTopicActivity(postCount = 3, lastSeenMs = NOW_MS - 1)),
            myTopics = listOf(topic(1L, postsCount = 4, lastRead = 4)),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        // But the baseline still advances for the next sync.
        assertEquals(4, plan.newKnownTopics.getValue(1L).postCount)
    }

    @Test
    fun `a partially-read batch notifies only the unread remainder`() {
        // 3 known -> 8 posts, user has read up to 6: notify the 2 they haven't seen,
        // not the 5 the sync technically observed.
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = mapOf(1L to KnownTopicActivity(postCount = 3, lastSeenMs = NOW_MS - 1)),
            myTopics = listOf(topic(1L, postsCount = 8, lastRead = 6)),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        assertEquals(2, plan.notifications.single().newReplyCount)
    }

    @Test
    fun `an unknown topic seeds silently even when others notify`() {
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = mapOf(1L to KnownTopicActivity(postCount = 3, lastSeenMs = NOW_MS - 1)),
            myTopics = listOf(
                topic(1L, postsCount = 5),
                topic(2L, postsCount = 9), // never seen before: no prior count to diff
            ),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        assertEquals(listOf(1L), plan.notifications.map { it.topicId })
        assertEquals(9, plan.newKnownTopics.getValue(2L).postCount)
    }

    @Test
    fun `an unattributed forum keeps an empty group name`() {
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = mapOf(1L to KnownTopicActivity(postCount = 3, lastSeenMs = NOW_MS - 1)),
            myTopics = listOf(topic(1L, postsCount = 5, forumId = 999L)),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        assertEquals("", plan.notifications.single().groupName)
    }

    @Test
    fun `topics unseen past the retention window are pruned`() {
        val staleMs = NOW_MS - 61L * 24 * 60 * 60 * 1000
        val freshMs = NOW_MS - 1L * 24 * 60 * 60 * 1000
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = mapOf(
                1L to KnownTopicActivity(postCount = 3, lastSeenMs = staleMs),
                2L to KnownTopicActivity(postCount = 4, lastSeenMs = freshMs),
            ),
            myTopics = emptyList(),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        assertEquals(setOf(2L), plan.newKnownTopics.keys)
    }

    @Test
    fun `a currently-seen topic refreshes its retention stamp`() {
        val staleMs = NOW_MS - 61L * 24 * 60 * 60 * 1000
        val plan = MyPostsNotificationPlanner.plan(
            knownTopics = mapOf(1L to KnownTopicActivity(postCount = 3, lastSeenMs = staleMs)),
            myTopics = listOf(topic(1L, postsCount = 3)),
            groupNamesByForumId = GROUPS,
            nowMs = NOW_MS,
        )
        // Pruned for staleness but re-seeded by being visible — with a fresh stamp.
        assertEquals(KnownTopicActivity(3, NOW_MS), plan.newKnownTopics.getValue(1L))
    }
}

class MyPostsNotificationContentTest {

    private fun notification(count: Int, groupName: String = "Kirkland Fiber Arts Circle") =
        NewReplyNotification(topicId = 1L, topicTitle = "Cast-on question", groupName = groupName, newReplyCount = count)

    @Test
    fun `text uses singular and plural reply wording`() {
        assertEquals(
            "1 new reply in Kirkland Fiber Arts Circle",
            MyPostsNotificationContent.replyText(notification(1)),
        )
        assertEquals(
            "3 new replies in Kirkland Fiber Arts Circle",
            MyPostsNotificationContent.replyText(notification(3)),
        )
    }

    @Test
    fun `text omits the group clause when unattributed`() {
        assertEquals("2 new replies", MyPostsNotificationContent.replyText(notification(2, groupName = "")))
    }

    @Test
    fun `title is the topic title`() {
        assertEquals("Cast-on question", MyPostsNotificationContent.replyTitle(notification(1)))
    }
}
