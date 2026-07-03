package com.autom8ed.fibersocial.feed.models

/**
 * A single card in the group feed, derived from a Ravelry forum [Topic].
 *
 * All group content comes through Ravelry's forum topics endpoint. Topics are classified
 * into three card types based on fields the API provides:
 *
 * - [DiscussionTopic] — general conversation or questions (the common case).
 * - [AnnouncementTopic] — sticky topics: KAL sign-ups, mod notices, pinned info.
 * - [ProjectTopic] — topics with attached images: WIP/FO shares.
 *
 * Image URLs are not available in the topic list or detail response — only a count.
 * Actual image loading requires fetching individual posts and is deferred to a future phase.
 *
 * Note: Ravelry group events exist at `ravelry.com/events/` but are not exposed by the API.
 * An `EventTopic` type is deferred until Ravelry provides an events endpoint.
 *
 * @property id Ravelry topic ID.
 * @property groupId ID of the [Group] this item belongs to.
 * @property groupName Display name of the group, shown on the card.
 * @property lastPostAt ISO-8601 timestamp of the most recent reply, used for feed sorting.
 */
sealed class FeedItem {
    abstract val id: Long
    abstract val groupId: Long
    abstract val groupName: String
    abstract val lastPostAt: String?

    /**
     * A topic where at least one post contains an image.
     *
     * @property author User who created the opening post.
     * @property title Topic title.
     * @property imageCount Total number of images across all posts.
     * @property imageUrls Direct image URLs. Empty until post-level fetching is implemented.
     * @property replyCount Total number of posts in the thread.
     */
    data class ProjectTopic(
        override val id: Long,
        override val groupId: Long,
        override val groupName: String,
        override val lastPostAt: String?,
        val author: RavelryUser,
        val title: String,
        val imageCount: Int,
        val imageUrls: List<String>,
        val replyCount: Int,
    ) : FeedItem()

    /**
     * A sticky/pinned topic such as a KAL announcement or moderator notice.
     *
     * @property author User who created the opening post.
     * @property title Topic title.
     * @property bodyPreview Truncated plain-text excerpt of the opening post (max 200 chars).
     * @property bodySummary Full plain-text content of the opening post.
     * @property replyCount Total number of posts in the thread.
     */
    data class AnnouncementTopic(
        override val id: Long,
        override val groupId: Long,
        override val groupName: String,
        override val lastPostAt: String?,
        val author: RavelryUser,
        val title: String,
        val bodyPreview: String,
        val bodySummary: String,
        val replyCount: Int,
    ) : FeedItem()

    /**
     * A general discussion topic — questions, conversation, sharing without images.
     *
     * @property author User who created the opening post.
     * @property title Topic title.
     * @property bodyPreview Truncated plain-text excerpt of the opening post (max 200 chars).
     * @property bodySummary Full plain-text content of the opening post.
     * @property replyCount Total number of posts in the thread.
     * @property latestReplyAuthor User who wrote the most recent reply, or `null` when the
     *   topic has no replies (or the latest reply couldn't be fetched).
     * @property latestReplyPreview Truncated plain-text excerpt of the most recent reply
     *   (max 200 chars), or `null` under the same conditions as [latestReplyAuthor].
     */
    data class DiscussionTopic(
        override val id: Long,
        override val groupId: Long,
        override val groupName: String,
        override val lastPostAt: String?,
        val author: RavelryUser,
        val title: String,
        val bodyPreview: String,
        val bodySummary: String,
        val replyCount: Int,
        val latestReplyAuthor: RavelryUser? = null,
        val latestReplyPreview: String? = null,
    ) : FeedItem() {
        /** Author the feed card should attribute the topic to: latest replier, else opener. */
        val displayAuthor: RavelryUser get() = latestReplyAuthor ?: author

        /** Preview text the feed card should show: latest reply, else the opening post. */
        val displayPreview: String get() = latestReplyPreview ?: bodyPreview
    }
}
