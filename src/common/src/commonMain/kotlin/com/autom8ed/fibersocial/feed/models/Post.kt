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
    /** Source text of the post (markdown/BBcode), used to pre-fill the editor. */
    val body: String = "",
    /**
     * Whether the signed-in user may edit this post, as reported by Ravelry.
     *
     * Tri-state on purpose:
     * - `true`  — Ravelry says the post is editable.
     * - `false` — Ravelry says it is NOT (locked topic, too old, not yours, …).
     * - `null`  — UNKNOWN. Ravelry returns `null` on a freshly-created post (the
     *   `reply.json` / `forum_posts` update response) and only fills in a real
     *   value once the post settles / on a later thread fetch.
     *
     * The UI treats `null` OPTIMISTICALLY as editable (see `canEdit` in
     * TopicDetailScreen: `mine && editable != false`). Rationale: the only posts
     * that come back `null` are ones you just created, and a reply you just made
     * is always editable by you — so showing the edit affordance immediately is
     * correct, and we never override an explicit `false` on an existing post.
     *
     * KNOWN GAP: if a `null` post turns out to be genuinely non-editable, the edit
     * request 403s. Today that 403 is misclassified as session expiry and bounces
     * the user to login instead of showing an inline "can't edit" error. Tracked in
     * issue #82 (which must cover this optimistic-edit path specifically). This is
     * why `editable` is nullable rather than coerced to `false` — the tri-state is
     * load-bearing for the edit-gating decision above.
     */
    val editable: Boolean? = null,
    @SerialName("created_at") val createdAt: String? = null,
    val user: RavelryUser? = null,
    @SerialName("vote_totals") val voteTotals: Map<String, Int> = emptyMap(),
    @SerialName("user_votes") val userVotes: List<String> = emptyList(),
)

/** Whether the current user has cast [type] on this post. */
fun Post.hasVoted(type: VoteType): Boolean = userVotes.contains(type.wireValue)

/** Total number of [type] votes on this post. */
fun Post.voteCount(type: VoteType): Int = voteTotals[type.wireValue] ?: 0
