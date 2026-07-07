package com.autom8ed.fibersocial.events

import com.fleeksoft.ksoup.Ksoup
import com.fleeksoft.ksoup.nodes.Element

/**
 * A person attending an event, from the event's people page.
 *
 * @property username Ravelry username.
 * @property avatarUrl Absolute avatar URL, or null when the user has no avatar
 *   (Ravelry shows a blank-skein placeholder; the app renders its own instead).
 */
data class EventAttendee(
    val username: String,
    val avatarUrl: String?,
)

/**
 * Parses an event's people page (`www.ravelry.com/events/{permalink}/people`) into the
 * attendee list.
 *
 * The scraped markup (see `docs/samples/event_people.html`):
 * ```
 * <div class="event__user_cards">
 *   <div class="user_card">
 *     <div class="avatar …"><a href="/people/{user}"><img src="…" class="avatar__image"></a></div>
 *     <div class="details"><a href="…/people/{user}" class="login">{user}</a> …</div>
 *   </div> …
 * ```
 * Cards without a username link are skipped; an absent cards container yields an empty
 * list (nobody is attending, or the page shape changed — both degrade the same way).
 */
object EventPeopleParser {

    /** Ravelry's no-avatar placeholder images live under this path. */
    private const val PLACEHOLDER_PATH = "/images/assets/illustrations/"

    /** Parses the full HTML of an event people page; attendees in page order. */
    fun parse(peoplePageHtml: String): List<EventAttendee> =
        Ksoup.parse(peoplePageHtml)
            .select("div.event__user_cards div.user_card")
            .mapNotNull { parseCard(it) }
            // The UI keys rows by username; a page shape that repeats a card must
            // degrade (like every other parser edge) rather than crash the screen.
            .distinctBy { it.username }

    private fun parseCard(card: Element): EventAttendee? {
        val username = card.selectFirst("div.details a.login")?.text()?.trim().orEmpty()
        if (username.isEmpty()) return null
        return EventAttendee(
            username = username,
            avatarUrl = avatarUrl(card),
        )
    }

    private fun avatarUrl(card: Element): String? {
        val src = card.selectFirst("div.avatar img")?.attr("src").orEmpty()
        return when {
            src.isEmpty() || src.contains(PLACEHOLDER_PATH) -> null
            // Protocol-relative (//cdn.host/…) must be checked before site-relative:
            // startsWith("/") also matches it and would mangle the URL into
            // https://www.ravelry.com//cdn.host/….
            src.startsWith("//") -> "https:$src"
            src.startsWith("/") -> "https://www.ravelry.com$src"
            else -> src
        }
    }
}
