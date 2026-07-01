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
 * @property voteTotals Vote-type name (e.g. "love") to total vote count on this post.
 *   Only populated when requested via `include=vote_totals`.
 * @property userVotes Vote-type names the current user has cast on this post
 *   (e.g. `["love"]`). Only populated when requested via `include=user_votes`.
 */
@Serializable
data class Post(
    val id: Long,
    @SerialName("body_html") val bodyHtml: String = "",
    @SerialName("created_at") val createdAt: String? = null,
    val user: RavelryUser? = null,
    @SerialName("vote_totals") val voteTotals: Map<String, Int> = emptyMap(),
    @SerialName("user_votes") val userVotes: List<String> = emptyList(),
)
