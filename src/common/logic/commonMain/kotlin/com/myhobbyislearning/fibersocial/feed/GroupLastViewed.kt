package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.Group

/**
 * Persists when the user last looked at each group (issue #350 part 3).
 *
 * This is what makes the drawer's per-group dots dismissible: a dot means "something has
 * happened in this group since you last opened it", so opening the group is what clears
 * it. See [resolveGroupDots] for the rule and [FeedRepository.getDrawerUnread] for where
 * the activity side comes from.
 */
interface GroupLastViewedStore {
    /** Group id → epoch millis when its feed was last opened, or `null` when nothing was
     *  ever saved. */
    suspend fun load(): Map<Long, Long>?

    suspend fun save(lastViewed: Map<Long, Long>)
}

/**
 * Outcome of [resolveGroupDots].
 *
 * @property unreadGroupForumIds Forum ids ([Group.forumId]) whose drawer row should show a
 *   dot — the drawer keys its rows by forum id, not group id.
 * @property lastViewed The last-viewed map to persist: existing entries untouched, unseen
 *   groups seeded with "now", and entries for groups the user no longer belongs to pruned.
 */
data class GroupActivityDots(
    val unreadGroupForumIds: Set<Long> = emptySet(),
    val lastViewed: Map<Long, Long> = emptyMap(),
)

/**
 * Decides which group rows show an activity dot.
 *
 * A group gets a dot when its most recent activity ([activity], group id → epoch millis)
 * is strictly newer than when the user last viewed it ([lastViewed]).
 *
 * Two deliberate non-dot cases:
 *
 * - **A group with no stored last-viewed entry seeds silently**: it records [now] and
 *   shows nothing. Otherwise a fresh install (or a newly joined group) would light every
 *   row at once, which is noise, not signal — the same seed-silently philosophy the
 *   notification planners use.
 * - **A group whose activity is unknown** (its fetch failed, or no topic carried a
 *   parseable timestamp) shows nothing rather than guessing. A transient network failure
 *   must not invent a dot.
 *
 * Groups absent from [groups] are dropped from the returned map, so left groups don't
 * accumulate forever — mirroring [reconcileGroupOrder]'s pruning.
 */
fun resolveGroupDots(
    groups: List<Group>,
    activity: Map<Long, Long>,
    lastViewed: Map<Long, Long>,
    now: Long,
): GroupActivityDots {
    val updated = mutableMapOf<Long, Long>()
    val dots = mutableSetOf<Long>()
    for (group in groups) {
        val seen = lastViewed[group.id]
        if (seen == null) {
            updated[group.id] = now
            continue
        }
        updated[group.id] = seen
        val latest = activity[group.id] ?: continue
        if (latest > seen) dots += group.forumId
    }
    return GroupActivityDots(unreadGroupForumIds = dots, lastViewed = updated)
}
