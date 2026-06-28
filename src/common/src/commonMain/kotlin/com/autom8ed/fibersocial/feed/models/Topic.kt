package com.autom8ed.fibersocial.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Covers both list-view fields and detail-view fields (detail adds createdByUser + summary). */
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
    // Only present in /topics/{id}.json detail response
    @SerialName("created_by_user") val createdByUser: RavelryUser? = null,
    val summary: String? = null,
)
