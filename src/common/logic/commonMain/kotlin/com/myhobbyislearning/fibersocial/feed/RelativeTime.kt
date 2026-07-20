package com.myhobbyislearning.fibersocial.feed

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

private val RAVELRY_DATE_REGEX =
    Regex("""^(\d{4})/(\d{2})/(\d{2}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})$""")

/**
 * Parses a Ravelry API timestamp (`"yyyy/MM/dd HH:mm:ss Z"`, e.g.
 * `"2026/07/03 10:00:00 +0000"`) into an [Instant], or `null` when it is absent or not in
 * that shape.
 *
 * Note the format is Ravelry's own, NOT ISO-8601, so these strings must never be compared
 * lexicographically or handed to an ISO parser — go through here instead. Shared by
 * [relativeTime] (card timestamps) and the drawer's per-group activity check
 * ([FeedRepository.getDrawerUnread]).
 */
fun parseRavelryTimestamp(dateString: String?): Instant? {
    if (dateString == null) return null
    val match = RAVELRY_DATE_REGEX.matchEntire(dateString) ?: return null
    return try {
        val g = match.groupValues
        val local = LocalDateTime(
            year = g[1].toInt(),
            monthNumber = g[2].toInt(),
            dayOfMonth = g[3].toInt(),
            hour = g[4].toInt(),
            minute = g[5].toInt(),
            second = g[6].toInt(),
        )
        val sign = if (g[7] == "-") -1 else 1
        val offsetSeconds = sign * (g[8].toInt() * 3600 + g[9].toInt() * 60)
        local.toInstant(UtcOffset(seconds = offsetSeconds))
    } catch (_: Exception) {
        null
    }
}

/**
 * Renders how long ago a Ravelry API timestamp (`"yyyy/MM/dd HH:mm:ss Z"`, e.g.
 * `"2026/07/03 10:00:00 +0000"`) was, relative to [now]. Empty string when the timestamp
 * is absent or unparseable.
 */
fun relativeTime(dateString: String?, now: Instant = Clock.System.now()): String {
    val then = parseRavelryTimestamp(dateString) ?: return ""
    return relativeTimeSince(then.toEpochMilliseconds(), now)
}

/**
 * Same wording as [relativeTime], for callers that already hold epoch millis rather than a
 * Ravelry timestamp string.
 *
 * Exists for [com.myhobbyislearning.fibersocial.messages.MessageThread.lastActivityAt],
 * which is the newest PARSEABLE timestamp across a whole conversation — a thread's newest
 * message may itself have an unparseable `sent_at`, so re-deriving the string to hand to
 * [relativeTime] would silently render the wrong message's age (or nothing at all).
 * [relativeTime] delegates here so both surfaces can never drift apart in their bucketing.
 */
fun relativeTimeSince(epochMillis: Long, now: Instant = Clock.System.now()): String {
    val minutes = (now.toEpochMilliseconds() - epochMillis) / 60_000
    return when {
        minutes < 60 -> "${minutes}m ago"
        minutes < 60 * 24 -> "${minutes / 60}h ago"
        minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
        else -> "${minutes / (60 * 24 * 7)}w ago"
    }
}
