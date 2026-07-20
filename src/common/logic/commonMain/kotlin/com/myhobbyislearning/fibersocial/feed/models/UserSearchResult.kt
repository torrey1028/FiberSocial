package com.myhobbyislearning.fibersocial.feed.models

/**
 * One `User` hit from Ravelry's global search (`/search.json?types=User`), used to pick a
 * message recipient (issue #367, epic #365).
 *
 * THERE IS NO `/people/search.json`. The generic global-search endpoint is the only
 * user-search Ravelry exposes, which is why this is a hand-mapped projection of a generic
 * search result rather than a [RavelryUser].
 *
 * NOT a `@Serializable` wire type on purpose: the JSON shape is a generic search result
 * whose identity fields live in a nested `record` object, so it is decoded by private DTOs
 * inside [com.myhobbyislearning.fibersocial.feed.RavelryApiClient] and flattened into this.
 *
 * @property id Ravelry's numeric user ID from `record.id`, or `null` if the result carried
 *   no record. RETAINED DELIBERATELY even though nothing sends it yet — see the note below.
 * @property username Ravelry handle, from `record.permalink`. This is the value
 *   [com.myhobbyislearning.fibersocial.feed.RavelryApiClient.sendMessage] puts in
 *   `recipient_username`, and the same value that addresses `/people/{username}` paths.
 * @property displayName The result's `title` — what to show in the picker. Usually equal to
 *   [username], but it is a display string and must not be sent as an identifier.
 * @property avatarUrl Small avatar for the picker row, preferring the search result's
 *   `tiny_image_url` (~24x24, sized for inline text) and falling back to the much larger
 *   `image_url` (~500px) only when the tiny one is absent.
 */
data class UserSearchResult(
    val id: Long?,
    val username: String,
    val displayName: String,
    val avatarUrl: String?,
)

// WHY [UserSearchResult.id] EXISTS WHEN NOTHING SENDS IT (a decision, not an oversight):
//
// `messages/create.json` accepts EITHER `recipient_user_id` OR `recipient_username`, and we
// send the username — it is what the composer already has, it is human-checkable in a bug
// report, and it keeps the call symmetrical with every other username-addressed path in
// this client. So the id is unused today.
//
// It is kept anyway because it is the only STABLE identifier in the response. Ravelry
// usernames are user-changeable, so a username captured at picker time is a value that can
// go stale; the id cannot. The moment we persist a chosen recipient across time — a draft,
// a "recently messaged" list, a per-thread mute key (#377) — the username becomes the wrong
// thing to store and the id becomes the right one. Recovering it later would mean a second
// search round-trip, and only if the stale username still resolves.
//
// The cost of carrying it is one nullable Long that the JSON already contains; the cost of
// dropping it is unrecoverable at the point we discover we want it. Nullable rather than
// required because `record` is documented as a nested object we should not assume is
// always present, and a missing id must degrade to "we can still message by username",
// never to a decode failure that empties the whole picker.
