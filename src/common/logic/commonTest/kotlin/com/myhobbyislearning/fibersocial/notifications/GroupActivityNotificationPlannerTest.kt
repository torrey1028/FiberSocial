package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.feed.models.Topic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// 2026-07-03T12:00:00Z, matching the timestamps the topics below carry.
private const val NOW_MS = 1_783_080_000_000L
private val GROUP = Group(id = 1L, name = "Kirkland Fiber Arts Circle", permalink = "kirkland", forumId = 9L)

/** Ravelry's timestamp shape: `"yyyy/MM/dd HH:mm:ss Z"`. */
private fun repliedAt(day: Int, hour: Int): String {
    val dd = day.toString().padStart(2, '0')
    val hh = hour.toString().padStart(2, '0')
    return "2026/07/$dd $hh:00:00 +0000"
}

private fun topic(
    id: Long,
    day: Int = 2,
    hour: Int = 0,
    postsCount: Int = 10,
    lastRead: Int = 0,
) = Topic(
    id = id,
    title = "Topic $id",
    forumId = GROUP.forumId,
    postsCount = postsCount,
    lastRead = lastRead,
    repliedAt = repliedAt(day, hour),
)

/** Epoch millis of the timestamp [topic] builds, for asserting on the persisted mark. */
private fun repliedAtMs(day: Int, hour: Int) =
    JULY_1_2026_UTC_MS + (day - 1) * 86_400_000L + hour * 3_600_000L

private const val JULY_1_2026_UTC_MS = 1_782_864_000_000L

class GroupActivityNotificationPlannerTest {

    @Test
    fun `a group with no recorded mark seeds silently`() {
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = null,
            snapshots = listOf(GroupTopicsSnapshot(GROUP, listOf(topic(1L, day = 2, hour = 6)))),
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        assertEquals(
            KnownGroupActivity(repliedAtMs(2, 6), NOW_MS),
            plan.newKnownGroups.getValue(GROUP.id),
        )
    }

    @Test
    fun `a topic replied to after the mark notifies`() {
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(GROUP.id to KnownGroupActivity(repliedAtMs(2, 0), NOW_MS - 1)),
            snapshots = listOf(GroupTopicsSnapshot(GROUP, listOf(topic(1L, day = 2, hour = 6)))),
            nowMs = NOW_MS,
        )
        val notification = plan.notifications.single()
        assertEquals(GROUP.id, notification.groupId)
        assertEquals("Kirkland Fiber Arts Circle", notification.groupName)
        assertEquals(listOf("Topic 1"), notification.topicTitles)
    }

    @Test
    fun `a topic replied to at or before the mark stays quiet`() {
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(GROUP.id to KnownGroupActivity(repliedAtMs(2, 6), NOW_MS - 1)),
            snapshots = listOf(
                GroupTopicsSnapshot(GROUP, listOf(topic(1L, day = 2, hour = 6), topic(2L, day = 1, hour = 0))),
            ),
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
    }

    @Test
    fun `titles are ordered most recently active first`() {
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(GROUP.id to KnownGroupActivity(repliedAtMs(2, 0), NOW_MS - 1)),
            snapshots = listOf(
                GroupTopicsSnapshot(
                    GROUP,
                    listOf(topic(1L, day = 2, hour = 3), topic(2L, day = 2, hour = 9)),
                ),
            ),
            nowMs = NOW_MS,
        )
        assertEquals(listOf("Topic 2", "Topic 1"), plan.notifications.single().topicTitles)
    }

    @Test
    fun `a topic the user has already read to the end stays quiet`() {
        // Replying yourself bumps replied_at past the mark and advances last_read with it;
        // announcing that would be the app telling the user about their own post.
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(GROUP.id to KnownGroupActivity(repliedAtMs(2, 0), NOW_MS - 1)),
            snapshots = listOf(
                GroupTopicsSnapshot(GROUP, listOf(topic(1L, day = 2, hour = 6, postsCount = 10, lastRead = 10))),
            ),
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        // The mark still advances — the activity happened, it just isn't news.
        assertEquals(repliedAtMs(2, 6), plan.newKnownGroups.getValue(GROUP.id).latestActivityMs)
    }

    @Test
    fun `a muted topic is skipped but still advances the mark`() {
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(GROUP.id to KnownGroupActivity(repliedAtMs(2, 0), NOW_MS - 1)),
            snapshots = listOf(GroupTopicsSnapshot(GROUP, listOf(topic(1L, day = 2, hour = 6)))),
            nowMs = NOW_MS,
            mutedTopics = setOf(1L),
        )
        assertTrue(plan.notifications.isEmpty())
        assertEquals(repliedAtMs(2, 6), plan.newKnownGroups.getValue(GROUP.id).latestActivityMs)
    }

    @Test
    fun `the mark takes the newest reply across the page not the first slot`() {
        // Ravelry's default ordering puts pinned topics first; slot 1 can be months stale.
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = null,
            snapshots = listOf(
                GroupTopicsSnapshot(
                    GROUP,
                    listOf(topic(1L, day = 1, hour = 0), topic(2L, day = 2, hour = 9)),
                ),
            ),
            nowMs = NOW_MS,
        )
        assertEquals(repliedAtMs(2, 9), plan.newKnownGroups.getValue(GROUP.id).latestActivityMs)
    }

    @Test
    fun `a page with no parseable timestamps keeps the previous mark`() {
        // Resetting to 0 would make the next healthy sync announce the whole page.
        val prior = KnownGroupActivity(repliedAtMs(2, 6), NOW_MS - 1)
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(GROUP.id to prior),
            snapshots = listOf(
                GroupTopicsSnapshot(GROUP, listOf(topic(1L).copy(repliedAt = null))),
            ),
            nowMs = NOW_MS,
        )
        assertTrue(plan.notifications.isEmpty())
        assertEquals(
            KnownGroupActivity(prior.latestActivityMs, NOW_MS),
            plan.newKnownGroups.getValue(GROUP.id),
        )
    }

    @Test
    fun `a mark never moves backwards when the page regresses`() {
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(GROUP.id to KnownGroupActivity(repliedAtMs(2, 9), NOW_MS - 1)),
            snapshots = listOf(GroupTopicsSnapshot(GROUP, listOf(topic(1L, day = 2, hour = 3)))),
            nowMs = NOW_MS,
        )
        assertEquals(repliedAtMs(2, 9), plan.newKnownGroups.getValue(GROUP.id).latestActivityMs)
    }

    @Test
    fun `a group unseen for the retention window is forgotten`() {
        val stale = NOW_MS - 61L * 24 * 60 * 60 * 1000
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(
                GROUP.id to KnownGroupActivity(repliedAtMs(2, 0), NOW_MS - 1),
                77L to KnownGroupActivity(repliedAtMs(1, 0), stale),
            ),
            snapshots = listOf(GroupTopicsSnapshot(GROUP, listOf(topic(1L, day = 2, hour = 6)))),
            nowMs = NOW_MS,
        )
        assertEquals(setOf(GROUP.id), plan.newKnownGroups.keys)
    }

    @Test
    fun `a subscribed group not scanned this cycle keeps its mark`() {
        // A group whose page failed contributes no snapshot; its baseline must survive so
        // the next sync compares against it rather than re-seeding.
        val other = KnownGroupActivity(repliedAtMs(2, 3), NOW_MS - 1000)
        val plan = GroupActivityNotificationPlanner.plan(
            knownGroups = mapOf(GROUP.id to KnownGroupActivity(repliedAtMs(2, 0), NOW_MS - 1), 77L to other),
            snapshots = listOf(GroupTopicsSnapshot(GROUP, listOf(topic(1L, day = 2, hour = 6)))),
            nowMs = NOW_MS,
        )
        assertEquals(other, plan.newKnownGroups.getValue(77L))
    }
}

class GroupActivityNotificationContentTest {

    private fun notification(vararg titles: String) =
        NewGroupActivityNotification(groupId = 1L, groupName = "Loom Knitters", topicTitles = titles.toList())

    @Test
    fun `the title names the group`() {
        assertEquals(
            "New posts in Loom Knitters",
            GroupActivityNotificationContent.groupActivityTitle(notification("WIP Wednesday")),
        )
    }

    @Test
    fun `one topic shows its title`() {
        assertEquals(
            "WIP Wednesday",
            GroupActivityNotificationContent.groupActivityText(notification("WIP Wednesday")),
        )
    }

    @Test
    fun `several topics show a count and the newest`() {
        assertEquals(
            "3 topics, including WIP Wednesday",
            GroupActivityNotificationContent.groupActivityText(
                notification("WIP Wednesday", "Yarn swap", "Finished objects"),
            ),
        )
    }

    @Test
    fun `two groups get distinct notification ids`() {
        assertTrue(
            GroupActivityNotificationContent.groupActivityNotificationId(1L) !=
                GroupActivityNotificationContent.groupActivityNotificationId(2L),
        )
    }
}
