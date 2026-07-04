package com.autom8ed.fibersocial.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Ravelry forum topic, returned by both list and detail endpoints.
 *
 * The list endpoint (`/forums/{id}/topics.json`) returns most fields but omits
 * [createdByUser] and [summary]. The detail endpoint (`/topics/{id}.json`) includes
 * both, so [FeedRepository] fetches details in parallel after loading the list.
 *
 * @property id Ravelry topic ID.
 * @property title Topic title as entered by the author.
 * @property forumId ID of the forum this topic belongs to. Zero in detail responses.
 * @property postsCount Total number of replies (including the opening post).
 * @property imagesCount Number of images attached across all posts in the topic.
 *   Deliberately unused for feed classification (issue #77: branching on it silently
 *   hid topics); retained as scrape-contract documentation and for a future
 *   image-preview feature.
 * @property repliedAt ISO-8601 timestamp of the most recent reply.
 * @property createdAt ISO-8601 timestamp when the topic was first posted.
 * @property sticky Whether a moderator has pinned this topic to the top of the forum.
 * @property archived Whether the topic is closed for new replies.
 * @property createdByUser Author of the opening post. Only present in detail responses.
 * @property summary Plain-text excerpt of the opening post body. Only present in detail responses.
 */
@Serializable
data class Topic(
    val id: Long,
    val title: String,
    @SerialName("forum_id") val forumId: Long = 0,
    @SerialName("forum_posts_count") val postsCount: Int = 0,
    @SerialName("forum_images_count") val imagesCount: Int = 0,
    @SerialName("replied_at") val repliedAt: String? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val sticky: Boolean = false,
    val archived: Boolean = false,
    @SerialName("created_by_user") val createdByUser: RavelryUser? = null,
    val summary: String? = null,
)
