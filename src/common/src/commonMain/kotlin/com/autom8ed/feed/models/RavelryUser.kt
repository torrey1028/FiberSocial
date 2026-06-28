package com.autom8ed.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class RavelryUser(
    val username: String,
    @SerialName("small_photo_url") val avatarUrl: String? = null,
)
