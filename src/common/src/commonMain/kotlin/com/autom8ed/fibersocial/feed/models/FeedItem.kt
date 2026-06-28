package com.autom8ed.fibersocial.feed.models

/**
 * All group content comes through Ravelry's forum topics endpoint. We classify
 * topics into three card types based on fields the API actually provides:
 *
 * ProjectPost      — topic has images (forum_images_count > 0): someone sharing photos of a WIP/FO
 * AnnouncementPost — topic is sticky: KAL sign-ups, mod notices, pinned info
 * DiscussionPost   — everything else: questions, general conversation
 *
 * Image URLs are not available in the topic list or detail response — only a count.
 * Actual image loading is deferred to a future phase (requires fetching posts).
 *
 * Note: Ravelry group events (meetups, etc.) exist at ravelry.com/events/ but are not
 * exposed by the API. EventPost is deferred until Ravelry provides an events endpoint.
 */
sealed class FeedItem {
    abstract val id: Long
    abstract val groupId: Long
    abstract val groupName: String
    abstract val lastPostAt: String?

    data class ProjectPost(
        override val id: Long,
        override val groupId: Long,
        override val groupName: String,
        override val lastPostAt: String?,
        val author: RavelryUser,
        val title: String,
        val imageCount: Int,
        val imageUrls: List<String>, // deferred: requires fetching posts
        val replyCount: Int,
    ) : FeedItem()

    data class AnnouncementPost(
        override val id: Long,
        override val groupId: Long,
        override val groupName: String,
        override val lastPostAt: String?,
        val author: RavelryUser,
        val title: String,
        val bodyPreview: String,
        val replyCount: Int,
    ) : FeedItem()

    data class DiscussionPost(
        override val id: Long,
        override val groupId: Long,
        override val groupName: String,
        override val lastPostAt: String?,
        val author: RavelryUser,
        val title: String,
        val bodyPreview: String,
        val replyCount: Int,
    ) : FeedItem()
}
