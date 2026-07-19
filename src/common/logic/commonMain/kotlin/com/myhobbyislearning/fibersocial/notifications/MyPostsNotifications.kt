package com.myhobbyislearning.fibersocial.notifications

import com.myhobbyislearning.fibersocial.feed.models.Topic
import kotlin.time.Duration.Companion.days
import kotlinx.serialization.Serializable

/** How long a known topic is remembered after it was last seen before being pruned. */
private val KNOWN_TOPIC_RETENTION = 60.days

/**
 * The last activity a sync observed on one of the user's topics.
 *
 * @property postCount [Topic.postsCount] as of the last sync that saw the topic.
 * @property lastSeenMs Epoch millis the topic was last seen in a sync, for pruning.
 */
@Serializable
data class KnownTopicActivity(
    val postCount: Int,
    val lastSeenMs: Long,
)

/**
 * A "new replies in a topic you posted in" notification to post.
 *
 * @property groupName Attributed group, or empty when the topic's forum matches none of
 *   the user's groups (same rule as the My Posts feed itself).
 * @property newReplyCount How many posts arrived since the last sync that the user
 *   hasn't already read.
 */
data class NewReplyNotification(
    val topicId: Long,
    val topicTitle: String,
    val groupName: String,
    val newReplyCount: Int,
)

/** What the My Posts leg of a sync cycle produced. */
data class MyPostsPlan(
    val notifications: List<NewReplyNotification>,
    val newKnownTopics: Map<Long, KnownTopicActivity>,
)

/**
 * Pure planning logic for My Posts activity notifications: given the topics the user has
 * posted in (page 1 of `filtered_topics`, newest activity first) and what the previous
 * sync recorded, decides what to notify and what to persist.
 *
 * Follows [EventNotificationPlanner]'s seeding rule: an absent or empty known map seeds
 * counts silently — a fresh install must not announce every old reply, and one glitchy
 * sync that persisted an empty map must not turn the next healthy one into a storm.
 * A topic not in the known map (newly posted in, or pruned) also seeds silently: with no
 * prior count there is no defensible "N new" to claim.
 */
object MyPostsNotificationPlanner {

    /**
     * Plans the My Posts leg of one sync cycle.
     *
     * A topic notifies only when BOTH hold: its post count grew since the last sync,
     * AND it has posts beyond the user's own read marker. The read-marker gate keeps
     * already-read activity quiet — most importantly the user's own replies, which grow
     * the count but advance their read marker when they leave the thread.
     *
     * The notified count is `postsCount - max(known count, read marker)`: what's new
     * since the last sync and still unread, never inflated by posts the user has seen.
     *
     * @param knownTopics Per-topic activity persisted by the previous sync (null before
     *   the first sync).
     * @param myTopics Topics the user has posted in, from [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.getMyTopics].
     * @param groupNamesByForumId The user's groups keyed by forum, for attribution.
     * @param mutedTopics Topic ids the user muted reply notifications for (issue #338).
     *   A muted topic is skipped for notifying but STILL kept in [MyPostsPlan.newKnownTopics]
     *   with a current count, so an unmute later measures growth from now rather than
     *   firing the backlog that accrued while muted (same seed-silently philosophy as the
     *   rest of the planner).
     * @param nowMs Current epoch millis (for last-seen stamps and pruning).
     */
    fun plan(
        knownTopics: Map<Long, KnownTopicActivity>?,
        myTopics: List<Topic>,
        groupNamesByForumId: Map<Long, String>,
        nowMs: Long,
        mutedTopics: Set<Long> = emptySet(),
    ): MyPostsPlan {
        val known = knownTopics ?: emptyMap()
        val notifications = if (known.isEmpty()) {
            emptyList()
        } else {
            myTopics.mapNotNull { topic ->
                if (topic.id in mutedTopics) return@mapNotNull null
                val prior = known[topic.id] ?: return@mapNotNull null
                val baseline = maxOf(prior.postCount, topic.lastRead)
                val newCount = topic.postsCount - baseline
                if (newCount <= 0) return@mapNotNull null
                NewReplyNotification(
                    topicId = topic.id,
                    topicTitle = topic.title,
                    groupName = groupNamesByForumId[topic.forumId].orEmpty(),
                    newReplyCount = newCount,
                )
            }
        }

        val retained = known.filterValues { activity ->
            nowMs - activity.lastSeenMs < KNOWN_TOPIC_RETENTION.inWholeMilliseconds
        }
        // Every currently-seen topic gets its count and last-seen stamp refreshed; one
        // that stays off the first page for the whole retention window is forgotten and
        // re-seeds silently on return.
        val newKnownTopics = retained + myTopics.associate { topic ->
            topic.id to KnownTopicActivity(postCount = topic.postsCount, lastSeenMs = nowMs)
        }
        return MyPostsPlan(notifications = notifications, newKnownTopics = newKnownTopics)
    }
}

/**
 * Display copy and ID derivation for reply notifications, shared by the platform
 * notifiers (like [EventNotificationContent] for events).
 *
 * The notification ID is the topic id folded to an Int; platforms scope it with a
 * topic-specific tag/identifier, so a fresh batch of replies to the same topic replaces
 * that topic's earlier notification instead of stacking, while different topics stack.
 */
object MyPostsNotificationContent {

    fun replyNotificationId(topicId: Long): Int = topicId.hashCode()

    fun replyTitle(notification: NewReplyNotification): String = notification.topicTitle

    fun replyText(notification: NewReplyNotification): String {
        val count = notification.newReplyCount
        val replies = if (count == 1) "1 new reply" else "$count new replies"
        return if (notification.groupName.isBlank()) {
            replies
        } else {
            "$replies in ${notification.groupName}"
        }
    }

    /**
     * Line for the stack's summary notification, totalled across every reply
     * notification currently showing (not just the latest sync's batch).
     */
    fun summaryText(totalReplies: Int, topicCount: Int): String {
        val replies = if (totalReplies == 1) "1 new reply" else "$totalReplies new replies"
        val topics = if (topicCount == 1) "1 topic" else "$topicCount topics"
        return "$replies in $topics"
    }
}
