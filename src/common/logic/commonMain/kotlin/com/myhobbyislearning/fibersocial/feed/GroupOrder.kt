package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.Group

/**
 * Persists the user's chosen group display order (issue #97).
 *
 * The order determines the drawer listing, and its first group is the default group
 * shown when the app opens.
 */
interface GroupOrderStore {
    /** Stored group IDs in display order, or `null` when no order was ever saved. */
    suspend fun load(): List<Long>?

    suspend fun save(order: List<Long>)
}

/**
 * Orders [groups] by [storedOrder], applying the issue #97 list-maintenance rules:
 *
 * - groups keep their stored position;
 * - newly-joined groups (absent from the stored order) append at the bottom, keeping
 *   their fetched order;
 * - stored IDs whose group no longer exists (left groups) drop out.
 *
 * A `null` [storedOrder] (nothing saved yet) keeps the fetched order — the caller
 * persists the result either way, which seeds the initial order on first run and keeps
 * the default group stable afterwards instead of drifting with fetch order.
 */
fun reconcileGroupOrder(groups: List<Group>, storedOrder: List<Long>?): List<Group> {
    if (storedOrder == null) return groups
    val byId = groups.associateBy { it.id }
    // distinct() guards against corrupted prefs or a future reorder-persistence bug
    // writing a duplicate ID — without it, that group would appear twice below.
    val dedupedOrder = storedOrder.distinct()
    val stored = dedupedOrder.toSet()
    return dedupedOrder.mapNotNull { byId[it] } + groups.filter { it.id !in stored }
}
