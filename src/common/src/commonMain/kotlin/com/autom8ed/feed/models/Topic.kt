package com.autom8ed.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Topic(
    val id: Long,
    val title: String,
    @SerialName("posts_count") val postsCount: Int = 0,
    @SerialName("last_post_at") val lastPostAt: String? = null,
    @SerialName("first_poster") val firstPoster: RavelryUser? = null,
    @SerialName("last_poster") val lastPoster: RavelryUser? = null,
    @SerialName("cover_image_url") val coverImageUrl: String? = null,
    @SerialName("tag_names") val tags: List<String> = emptyList(),
)
