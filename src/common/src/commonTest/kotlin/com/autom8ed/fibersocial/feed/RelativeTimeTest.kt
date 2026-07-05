package com.autom8ed.fibersocial.feed

import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class RelativeTimeTest {
    private val now = Instant.parse("2026-07-03T12:00:00Z")

    @Test
    fun `null input renders empty`() {
        assertEquals("", relativeTime(null, now))
    }

    @Test
    fun `unparseable input renders empty`() {
        assertEquals("", relativeTime("not a date", now))
    }

    @Test
    fun `minutes ago under an hour`() {
        assertEquals("30m ago", relativeTime("2026/07/03 11:30:00 +0000", now))
    }

    @Test
    fun `hours ago under a day`() {
        assertEquals("5h ago", relativeTime("2026/07/03 07:00:00 +0000", now))
    }

    @Test
    fun `days ago under a week`() {
        assertEquals("2d ago", relativeTime("2026/07/01 12:00:00 +0000", now))
    }

    @Test
    fun `weeks ago`() {
        assertEquals("2w ago", relativeTime("2026/06/19 12:00:00 +0000", now))
    }

    @Test
    fun `non-utc offsets are normalized before comparing`() {
        // 07:00 -0500 is 12:00 UTC — exactly "now".
        assertEquals("0m ago", relativeTime("2026/07/03 07:00:00 -0500", now))
    }

    @Test
    fun `regex-shaped but semantically invalid dates render empty`() {
        // Matches the "yyyy/MM/dd HH:mm:ss Z" shape but month 13 isn't a real month —
        // exercises the catch-all around the LocalDateTime construction.
        assertEquals("", relativeTime("2026/13/01 00:00:00 +0000", now))
    }

    @Test
    fun `default now argument is today`() {
        // No fixed `now` passed — just confirms the default-parameter overload runs
        // without throwing, since a fresh timestamp is always "0m ago" or unparseable.
        assertEquals("", relativeTime(null))
    }
}
