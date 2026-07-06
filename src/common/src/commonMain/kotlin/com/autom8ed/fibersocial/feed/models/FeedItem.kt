package com.autom8ed.fibersocial.feed.models

/**
 * A single card in the group feed, derived from a Ravelry forum [Topic].
 *
 * Every topic renders as the same card; there is deliberately no per-kind type hierarchy.
 * Whether posts in the thread carry images does not affect how the topic is listed
 * (issue #77 — a photo in a thread doesn't make it any less of a discussion; the images
 * themselves render in the topic detail view). The one distinction the feed makes is
 * [sticky], which pins the card to the top with a pinned label.
 *
 * @property id Ravelry topic ID.
 * @property groupId ID of the [Group] this item belongs to.
 * @property groupName Display name of the group, shown on the card.
 * @property lastPostAt ISO-8601 timestamp of the most recent reply, used for feed sorting.
 * @property author User who created the opening post.
 * @property title Topic title.
 * @property bodyPreview Truncated plain-text excerpt of the author-written topic summary
 *   (max 200 chars).
 * @property bodySummary The author-written topic summary as raw Markdown source.
 * @property bodySummaryHtml Ravelry's HTML rendering of the topic summary; preferred over
 *   [bodySummary] when present (see [Topic.summaryHtml]). Empty when unavailable.
 * @property replyCount Total number of posts in the thread.
 * @property sticky Whether a moderator pinned this topic to the top of the forum. Sticky
 *   cards always attribute to the opening post ([latestReplyAuthor]/[latestReplyPreview]
 *   stay null — an announcement is its opening post, not its latest comment). The old
 *   sealed hierarchy made the combination unrepresentable; `init` enforces it now.
 * @property latestReplyAuthor User who wrote the most recent reply, or `null` when the
 *   topic has no replies (or the latest reply couldn't be fetched).
 * @property latestReplyPreview Truncated plain-text excerpt of the most recent reply
 *   (max 200 chars), or `null` under the same conditions as [latestReplyAuthor].
 * @property latestReplyHtml Ravelry's HTML rendering of the most recent reply's body —
 *   the source for the card's rich preview (issue #154) — or `null` under the same
 *   conditions as [latestReplyAuthor].
 */
data class FeedItem(
    val id: Long,
    val groupId: Long,
    val groupName: String,
    val lastPostAt: String?,
    val author: RavelryUser,
    val title: String,
    val bodyPreview: String,
    val bodySummary: String,
    val bodySummaryHtml: String = "",
    val replyCount: Int,
    val sticky: Boolean = false,
    val latestReplyAuthor: RavelryUser? = null,
    val latestReplyPreview: String? = null,
    val latestReplyHtml: String? = null,
) {
    init {
        require(!sticky || (latestReplyAuthor == null && latestReplyPreview == null && latestReplyHtml == null)) {
            "Sticky topics attribute to the opening post; latest-reply fields must stay null"
        }
    }

    /** Author the feed card should attribute the topic to: latest replier, else opener. */
    val displayAuthor: RavelryUser get() = latestReplyAuthor ?: author

    /** Preview text the feed card should show: latest reply, else the opening post. */
    val displayPreview: String get() = latestReplyPreview ?: bodyPreview
}
