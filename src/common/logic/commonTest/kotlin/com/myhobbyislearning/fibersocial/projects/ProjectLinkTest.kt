package com.myhobbyislearning.fibersocial.projects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProjectLinkTest {

    @Test
    fun `parses the canonical project url`() {
        assertEquals(
            ProjectLink("yarnie", "autumn-socks"),
            parseProjectLink("https://www.ravelry.com/projects/yarnie/autumn-socks"),
        )
    }

    @Test
    fun `tolerates scheme host-case slash query and fragment variants`() {
        val expected = ProjectLink("yarnie", "autumn-socks")
        assertEquals(expected, parseProjectLink("http://ravelry.com/projects/yarnie/autumn-socks"))
        assertEquals(expected, parseProjectLink("https://ravelry.com/projects/yarnie/autumn-socks/"))
        assertEquals(expected, parseProjectLink("https://www.ravelry.com/projects/yarnie/autumn-socks?iid=1"))
        assertEquals(expected, parseProjectLink("https://www.ravelry.com/projects/yarnie/autumn-socks#photos"))
        assertEquals(expected, parseProjectLink("  https://www.ravelry.com/projects/yarnie/autumn-socks  "))
    }

    @Test
    fun `rejects non-project urls`() {
        // Profile project listing — no specific project to open.
        assertNull(parseProjectLink("https://www.ravelry.com/projects/yarnie"))
        // Subpages below a project aren't the project page.
        assertNull(parseProjectLink("https://www.ravelry.com/projects/yarnie/autumn-socks/people"))
        assertNull(parseProjectLink("https://www.ravelry.com/patterns/library/some-pattern"))
        assertNull(parseProjectLink("https://example.com/projects/yarnie/autumn-socks"))
        assertNull(parseProjectLink("not a url"))
    }

    @Test
    fun `webUrl reconstructs the canonical form`() {
        assertEquals(
            "https://www.ravelry.com/projects/yarnie/autumn-socks",
            ProjectLink("yarnie", "autumn-socks").webUrl,
        )
    }
}
