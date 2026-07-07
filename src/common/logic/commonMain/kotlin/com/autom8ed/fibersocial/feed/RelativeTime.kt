package com.autom8ed.fibersocial.feed

import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.toInstant

private val RAVELRY_DATE_REGEX =
    Regex("""^(\d{4})/(\d{2})/(\d{2}) (\d{2}):(\d{2}):(\d{2}) ([+-])(\d{2})(\d{2})$""")

/**
 * Renders how long ago a Ravelry API timestamp (`"yyyy/MM/dd HH:mm:ss Z"`, e.g.
 * `"2026/07/03 10:00:00 +0000"`) was, relative to [now].
 */
fun relativeTime(dateString: String?, now: Instant = Clock.System.now()): String {
    if (dateString == null) return ""
    val match = RAVELRY_DATE_REGEX.matchEntire(dateString) ?: return ""
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
        val then = local.toInstant(UtcOffset(seconds = offsetSeconds))
        val minutes = (now - then).inWholeMinutes
        when {
            minutes < 60 -> "${minutes}m ago"
            minutes < 60 * 24 -> "${minutes / 60}h ago"
            minutes < 60 * 24 * 7 -> "${minutes / (60 * 24)}d ago"
            else -> "${minutes / (60 * 24 * 7)}w ago"
        }
    } catch (_: Exception) {
        ""
    }
}
