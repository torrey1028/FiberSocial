package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.projects.ProjectLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RouteLinkTapTest {

    @Test
    fun `a project link routes to the in-app opener, not the browser`() {
        var project: ProjectLink? = null
        var browser: String? = null
        routeLinkTap(
            "https://www.ravelry.com/projects/yarnie/autumn-socks",
            openProject = { project = it },
            openBrowser = { browser = it },
        )
        assertEquals(ProjectLink("yarnie", "autumn-socks"), project)
        assertNull(browser)
    }

    @Test
    fun `a non-project link routes to the browser`() {
        var project: ProjectLink? = null
        var browser: String? = null
        routeLinkTap(
            "https://www.ravelry.com/patterns/library/vanilla-socks",
            openProject = { project = it },
            openBrowser = { browser = it },
        )
        assertNull(project)
        assertEquals("https://www.ravelry.com/patterns/library/vanilla-socks", browser)
    }

    @Test
    fun `a project link falls back to the browser when no opener is available`() {
        var browser: String? = null
        routeLinkTap(
            "https://www.ravelry.com/projects/yarnie/autumn-socks",
            openProject = null,
            openBrowser = { browser = it },
        )
        assertEquals("https://www.ravelry.com/projects/yarnie/autumn-socks", browser)
    }
}
