package com.autom8ed.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class Group(
    val id: Long,
    val name: String,
    val permalink: String,
    @SerialName("members_count") val membersCount: Int = 0,
    @SerialName("avatar_image_path") val avatarUrl: String? = null,
)
