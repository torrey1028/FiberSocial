package com.autom8ed.fibersocial.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A Ravelry user, as returned by `/current_user.json` and embedded in topic detail responses.
 *
 * @property username Ravelry handle, used in profile URLs and API paths.
 * @property avatarUrl Optional URL to the user's small profile photo.
 */
@Serializable
data class RavelryUser(
    val username: String,
    @SerialName("small_photo_url") val avatarUrl: String? = null,
)
