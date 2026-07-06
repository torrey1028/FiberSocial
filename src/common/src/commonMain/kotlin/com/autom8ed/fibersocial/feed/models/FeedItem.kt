package com.autom8ed.fibersocial.feed.models

/**
 * A single card in the group feed, derived from a Ravelry forum [Topic].
 *
 * The card follows the forum style Ravelry's API is built for (issue #185): it's
 * attributed to the topic's starter (not the latest replier), shows the author-written
 * summary rendered in full (or nothing when there is none), the reply count, and how
 * many posts are unread relative to Ravelry's own read marker. Whether posts carry
 * images doesn't affect listing (issue #77). [sticky] pins the card to the top.
 *
 * @property id Ravelry topic ID.
 * @property groupId ID of the [Group] this item belongs to.
 * @property groupName Display name of the group, shown on the card.
 * @property lastPostAt ISO-8601 timestamp of the most recent reply, used for feed sorting.
 * @property author The topic's starter (opening-post author) — who the card attributes to.
 * @property title Topic title.
 * @property bodySummary The author-written topic summary as raw Markdown source. Empty
 *   when the topic has no summary (the card then shows title + meta only).
 * @property bodySummaryHtml Ravelry's HTML rendering of the topic summary; preferred over
 *   [bodySummary] when present (see [Topic.summaryHtml]). Empty when unavailable.
 * @property postCount Total posts in the topic, including the opening post — so it lines
 *   up with [unreadCount], which also counts from post 1.
 * @property unreadCount Posts unread relative to Ravelry's read marker (`postsCount`
 *   minus `last_read`, floored at 0) — web-consistent, since the app also POSTs the
 *   marker back when a topic is viewed.
 * @property firstUnreadPostNumber The 1-based post number to scroll to when opening the
 *   topic (the first unread post = `last_read + 1`), or `null` when nothing is unread.
 * @property sticky Whether a moderator pinned this topic to the top of the forum.
 */
data class FeedItem(
    val id: Long,
    val groupId: Long,
    val groupName: String,
    val lastPostAt: String?,
    val author: RavelryUser,
    val title: String,
    val bodySummary: String,
    val bodySummaryHtml: String = "",
    val postCount: Int,
    val unreadCount: Int = 0,
    val firstUnreadPostNumber: Int? = null,
    val sticky: Boolean = false,
) {
    /** Whether the topic has a summary to render on the card. */
    val hasSummary: Boolean get() = bodySummary.isNotBlank() || bodySummaryHtml.isNotBlank()
}
