package com.myhobbyislearning.fibersocial.events

/** Lowercase English month names as Ravelry renders them; index + 1 = month number. */
internal val RAVELRY_MONTHS = listOf(
    "january", "february", "march", "april", "may", "june",
    "july", "august", "september", "october", "november", "december",
)

/** Month number (1–12) for a Ravelry month name, or 0 when unrecognized. */
internal fun monthNumber(name: String): Int = RAVELRY_MONTHS.indexOf(name.lowercase()) + 1

/**
 * Event permalink from an event-link href, or null when [href] isn't an event link.
 * Strips any trailing path segment, query string, or fragment so permalinks stay
 * joinable across the parsers that produce them and the APIs that consume them.
 */
internal fun eventPermalinkFromHref(href: String): String? =
    href.substringAfter("/events/", missingDelimiterValue = "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .ifEmpty { null }
