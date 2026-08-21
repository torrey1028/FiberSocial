package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.ActivityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupActivityParserTest {

    // --- the real page ---------------------------------------------------------------

    @Test
    fun `parses every item on a real activity page`() {
        val page = GroupActivityParser.parse(GROUP_ACTIVITY_PAGE_HTML)
        assertEquals(8, page.items.size)
    }

    @Test
    fun `orders items newest first by activity id`() {
        val page = GroupActivityParser.parse(GROUP_ACTIVITY_PAGE_HTML)
        val ids = page.items.map { it.id }
        assertEquals(ids.sortedDescending(), ids)
    }

    @Test
    fun `reads the activity type from the icon modifier and not the container class`() {
        val page = GroupActivityParser.parse(GROUP_ACTIVITY_PAGE_HTML)
        // Every one of these is a div.project in the markup, yet they are four different
        // types — which is exactly why the container class must not be the discriminator.
        assertTrue(GROUP_ACTIVITY_PAGE_HTML.contains("""<div class="project""""))
        assertEquals(
            setOf(
                ActivityType.ProjectPhoto,
                ActivityType.StashPhoto,
                ActivityType.QueuedPattern,
                ActivityType.Favorite,
            ),
            page.items.map { it.type }.toSet(),
        )
    }

    @Test
    fun `parses a stash item completely`() {
        val item = GroupActivityParser.parse(GROUP_ACTIVITY_PAGE_HTML)
            .items.single { it.id == 807483746L }

        assertEquals(ActivityType.StashPhoto, item.type)
        assertEquals("Alecchi", item.actorUsername)
        assertEquals("Alecchi stashed Somsomknit Star Cluster", item.title)
        assertEquals("https://www.ravelry.com/people/Alecchi/stash/star-cluster-3", item.targetUrl)
        assertEquals("less than a minute ago", item.relativeTime)
        assertNotNull(item.thumbnailUrl)
        assertTrue(item.thumbnailUrl!!.startsWith("https://"))
    }

    @Test
    fun `keeps relative time verbatim rather than reformatting it`() {
        val page = GroupActivityParser.parse(GROUP_ACTIVITY_PAGE_HTML)
        // There is no absolute timestamp anywhere in the markup, so whatever Ravelry wrote
        // is all there is. Anything that reformats it is inventing precision.
        assertTrue(page.items.all { it.relativeTime.isNotEmpty() })
        assertTrue(page.items.any { it.relativeTime == "less than a minute ago" })
    }

    @Test
    fun `recovers the actor for pattern targets that carry no username in the url`() {
        val page = GroupActivityParser.parse(GROUP_ACTIVITY_PAGE_HTML)
        val patternTargeted = page.items.filter { "/patterns/library/" in it.targetUrl }
        // Favorites and queued patterns dominate a real page and none of their URLs name
        // the member, so a URL-only parse would leave these blank.
        assertTrue(patternTargeted.isNotEmpty(), "fixture should contain pattern-targeted items")
        assertTrue(patternTargeted.all { it.actorUsername.isNotEmpty() })
    }

    @Test
    fun `never leaves a possessive apostrophe in a username`() {
        val page = GroupActivityParser.parse(GROUP_ACTIVITY_PAGE_HTML)
        assertTrue(page.items.none { it.actorUsername.endsWith("'") })
        assertTrue(page.items.none { it.actorUsername.endsWith("'s") })
    }

    @Test
    fun `reports more pages when the paginator offers a next anchor`() {
        assertTrue(GroupActivityParser.parse(GROUP_ACTIVITY_PAGE_HTML).hasMore)
    }

    // --- the last-page trap ----------------------------------------------------------

    @Test
    fun `reports no more pages when next_page degrades to an empty div`() {
        // THE regression test for this parser. On the last page Ravelry does not remove the
        // next-page control, it swaps the anchor for an empty div carrying a --empty
        // modifier. A `.next_page` class selector matches here too, which would report
        // "more pages" forever and page past the end indefinitely.
        val lastPage = """
            <html><body>
            <div class="page_links">
              <a href="/groups/browse/activity/g?page=2" class="previous_page">← Previous</a>
              <div class="pagination">
                <a href="/groups/browse/activity/g?page=1" class="page_bar__page">1</a>
                <span aria-current="page" class="page_bar__current">3</span>
                <span class="pagination__last_page">of 3</span>
              </div>
              <div class="next_page next_page--empty">&nbsp;</div>
            </div>
            <div id="recent_activity">
              <div class="project" style="position: relative; ">
                <a class="photo" href="https://www.ravelry.com/projects/alice/socks"
                   id="activity_802198241"
                   style="background-image: url('https://images.ravelrycache.com/a_small.jpg');"></a>
                <img class="icon activity_icon icon_16 o-icon--projects o-icon o-icon--xs" src="/projects.svg">
                <div class="details">
                  <a href="https://www.ravelry.com/projects/alice/socks" id="activity_802198241_link">alice's Socks</a>
                  <span class="touched">29 days ago</span>
                </div>
              </div>
            </div>
            </body></html>
        """.trimIndent()

        val page = GroupActivityParser.parse(lastPage)
        assertFalse(page.hasMore, "an empty next_page div must not count as another page")
        assertEquals(1, page.items.size)
    }

    @Test
    fun `reports no more pages when there is no paginator at all`() {
        val singlePage = """
            <html><body><div id="recent_activity"></div></body></html>
        """.trimIndent()
        assertFalse(GroupActivityParser.parse(singlePage).hasMore)
    }

    // --- malformed items --------------------------------------------------------------

    private fun pageOf(vararg itemHtml: String) = """
        <html><body><div id="recent_activity">
        ${itemHtml.joinToString("\n")}
        </div></body></html>
    """.trimIndent()

    /**
     * A well-formed item, with each part individually breakable.
     *
     * [activityId] drives *both* places the id appears — the photo anchor and the title
     * link — because that is how Ravelry renders it. A helper that hardcoded the link id
     * would make the "no id" case silently still parseable, and the test would pass while
     * asserting nothing.
     */
    private fun item(
        activityId: Long? = 900000001L,
        icon: String = "o-icon--projects",
        withLink: Boolean = true,
    ): String {
        val photoId = activityId?.let { """id="activity_$it"""" }.orEmpty()
        val linkId = activityId?.let { """id="activity_${it}_link"""" }.orEmpty()
        val link = if (withLink) {
            """<a href="https://www.ravelry.com/projects/alice/socks" $linkId>alice's Socks</a>"""
        } else {
            ""
        }
        return """
            <div class="project" style="position: relative; ">
              <a class="photo" href="https://www.ravelry.com/projects/alice/socks" $photoId
                 style="background-image: url('https://images.ravelrycache.com/a_small.jpg');"></a>
              <img class="icon activity_icon icon_16 $icon o-icon o-icon--xs" src="/projects.svg">
              <div class="details">$link<span class="touched">1 day ago</span></div>
            </div>
        """.trimIndent()
    }

    @Test
    fun `skips an item with an unknown type icon and keeps the rest of the page`() {
        // Ravelry adding a ninth activity type must cost one row, not the whole feed.
        val page = GroupActivityParser.parse(
            pageOf(item(), item(activityId = 900000002L, icon = "o-icon--brand-new-thing")),
        )
        assertEquals(listOf(900000001L), page.items.map { it.id })
    }

    @Test
    fun `skips an item with no activity id and keeps the rest of the page`() {
        // The id is the sort key, so an item without one is unusable rather than degraded.
        val page = GroupActivityParser.parse(pageOf(item(), item(activityId = null)))
        assertEquals(listOf(900000001L), page.items.map { it.id })
    }

    @Test
    fun `skips an item with no title link and keeps the rest of the page`() {
        val page = GroupActivityParser.parse(
            pageOf(item(), item(activityId = 900000003L, withLink = false)),
        )
        assertEquals(listOf(900000001L), page.items.map { it.id })
    }

    @Test
    fun `skips an item with no type icon at all`() {
        val noIcon = """
            <div class="project" style="position: relative; ">
              <a class="photo" href="https://www.ravelry.com/projects/alice/socks" id="activity_900000004"></a>
              <div class="details">
                <a href="https://www.ravelry.com/projects/alice/socks" id="activity_900000004_link">alice's Socks</a>
                <span class="touched">1 day ago</span>
              </div>
            </div>
        """.trimIndent()
        assertTrue(GroupActivityParser.parse(pageOf(noIcon)).items.isEmpty())
    }

    @Test
    fun `an empty activity list parses as an empty page rather than throwing`() {
        val page = GroupActivityParser.parse(
            """<html><body><div id="recent_activity"></div></body></html>""",
        )
        assertTrue(page.items.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `a page with no activity container at all parses as empty`() {
        val page = GroupActivityParser.parse("<html><body><p>Nothing here</p></body></html>")
        assertTrue(page.items.isEmpty())
    }

    // --- thumbnails --------------------------------------------------------------------

    @Test
    fun `an item with no photo has a null thumbnail`() {
        val noPhoto = """
            <div class="project" style="position: relative; ">
              <img class="icon activity_icon icon_16 o-icon--favorites o-icon o-icon--xs" src="/favorites.svg">
              <div class="details">
                <a href="https://www.ravelry.com/patterns/library/rosi-5" id="activity_900000005_link">bob favorited Rosi</a>
                <span class="touched">2 days ago</span>
              </div>
            </div>
        """.trimIndent()
        val item = GroupActivityParser.parse(pageOf(noPhoto)).items.single()
        assertNull(item.thumbnailUrl)
        // The id is still recoverable from the title link's id when the photo anchor is gone.
        assertEquals(900000005L, item.id)
        assertEquals("bob", item.actorUsername)
    }

    @Test
    fun `a data uri placeholder is not mistaken for a real thumbnail`() {
        // Ravelry paints a base64 colour placeholder behind real photos; showing it as
        // content would put a flat rectangle where an image belongs.
        val placeholderOnly = """
            <div class="project" style="position: relative; ">
              <a class="photo" href="https://www.ravelry.com/projects/alice/socks" id="activity_900000006"
                 style="background-image: url(data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=);"></a>
              <img class="icon activity_icon icon_16 o-icon--projects o-icon o-icon--xs" src="/projects.svg">
              <div class="details">
                <a href="https://www.ravelry.com/projects/alice/socks" id="activity_900000006_link">alice's Socks</a>
                <span class="touched">1 day ago</span>
              </div>
            </div>
        """.trimIndent()
        assertNull(GroupActivityParser.parse(pageOf(placeholderOnly)).items.single().thumbnailUrl)
    }
}
