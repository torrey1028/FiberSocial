package com.autom8ed.fibersocial.activity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GroupActivityParserGoldenTest {
    private val page = GroupActivityParser.parse(GROUP_ACTIVITY_HTML)

    @Test
    fun `parses all entries from the container in page order`() {
        assertEquals(40, page.items.size)
        assertEquals(800219509L, page.items.first().id)
        assertEquals(798420867L, page.items.last().id)
    }

    @Test
    fun `project-photo entry carries all fields`() {
        val first = page.items.first()
        assertEquals(GroupActivityType.PROJECT_PHOTO, first.type)
        assertEquals("projects", first.typeKey)
        assertEquals("wildahose's Honeycomb Aran", first.title)
        assertEquals("https://www.ravelry.com/projects/wildahose/honeycomb-aran-3", first.targetUrl)
        assertEquals(
            "https://images4-f.ravelrycache.com/uploads/wildahose/1152534907/image-0_small.jpg",
            first.thumbnailUrl,
        )
        assertEquals("about 18 hours ago", first.ageText)
        assertEquals("wildahose", first.projectUsername)
        assertEquals("honeycomb-aran-3", first.projectPermalink)
    }

    @Test
    fun `magic-link entry targets the forum post and has no project ref`() {
        val magicLink = page.items.single { it.type == GroupActivityType.MAGIC_LINK }
        assertEquals("magic_link", magicLink.typeKey)
        assertEquals(798786707L, magicLink.id)
        assertEquals(
            "https://www.ravelry.com/discuss/chronic-bitches/4407977/551-575#564",
            magicLink.targetUrl,
        )
        assertEquals(
            "briarknittles magic linked Flower and Garden Shawl by Parry Otter",
            magicLink.title,
        )
        assertNull(magicLink.projectUsername)
        assertNull(magicLink.projectPermalink)
    }

    @Test
    fun `all other entries are project photos with a project ref`() {
        val photos = page.items.filter { it.type == GroupActivityType.PROJECT_PHOTO }
        assertEquals(39, photos.size)
        assertTrue(photos.all { it.projectUsername != null && it.projectPermalink != null })
    }

    @Test
    fun `pagination parses current and total pages`() {
        assertEquals(1, page.currentPage)
        assertEquals(3, page.totalPages)
    }

    @Test
    fun `entries outside the recent_activity container are ignored`() {
        assertTrue(page.items.none { it.id == 1L })
    }
}

class GroupActivityParserLenienceTest {
    private fun page(entry: String) =
        GroupActivityParser.parse("""<div id="recent_activity">$entry</div>""")

    private val detailsLink =
        """<a href="https://www.ravelry.com/projects/someone/some-hat" id="activity_42_link">someone's Some Hat</a>"""

    @Test
    fun `entry without a details link is skipped`() {
        val html = """<div class="project"><span class="touched">now</span></div>"""
        assertTrue(page(html).items.isEmpty())
    }

    @Test
    fun `minimal entry degrades to nulls and empties`() {
        val item = page("""<div class="project"><div class="details"><a href="/x">bare</a></div></div>""")
            .items.single()
        assertNull(item.id)
        assertEquals(GroupActivityType.UNKNOWN, item.type)
        assertEquals("", item.typeKey)
        assertNull(item.thumbnailUrl)
        assertEquals("", item.ageText)
        assertNull(item.projectUsername)
    }

    @Test
    fun `unmodeled icon key parses as UNKNOWN but is preserved`() {
        val html = """
            <div class="project">
            <img class="icon activity_icon icon_16 o-icon--handspun o-icon o-icon--xs">
            <div class="details">$detailsLink</div>
            </div>
        """
        val item = page(html).items.single()
        assertEquals(GroupActivityType.UNKNOWN, item.type)
        assertEquals("handspun", item.typeKey)
    }

    @Test
    fun `id falls back to the details link when there is no photo`() {
        val item = page("""<div class="project"><div class="details">$detailsLink</div></div>""")
            .items.single()
        assertEquals(42L, item.id)
        assertEquals("someone", item.projectUsername)
        assertEquals("some-hat", item.projectPermalink)
    }

    @Test
    fun `placeholder data URI backgrounds yield no thumbnail`() {
        val html = """
            <div class="project">
            <a class="photo" href="/x" id="activity_7" style="background-image: url(data:image/svg+xml;base64,AAAA);"></a>
            <div class="details">$detailsLink</div>
            </div>
        """
        assertNull(page(html).items.single().thumbnailUrl)
    }

    @Test
    fun `icon with only size-modifier classes yields UNKNOWN and an empty key`() {
        val html = """
            <div class="project">
            <img class="icon activity_icon o-icon o-icon--xs">
            <div class="details">$detailsLink</div>
            </div>
        """
        val item = page(html).items.single()
        assertEquals(GroupActivityType.UNKNOWN, item.type)
        assertEquals("", item.typeKey)
    }

    @Test
    fun `id falls back to the details link when the photo id is not an activity id`() {
        val html = """
            <div class="project">
            <a class="photo" href="/x" id="photo_149013597"></a>
            <div class="details">$detailsLink</div>
            </div>
        """
        assertEquals(42L, page(html).items.single().id)
    }

    @Test
    fun `page without pagination is page 1 of 1`() {
        val result = page("""<div class="project"><div class="details">$detailsLink</div></div>""")
        assertEquals(1, result.currentPage)
        assertEquals(1, result.totalPages)
    }

    @Test
    fun `empty or unrelated html yields an empty page`() {
        assertTrue(GroupActivityParser.parse("").items.isEmpty())
        assertTrue(GroupActivityParser.parse("<html><body><p>quiet group</p></body></html>").items.isEmpty())
    }

    private fun paginated(current: String, lastPage: String? = null): GroupActivityPage {
        val last = lastPage?.let { """<span class="pagination__last_page">$it</span>""" }.orEmpty()
        return GroupActivityParser.parse(
            """<div class="pagination"><span class="page_bar__current">$current</span>$last</div>""",
        )
    }

    @Test
    fun `pagination without a last-page span totals at the current page`() {
        val page = paginated("4")
        assertEquals(4, page.currentPage)
        assertEquals(4, page.totalPages)
    }

    @Test
    fun `unparseable current page falls back to page 1`() {
        val page = paginated("first", "of 3")
        assertEquals(1, page.currentPage)
        assertEquals(3, page.totalPages)
    }

    @Test
    fun `last-page text without a number totals at the current page`() {
        val page = paginated("2", "of many")
        assertEquals(2, page.currentPage)
        assertEquals(2, page.totalPages)
    }

    @Test
    fun `last-page number too large for an Int totals at the current page`() {
        val page = paginated("2", "of 99999999999")
        assertEquals(2, page.totalPages)
    }

    @Test
    fun `total pages never reports below the current page`() {
        val page = paginated("5", "of 3")
        assertEquals(5, page.currentPage)
        assertEquals(5, page.totalPages)
    }
}

class GroupActivityParserHelpersTest {
    @Test
    fun `activity ids parse from photo and link element ids`() {
        assertEquals(800219509L, GroupActivityParser.activityIdFrom("activity_800219509"))
        assertEquals(800219509L, GroupActivityParser.activityIdFrom("activity_800219509_link"))
        assertNull(GroupActivityParser.activityIdFrom("photo_149013597"))
        assertNull(GroupActivityParser.activityIdFrom("activity_"))
        assertNull(GroupActivityParser.activityIdFrom("activity_99999999999999999999999"))
        assertNull(GroupActivityParser.activityIdFrom(null))
    }

    @Test
    fun `thumbnails parse from quoted and unquoted url syntax`() {
        assertEquals(
            "https://example.com/a.jpg",
            GroupActivityParser.thumbnailFromStyle("background-image: url('https://example.com/a.jpg'); background-position: -35px -55px;"),
        )
        assertEquals(
            "https://example.com/a.jpg",
            GroupActivityParser.thumbnailFromStyle("""background-image: url("https://example.com/a.jpg")"""),
        )
        assertEquals(
            "https://example.com/a.jpg",
            GroupActivityParser.thumbnailFromStyle("background-image: url(https://example.com/a.jpg)"),
        )
        assertNull(GroupActivityParser.thumbnailFromStyle("position: relative;"))
        assertNull(GroupActivityParser.thumbnailFromStyle(""))
    }

    @Test
    fun `project refs parse only from two-segment project paths`() {
        assertEquals(
            "wildahose" to "honeycomb-aran-3",
            GroupActivityParser.projectRefFromHref("https://www.ravelry.com/projects/wildahose/honeycomb-aran-3"),
        )
        assertEquals(
            null to null,
            GroupActivityParser.projectRefFromHref("https://www.ravelry.com/projects/search#group=Kirkland+Fiber+Arts+Circle"),
        )
        assertEquals(
            null to null,
            GroupActivityParser.projectRefFromHref("https://www.ravelry.com/discuss/chronic-bitches/4407977/551-575#564"),
        )
        assertEquals(null to null, GroupActivityParser.projectRefFromHref(""))
    }

    @Test
    fun `project permalinks strip queries and fragments`() {
        assertEquals(
            "user" to "hat",
            GroupActivityParser.projectRefFromHref("https://www.ravelry.com/projects/user/hat?view=full#photos"),
        )
    }
}
