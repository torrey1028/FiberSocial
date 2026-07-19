package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.Group
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GroupLastViewedTest {

    private fun group(id: Long) = Group(id = id, name = "G$id", permalink = "g$id", forumId = id * 10)

    @Test
    fun `activity newer than the last view lights the group's forum`() {
        val dots = resolveGroupDots(
            groups = listOf(group(1)),
            activity = mapOf(1L to 200L),
            lastViewed = mapOf(1L to 100L),
            now = 500L,
        )
        assertEquals(setOf(10L), dots.unreadGroupForumIds)
    }

    @Test
    fun `activity older than the last view lights nothing`() {
        val dots = resolveGroupDots(
            groups = listOf(group(1)),
            activity = mapOf(1L to 50L),
            lastViewed = mapOf(1L to 100L),
            now = 500L,
        )
        assertTrue(dots.unreadGroupForumIds.isEmpty())
    }

    @Test
    fun `activity exactly at the last view lights nothing`() {
        // Strictly-newer, not newer-or-equal: re-running the check right after a view must
        // not re-light the dot the view just cleared.
        val dots = resolveGroupDots(
            groups = listOf(group(1)),
            activity = mapOf(1L to 100L),
            lastViewed = mapOf(1L to 100L),
            now = 500L,
        )
        assertTrue(dots.unreadGroupForumIds.isEmpty())
    }

    @Test
    fun `an unseen group seeds now and shows no dot`() {
        // A brand-new install must not light every group at once.
        val dots = resolveGroupDots(
            groups = listOf(group(1), group(2)),
            activity = mapOf(1L to 400L, 2L to 400L),
            lastViewed = emptyMap(),
            now = 500L,
        )
        assertTrue(dots.unreadGroupForumIds.isEmpty())
        assertEquals(mapOf(1L to 500L, 2L to 500L), dots.lastViewed)
    }

    @Test
    fun `a group with unknown activity shows no dot and keeps its timestamp`() {
        // Its fetch failed, or no topic had a parseable timestamp — don't guess.
        val dots = resolveGroupDots(
            groups = listOf(group(1)),
            activity = emptyMap(),
            lastViewed = mapOf(1L to 100L),
            now = 500L,
        )
        assertTrue(dots.unreadGroupForumIds.isEmpty())
        assertEquals(mapOf(1L to 100L), dots.lastViewed)
    }

    @Test
    fun `seeding and dotting apply together across groups`() {
        val dots = resolveGroupDots(
            groups = listOf(group(1), group(2), group(3)),
            activity = mapOf(1L to 200L, 2L to 50L, 3L to 400L),
            // Group 3 is newly joined.
            lastViewed = mapOf(1L to 100L, 2L to 100L),
            now = 500L,
        )
        assertEquals(setOf(10L), dots.unreadGroupForumIds)
        assertEquals(mapOf(1L to 100L, 2L to 100L, 3L to 500L), dots.lastViewed)
    }

    @Test
    fun `entries for departed groups are pruned`() {
        val dots = resolveGroupDots(
            groups = listOf(group(1)),
            activity = emptyMap(),
            lastViewed = mapOf(1L to 100L, 99L to 100L),
            now = 500L,
        )
        assertEquals(mapOf(1L to 100L), dots.lastViewed)
    }

    @Test
    fun `no groups yields no dots and an empty map`() {
        val dots = resolveGroupDots(emptyList(), mapOf(1L to 400L), mapOf(1L to 100L), now = 500L)
        assertEquals(GroupActivityDots(), dots)
    }
}
