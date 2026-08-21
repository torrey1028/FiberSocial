package com.myhobbyislearning.fibersocial.feed.models

/**
 * One row in a group's Activity tab (epic #483) — a member doing something on Ravelry:
 * adding a project photo, stashing yarn, favoriting a pattern, and so on.
 *
 * Scraped from `www.ravelry.com/groups/browse/activity/{permalink}`, because Ravelry's
 * JSON API has no group-activity endpoint (its `groups` namespace is `groups/search.json`
 * alone, and `/people/{username}/friends/activity.json` is scoped to friends rather than
 * group members).
 *
 * There is deliberately **no group field**: Activity is a per-group tab, so every item in
 * a list belongs to the group being viewed and per-item attribution would be dead weight.
 *
 * @property id Ravelry's own activity id, from the item's `id="activity_<id>"`. **Also the
 *   sort key** — see [relativeTime] for why there is nothing better. Globally unique across
 *   Ravelry, which additionally makes de-duplication exact when the moving 30-day window
 *   repeats an item across pages.
 * @property type What the member did. An open set — see [ActivityType].
 * @property relativeTime Ravelry's own wording for when this happened, e.g.
 *   `"about 12 hours ago"` or `"6 days ago"`, stored and displayed **verbatim**.
 *
 *   The page carries no absolute timestamp anywhere — no `datetime` attribute, no `title`
 *   tooltip — and the text is day-granular once past a day old, so dozens of items can
 *   share one string. Do **not** parse this into an `Instant` to sort on: that fabricates
 *   precision and makes distinct events compare equal, which is exactly the ambiguity
 *   sorting on [id] avoids. Render it as-is rather than through
 *   [com.myhobbyislearning.fibersocial.feed.RelativeTime], which formats *from* an instant
 *   this feature doesn't have.
 * @property actorUsername The member who did it. Parsed with [parseActorUsername] — read
 *   its docs before touching, the obvious approaches are both wrong.
 * @property title Ravelry's own phrasing of the event, e.g. `"wildahose's Turtle Dove
 *   V-neck"` or `"FlowerPower111 favorited Rosi by Christina Körber-Reith"`. Already
 *   carries the actor and the verb, so render it rather than rebuilding the sentence.
 * @property thumbnailUrl Item image, or null when the item has none or only a placeholder.
 * @property targetUrl Absolute `www.ravelry.com` URL of the thing acted on. The path shape
 *   varies by type — `/projects/{user}/{permalink}`, `/people/{user}/stash/{permalink}`,
 *   `/patterns/library/{permalink}` — which is what tap-through routes on.
 */
data class ActivityItem(
    val id: Long,
    val type: ActivityType,
    val relativeTime: String,
    val actorUsername: String,
    val title: String,
    val thumbnailUrl: String?,
    val targetUrl: String,
)

/**
 * The kind of activity, as Ravelry's own Activity-tab filter menu enumerates it.
 *
 * A closed enum of the eight types Ravelry offers, with unrecognised ones handled at the
 * parse boundary instead of inside the model: [fromIconModifier] returns null, and the
 * parser drops that item and logs the modifier it didn't recognise. Ravelry can add a ninth
 * type whenever it likes, and one unrecognised item must never blank a member's whole feed.
 *
 * Logging at the parse boundary rather than carrying an `Unknown` case through the model is
 * deliberate: the unrecognised icon string is in hand exactly there, and a log line naming
 * it is what turns "some rows are silently missing" into a diagnosable report.
 *
 * @property queryKey The `type_N` query parameter that asks for this kind. The app always
 *   requests all eight — per-type filtering isn't a product requirement, and an omitted
 *   type is content silently missing.
 * @property iconModifier The `o-icon--*` modifier on an item's `img.activity_icon`, which
 *   is the **only** discriminator in the markup: every item renders as `div.project`
 *   regardless of what it actually is, so the container class must not be used to tell
 *   them apart.
 */
enum class ActivityType(val queryKey: String, val iconModifier: String) {
    ProjectPhoto("type_1", "projects"),
    StashPhoto("type_2", "stash"),
    QueuedPattern("type_3", "queue"),
    Favorite("type_4", "favorites"),
    ForumPostLinking("type_5", "magic-link"),
    Comment("type_6", "comment"),
    HandspunPhoto("type_7", "handspun"),
    FiberPhoto("type_8", "fiber"),
    ;

    companion object {
        /**
         * Resolves an `o-icon--*` modifier to a type, or null when it isn't one we know.
         *
         * A null means the caller should skip that item and log [modifier], so a type
         * Ravelry adds later shows up as a diagnosable line rather than silent absence.
         *
         * Provenance, because half of this table is inference: `projects`, `stash`, `queue`
         * and `favorites` were observed on real items in captured pages. The other four
         * come from the filter menu's own icon names (`data-icon-svg=".../{name}.svg"`),
         * where the four verified types matched their menu icon exactly — so the convention
         * is sound, but those four are unconfirmed against a real item until one appears.
         * A wrong guess here can only fail to match (and skip the row); it cannot produce a
         * wrong type.
         */
        fun fromIconModifier(modifier: String): ActivityType? =
            entries.firstOrNull { it.iconModifier == modifier }

        /** Every `type_N=1` pair, for building an all-types request. */
        val allQueryKeys: List<String> = entries.map { it.queryKey }
    }
}

/**
 * Extracts the acting member's username from an activity item's [title] and [targetUrl].
 *
 * Both obvious approaches are wrong on real data, so this exists as one shared, tested
 * function rather than being open-coded:
 *
 * - **Splitting the title on `'s`** corrupts every username already ending in s. Captured
 *   pages contain `WhiskeyKins' Night Rainbow Sock` and `cosmicjammies' Simple Striped
 *   Beanie` — possessive apostrophe, no trailing s.
 * - **Taking it from the URL path** only works for some types. Favorites and queued
 *   patterns were 37 of 40 items in one captured page and both target
 *   `/patterns/library/{permalink}`, which contains no username at all.
 *
 * So: prefer the URL when its path carries a username (`/projects/{user}/…`,
 * `/people/{user}/…`) because that is unambiguous; otherwise take the title's first
 * whitespace-delimited token and strip a trailing possessive. The two agree where both
 * apply.
 *
 * Deliberately not keyed on the verb (`stashed`, `favorited`, `queued`): that set is open,
 * and the project-photo form has no verb at all.
 *
 * @return The username, or an empty string when neither source yields one.
 */
fun parseActorUsername(title: String, targetUrl: String): String {
    usernameFromPath(targetUrl)?.let { return it }
    val firstToken = title.trimStart().substringBefore(' ')
    return firstToken.removeSuffix("'s").removeSuffix("'")
}

/** `https://www.ravelry.com/projects/alice/socks` -> `alice`; null when the path has no
 *  username slot (e.g. `/patterns/library/rosi-5`). */
private fun usernameFromPath(targetUrl: String): String? {
    val path = targetUrl.substringAfter("ravelry.com", missingDelimiterValue = targetUrl)
    val segments = path.substringBefore('?').split('/').filter { it.isNotEmpty() }
    if (segments.size < 2) return null
    return if (segments[0] in USERNAME_BEARING_PREFIXES) segments[1] else null
}

/** URL prefixes whose next path segment is a username. */
private val USERNAME_BEARING_PREFIXES = setOf("projects", "people")
