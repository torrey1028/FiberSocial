package com.autom8ed.fibersocial.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Group(
    val id: Long,
    val name: String,
    val permalink: String,
    @SerialName("forum_id") val forumId: Long,
    @SerialName("badge_url") val badgeUrl: String? = null,
    @SerialName("short_description") val shortDescription: String? = null,
)
