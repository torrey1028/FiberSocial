package com.autom8ed.fibersocial.feed.models

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A single post (reply) in a Ravelry forum topic thread.
 *
 * @property id Ravelry post ID.
 * @property bodyHtml HTML content of the post body.
 * @property createdAt ISO-8601 timestamp when the post was submitted.
 * @property user Author of the post.
 */
@Serializable
data class Post(
    val id: Long,
    @SerialName("body_html") val bodyHtml: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val user: RavelryUser? = null,
)
