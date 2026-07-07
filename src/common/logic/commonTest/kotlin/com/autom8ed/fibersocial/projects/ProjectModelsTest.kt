package com.autom8ed.fibersocial.projects

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.serialization.json.Json

/**
 * Decodes each project-page model from minimal JSON, exercising every field's default
 * (the absent-field branch of the generated deserialization constructor) — the full-field
 * paths are covered by the API-client tests.
 */
class ProjectModelsTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `ProjectDetail fills defaults for every absent field`() {
        val p = json.decodeFromString<ProjectDetail>("""{"id":7,"name":"Socks"}""")
        assertEquals(7L, p.id)
        assertEquals("Socks", p.name)
        assertEquals("", p.permalink)
        assertNull(p.patternName)
        assertNull(p.patternId)
        assertNull(p.statusName)
        assertNull(p.craftName)
        assertNull(p.progress)
        assertNull(p.started)
        assertNull(p.completed)
        assertNull(p.madeFor)
        assertNull(p.size)
        assertNull(p.notes)
        assertNull(p.notesHtml)
        assertEquals(emptyList(), p.tagNames)
        assertEquals(emptyList(), p.photos)
    }

    @Test
    fun `models built directly run their default expressions`() {
        // Serialization decoding uses a synthetic constructor and skips the primary
        // constructor's default expressions; direct construction exercises them.
        val p = ProjectDetail(id = 7, name = "Socks")
        assertEquals("", p.permalink)
        assertNull(p.patternId)
        assertEquals(emptyList(), p.tagNames)
        assertEquals(emptyList(), p.photos)

        val info = PatternInfo(id = 42)
        assertEquals("", info.name)
        assertNull(info.author)

        val author = PatternAuthor(id = 3)
        assertEquals("", author.name)

        val comment = ProjectComment(id = 1)
        assertEquals("", comment.commentHtml)
        assertNull(comment.user)
    }

    @Test
    fun `PatternInfo defaults and webUrl`() {
        val bare = json.decodeFromString<PatternInfo>("""{"id":42}""")
        assertEquals(42L, bare.id)
        assertEquals("", bare.name)
        assertEquals("", bare.permalink)
        assertNull(bare.author)
        assertEquals(
            "https://www.ravelry.com/patterns/library/vanilla-socks",
            PatternInfo(permalink = "vanilla-socks").webUrl,
        )
    }

    @Test
    fun `PatternAuthor fills defaults`() {
        val a = json.decodeFromString<PatternAuthor>("""{"id":3}""")
        assertEquals(3L, a.id)
        assertEquals("", a.name)
        assertEquals("", a.permalink)
    }

    @Test
    fun `models decode with a partial field set`() {
        // A mix of present and absent optional fields exercises the mixed-bitmask
        // branch of each generated deserialization constructor.
        val p = json.decodeFromString<ProjectDetail>(
            """{"id":7,"name":"Socks","status_name":"Done","tag_names":["a"]}""",
        )
        assertEquals("Done", p.statusName)
        assertEquals(listOf("a"), p.tagNames)
        assertNull(p.craftName)

        val info = json.decodeFromString<PatternInfo>("""{"id":42,"name":"P"}""")
        assertEquals("P", info.name)
        assertEquals("", info.permalink)

        val c = json.decodeFromString<ProjectComment>(
            """{"id":1,"comment_html":"<p>hi</p>"}""",
        )
        assertEquals("<p>hi</p>", c.commentHtml)
        assertNull(c.user)
    }

    @Test
    fun `ProjectComment fills defaults`() {
        val c = json.decodeFromString<ProjectComment>("""{"id":1}""")
        assertEquals(1L, c.id)
        assertEquals("", c.commentHtml)
        assertNull(c.createdAt)
        assertNull(c.user)
    }
}
