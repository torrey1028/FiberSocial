package com.autom8ed.fibersocial.feed.models

/**
 * Ravelry's forum post reaction types (the "voting buttons" under each post).
 *
 * @property wireValue The value Ravelry's API uses for this type: as the `type` request
 *   parameter when voting, as keys in [Post.voteTotals], and as entries in [Post.userVotes].
 */
enum class VoteType(val wireValue: String) {
    INTERESTING("interesting"),
    EDUCATIONAL("educational"),
    FUNNY("funny"),
    AGREE("agree"),
    DISAGREE("disagree"),
    LOVE("love"),
}
