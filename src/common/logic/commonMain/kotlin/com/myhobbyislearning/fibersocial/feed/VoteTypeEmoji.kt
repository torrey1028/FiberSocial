package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.VoteType

/** Emoji shown on each vote button, keyed by [VoteType]. */
val VOTE_TYPE_EMOJI: Map<VoteType, String> = mapOf(
    VoteType.INTERESTING to "🤔",
    VoteType.EDUCATIONAL to "📚",
    VoteType.FUNNY to "😂",
    VoteType.AGREE to "👍",
    VoteType.DISAGREE to "👎",
    VoteType.LOVE to "❤️",
)
