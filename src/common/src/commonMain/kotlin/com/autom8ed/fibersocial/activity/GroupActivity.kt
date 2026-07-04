package com.autom8ed.fibersocial.activity

/**
 * Activity types a group's activity page renders, keyed by the entry's
 * `o-icon--<key>` icon class. Only the keys observed in real captures are
 * modeled; anything else parses as [UNKNOWN] with the raw key preserved on
 * [GroupActivityItem.typeKey].
 */
enum class GroupActivityType(internal val iconKey: String) {
    /** A member added a photo to one of their projects. */
    PROJECT_PHOTO("projects"),

    /** A member magic-linked a pattern/yarn/person in a forum post. */
    MAGIC_LINK("magic_link"),

    UNKNOWN("");

    internal companion object {
        fun fromIconKey(key: String): GroupActivityType =
            entries.firstOrNull { it != UNKNOWN && it.iconKey == key } ?: UNKNOWN
    }
}

/**
 * One entry on a group's activity page (`/groups/{permalink}/activity`).
 *
 * @property id Ravelry's activity id (from the entry's `activity_<id>` element id), or null
 *   when the markup carries none. Stable across refreshes, so usable for dedup.
 * @property typeKey Raw icon key (`o-icon--<key>`) so callers can distinguish activity
 *   types that aren't modeled in [GroupActivityType] yet.
 * @property targetUrl Raw href the entry links to, exactly as found in the markup (absolute
 *   on real pages, but the parser does not require it) — a project page for
 *   [GroupActivityType.PROJECT_PHOTO], a forum post for [GroupActivityType.MAGIC_LINK].
 * @property thumbnailUrl Small cropped photo shown on the entry, when present.
 * @property ageText Relative age exactly as the site renders it, e.g. "about 18 hours ago".
 *   The page carries no absolute timestamps; entries are newest-first in page order.
 * @property projectUsername Owner username when [targetUrl] is a project page — together
 *   with [projectPermalink] this is the key for `GET /projects/{username}/{permalink}.json`.
 */
data class GroupActivityItem(
    val id: Long?,
    val type: GroupActivityType,
    val typeKey: String,
    val title: String,
    val targetUrl: String,
    val thumbnailUrl: String?,
    val ageText: String,
    val projectUsername: String?,
    val projectPermalink: String?,
)

/** One page of a group's activity feed, plus its position in the site's pagination. */
data class GroupActivityPage(
    val items: List<GroupActivityItem>,
    val currentPage: Int,
    val totalPages: Int,
)
