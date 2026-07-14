package com.myhobbyislearning.fibersocial.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Ravelry group the user is a member of.
 *
 * @property id Ravelry's numeric group ID.
 * @property name Display name of the group.
 * @property permalink URL-safe slug used in `ravelry.com/groups/{permalink}`.
 * @property forumId ID of the group's discussion forum, used to fetch [Topic] lists.
 * @property badgeUrl Optional URL to the group's badge image.
 * @property shortDescription Optional brief description of the group.
 */
@Serializable
data class Group(
    val id: Long,
    val name: String,
    val permalink: String,
    @SerialName("forum_id") val forumId: Long,
    @SerialName("badge_url") val badgeUrl: String? = null,
    @SerialName("short_description") val shortDescription: String? = null,
)
