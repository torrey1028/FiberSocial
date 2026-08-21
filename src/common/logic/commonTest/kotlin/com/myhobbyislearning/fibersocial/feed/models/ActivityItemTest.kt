package com.myhobbyislearning.fibersocial.feed.models

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Cases are taken from real captured Activity pages rather than invented, because both
 * username-parsing traps only show up on real data (see [parseActorUsername]).
 */
class ActivityItemTest {

    // --- ActivityType ---------------------------------------------------------------

    @Test
    fun `every type has a distinct query key and icon modifier`() {
        val queryKeys = ActivityType.entries.map { it.queryKey }
        val iconModifiers = ActivityType.entries.map { it.iconModifier }
        assertEquals(queryKeys.size, queryKeys.toSet().size, "duplicate query key")
        assertEquals(iconModifiers.size, iconModifiers.toSet().size, "duplicate icon modifier")
        assertEquals(8, ActivityType.entries.size)
    }

    @Test
    fun `query keys are the type_1 through type_8 set Ravelry expects`() {
        assertEquals(
            (1..8).map { "type_$it" }.toSet(),
            ActivityType.allQueryKeys.toSet(),
        )
    }

    @Test
    fun `icon modifiers observed on real items resolve to their type`() {
        // These four were seen on actual items in captured pages.
        assertEquals(ActivityType.ProjectPhoto, ActivityType.fromIconModifier("projects"))
        assertEquals(ActivityType.StashPhoto, ActivityType.fromIconModifier("stash"))
        assertEquals(ActivityType.QueuedPattern, ActivityType.fromIconModifier("queue"))
        assertEquals(ActivityType.Favorite, ActivityType.fromIconModifier("favorites"))
    }

    @Test
    fun `icon modifiers inferred from the filter menu resolve to their type`() {
        // Unconfirmed against a real item — see fromIconModifier's provenance note.
        assertEquals(ActivityType.ForumPostLinking, ActivityType.fromIconModifier("magic-link"))
        assertEquals(ActivityType.Comment, ActivityType.fromIconModifier("comment"))
        assertEquals(ActivityType.HandspunPhoto, ActivityType.fromIconModifier("handspun"))
        assertEquals(ActivityType.FiberPhoto, ActivityType.fromIconModifier("fiber"))
    }

    @Test
    fun `an unrecognised icon modifier resolves to null rather than throwing`() {
        // A ninth activity type Ravelry adds later must not blank the feed.
        assertNull(ActivityType.fromIconModifier("something-new"))
        assertNull(ActivityType.fromIconModifier(""))
    }

    @Test
    fun `every type is reachable from its own icon modifier`() {
        ActivityType.entries.forEach { type ->
            assertEquals(type, ActivityType.fromIconModifier(type.iconModifier))
        }
    }

    // --- parseActorUsername ---------------------------------------------------------

    @Test
    fun `project photo takes the username from the url path`() {
        assertEquals(
            "wildahose",
            parseActorUsername(
                title = "wildahose's Turtle Dove V-neck",
                targetUrl = "https://www.ravelry.com/projects/wildahose/turtle-dove-v-neck",
            ),
        )
    }

    @Test
    fun `stash photo takes the username from the people path`() {
        assertEquals(
            "Alecchi",
            parseActorUsername(
                title = "Alecchi stashed Somsomknit Star Cluster",
                targetUrl = "https://www.ravelry.com/people/Alecchi/stash/star-cluster-3",
            ),
        )
    }

    @Test
    fun `favorite falls back to the title because the pattern url has no username`() {
        // 37 of 40 items in one captured page were this shape.
        assertEquals(
            "FlowerPower111",
            parseActorUsername(
                title = "FlowerPower111 favorited Rosi by Christina Körber-Reith",
                targetUrl = "https://www.ravelry.com/patterns/library/rosi-5",
            ),
        )
    }

    @Test
    fun `queued pattern falls back to the title too`() {
        assertEquals(
            "oldfashionlady",
            parseActorUsername(
                title = "oldfashionlady queued RIVIERA Bag by Susanne Müller",
                targetUrl = "https://www.ravelry.com/patterns/library/riviera-bag",
            ),
        )
    }

    @Test
    fun `a username ending in s survives the possessive apostrophe`() {
        // The trap: splitting on "'s" would yield "WhiskeyKin" / "cosmicjammie".
        assertEquals(
            "WhiskeyKins",
            parseActorUsername(
                title = "WhiskeyKins' Night Rainbow Sock",
                targetUrl = "https://www.ravelry.com/patterns/library/night-rainbow-sock",
            ),
        )
        assertEquals(
            "cosmicjammies",
            parseActorUsername(
                title = "cosmicjammies' Simple Striped Beanie",
                targetUrl = "https://www.ravelry.com/patterns/library/simple-striped-beanie",
            ),
        )
    }

    @Test
    fun `an ordinary possessive is stripped when falling back to the title`() {
        assertEquals(
            "SpaceNeedler",
            parseActorUsername(
                title = "SpaceNeedler's Leftover Ruffle Shorties",
                targetUrl = "https://www.ravelry.com/patterns/library/shorty-sock-set",
            ),
        )
    }

    @Test
    fun `the url wins over the title when both carry a username`() {
        // The URL is unambiguous, so a title that disagrees must not override it.
        assertEquals(
            "realuser",
            parseActorUsername(
                title = "someoneelse's Project",
                targetUrl = "https://www.ravelry.com/projects/realuser/a-project",
            ),
        )
    }

    @Test
    fun `a relative target url still yields the username`() {
        assertEquals(
            "alice",
            parseActorUsername(title = "alice's Socks", targetUrl = "/projects/alice/socks"),
        )
    }

    @Test
    fun `a query string on the target url does not leak into the username`() {
        assertEquals(
            "alice",
            parseActorUsername(
                title = "alice's Socks",
                targetUrl = "https://www.ravelry.com/projects/alice/socks?page=2",
            ),
        )
    }

    @Test
    fun `a url with no username slot and an empty title yields an empty string`() {
        assertEquals(
            "",
            parseActorUsername(title = "", targetUrl = "https://www.ravelry.com/patterns/library/x"),
        )
    }

    @Test
    fun `a truncated path does not throw`() {
        assertEquals(
            "solo",
            parseActorUsername(title = "solo", targetUrl = "https://www.ravelry.com/projects"),
        )
    }

    // --- ActivityItem ---------------------------------------------------------------

    @Test
    fun `an item carries the id as its sort key and the relative time verbatim`() {
        val item = ActivityItem(
            id = 807483746L,
            type = ActivityType.StashPhoto,
            relativeTime = "less than a minute ago",
            actorUsername = "Alecchi",
            title = "Alecchi stashed Somsomknit Star Cluster",
            thumbnailUrl = null,
            targetUrl = "https://www.ravelry.com/people/Alecchi/stash/star-cluster-3",
        )
        assertEquals(807483746L, item.id)
        // Stored exactly as Ravelry worded it — not reformatted, not parsed to an instant.
        assertEquals("less than a minute ago", item.relativeTime)
        assertNull(item.thumbnailUrl)
    }

    @Test
    fun `items sort newest first by id`() {
        // Ordering is by id because the markup carries no absolute timestamp, and the
        // relative text is day-granular so many items would compare equal.
        fun item(id: Long, relative: String) = ActivityItem(
            id = id,
            type = ActivityType.ProjectPhoto,
            relativeTime = relative,
            actorUsername = "u",
            title = "t",
            thumbnailUrl = null,
            targetUrl = "https://www.ravelry.com/projects/u/t",
        )
        val sixDaysAgo = listOf(
            item(805373795L, "7 days ago"),
            item(806573269L, "about 9 hours ago"),
            item(805389419L, "6 days ago"),
            item(805389388L, "6 days ago"),
        ).sortedByDescending { it.id }

        assertEquals(
            listOf(806573269L, 805389419L, 805389388L, 805373795L),
            sixDaysAgo.map { it.id },
        )
        // The two "6 days ago" items would tie on the rendered text but order exactly by id.
        assertNotNull(sixDaysAgo.first())
    }
}
