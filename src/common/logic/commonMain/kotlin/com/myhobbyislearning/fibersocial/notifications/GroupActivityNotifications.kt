package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.feed.models.Topic
import com.myhobbyislearning.fibersocial.feed.parseRavelryTimestamp
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.Serializable

/** How long a subscribed group's mark survives without a sync refreshing it. Matches
 *  `KNOWN_EVENT_RETENTION` / `KNOWN_TOPIC_RETENTION` / `KNOWN_MESSAGE_RETENTION`. */
private val KNOWN_GROUP_RETENTION = 60.days

/**
 * How far down a subscribed group's topic list one sync looks for new posts. Small on
 * purpose — this is one request PER SUBSCRIBED GROUP — and deliberately not 1, for the
 * same reason `FeedRepository.getGroupActivity` documents: Ravelry's default ordering
 * puts pinned topics first, so slot 1 can hold a sticky thread whose last reply is months
 * old while genuinely new replies sit below it.
 */
internal const val GROUP_ACTIVITY_TOPIC_PAGE_SIZE = 10

/**
 * The last forum activity a sync observed in a group the user subscribed to.
 *
 * A single high-water mark per group, not a per-topic map: the leg looks at one short page
 * of a busy public forum, so a map keyed by topic would both grow without bound and
 * mistake "scrolled back onto page 1" for "new" — the same paging trap [KnownMessages]
 * documents at length. `replied_at` is monotonic per topic, so a mark cannot make that
 * mistake.
 *
 * @property latestActivityMs Newest reply timestamp (epoch millis) seen across the group's
 *   scanned topics. Anything at or below it is already accounted for.
 * @property lastSeenMs Epoch millis the group was last seen in a sync, for pruning.
 */
@Serializable
data class KnownGroupActivity(
    val latestActivityMs: Long,
    val lastSeenMs: Long,
)

/**
 * One subscribed group and the page of its topics a sync fetched.
 *
 * @property group The group, for attribution and for keying the mark.
 * @property topics Its newest topics ([GROUP_ACTIVITY_TOPIC_PAGE_SIZE] of them).
 */
data class GroupTopicsSnapshot(
    val group: Group,
    val topics: List<Topic>,
)

/**
 * A "new posts in a group you subscribed to" notification to post (issue #510).
 *
 * @property groupId The group, so a tap can open its feed.
 * @property groupName Display name, for the notification's title.
 * @property topicTitles Titles of the topics that gained posts, most recently active
 *   first. Never empty — a notification with nothing to name is not planned.
 */
data class NewGroupActivityNotification(
    val groupId: Long,
    val groupName: String,
    val topicTitles: List<String>,
)

/** What the group-activity leg of a sync cycle produced. */
data class GroupActivityPlan(
    val notifications: List<NewGroupActivityNotification>,
    val newKnownGroups: Map<Long, KnownGroupActivity>,
)

/**
 * Pure planning logic for group-activity notifications: given a page of topics from each
 * group the user subscribed to and what the previous sync recorded, decides which groups
 * to announce and what to persist.
 *
 * Not to be confused with the Activity Feed epic's notifications (#498), which are about
 * *member* activity — project photos, stash, favorites — scraped from a group's Activity
 * tab. This leg is about forum posts, from the same JSON topic list the feed renders.
 *
 * Follows the seeding rule the other legs share: a group with no recorded mark seeds
 * silently rather than announcing every topic that was already there. Subscribing to a
 * group therefore means "tell me what happens from now on", not "tell me what I missed".
 */
object GroupActivityNotificationPlanner {

    /**
     * Plans the group-activity leg of one sync cycle.
     *
     * A topic counts as new activity when BOTH hold: its newest reply is later than the
     * group's mark, AND it has posts beyond the user's read marker. The read-marker gate
     * is what keeps the user's own posting quiet — replying in a group bumps `replied_at`
     * past the mark, but also advances `last_read` to the end of the thread.
     *
     * @param knownGroups Per-group marks persisted by the previous sync (null before the
     *   first one).
     * @param snapshots One entry per subscribed group, with a page of its topics.
     * @param nowMs Current epoch millis (for last-seen stamps and pruning).
     * @param mutedTopics Topic ids the user muted (issue #338). Honoured here too: a muted
     *   topic must not shout through a different leg than the one it was muted from. Its
     *   replies still advance the group's mark, so unmuting measures from now rather than
     *   replaying the backlog.
     */
    fun plan(
        knownGroups: Map<Long, KnownGroupActivity>?,
        snapshots: List<GroupTopicsSnapshot>,
        nowMs: Long,
        mutedTopics: Set<Long> = emptySet(),
    ): GroupActivityPlan {
        val known = knownGroups ?: emptyMap()
        val notifications = mutableListOf<NewGroupActivityNotification>()
        val marks = mutableMapOf<Long, KnownGroupActivity>()

        snapshots.forEach { (group, topics) ->
            val activity = topics.mapNotNull { topic ->
                val repliedAtMs = parseRavelryTimestamp(topic.repliedAt)?.toEpochMilliseconds()
                repliedAtMs?.let { topic to it }
            }
            // A page where nothing carried a parseable timestamp says nothing about the
            // group: keep the previous mark (refreshing its last-seen stamp so the group
            // isn't pruned out from under a live subscription) rather than resetting it to
            // 0, which would make the next healthy sync announce the whole page.
            val newestMs = activity.maxOfOrNull { it.second }
            val prior = known[group.id]
            marks[group.id] = KnownGroupActivity(
                latestActivityMs = maxOf(newestMs ?: 0L, prior?.latestActivityMs ?: 0L),
                lastSeenMs = nowMs,
            )
            if (prior == null) return@forEach // seeds silently — see the class KDoc
            val fresh = activity
                .filter { (topic, repliedAtMs) ->
                    repliedAtMs > prior.latestActivityMs &&
                        topic.postsCount > topic.lastRead &&
                        topic.id !in mutedTopics
                }
                .sortedByDescending { it.second }
                .map { it.first.title }
            if (fresh.isNotEmpty()) {
                notifications += NewGroupActivityNotification(
                    groupId = group.id,
                    groupName = group.name,
                    topicTitles = fresh,
                )
            }
        }

        // Groups that stopped being scanned (unsubscribed, or left) age out rather than
        // accumulating forever; one that comes back re-seeds silently.
        val retained = known.filterValues { nowMs - it.lastSeenMs < KNOWN_GROUP_RETENTION.inWholeMilliseconds }
        return GroupActivityPlan(notifications = notifications, newKnownGroups = retained + marks)
    }
}

/**
 * Display copy and ID derivation for group-activity notifications, shared by the platform
 * notifiers (like [MyPostsNotificationContent] for replies).
 *
 * The notification ID is the group id folded to an Int; both platforms scope replacement
 * by a group-specific tag/identifier, so a fresh batch for the same group replaces that
 * group's earlier banner with a current count instead of stacking, while different groups
 * stack. That is the same shape the reply leg uses, and for the same reason: a per-group
 * banner is a running summary, not a log.
 */
object GroupActivityNotificationContent {

    fun groupActivityNotificationId(groupId: Long): Int = groupId.hashCode()

    fun groupActivityTitle(notification: NewGroupActivityNotification): String =
        "New posts in ${notification.groupName}"

    /**
     * The banner's body: the topic itself when only one gained posts, otherwise a count
     * plus the most recently active one — a list of every title would be truncated by both
     * platforms' single-line bodies anyway.
     */
    fun groupActivityText(notification: NewGroupActivityNotification): String {
        val titles = notification.topicTitles
        return when (titles.size) {
            // Defensive: the planner never emits an empty batch.
            0 -> "New posts"
            1 -> titles.single()
            else -> "${titles.size} topics, including ${titles.first()}"
        }
    }
}
