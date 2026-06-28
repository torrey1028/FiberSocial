package com.autom8ed.feed.models

/**
 * Discriminated union of the two card types shown in the feed mockup.
 *
 * ProjectPost — author avatar + image grid (project photos from a group topic)
 * EventPost   — author avatar + single cover image + title + body text
 *
 * Both map to Ravelry Topics; the distinction is whether the topic has multiple
 * images attached (project showcase) or a single cover + body text (event/announcement).
 * Final classification logic will be refined once live API shapes are confirmed.
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
        val imageUrls: List<String>,  // populated in a future phase; imageCount for now
        val imageCount: Int,
        val replyCount: Int,
    ) : FeedItem()

    data class EventPost(
        override val id: Long,
        override val groupId: Long,
        override val groupName: String,
        override val lastPostAt: String?,
        val author: RavelryUser,
        val title: String,
        val coverImageUrl: String?,
        val bodyPreview: String,
        val replyCount: Int,
    ) : FeedItem()
}
