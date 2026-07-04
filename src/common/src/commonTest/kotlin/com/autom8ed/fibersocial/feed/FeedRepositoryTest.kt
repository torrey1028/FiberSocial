package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.models.FeedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class FeedRepositoryTest {
    private val group = com.autom8ed.fibersocial.feed.models.Group(
        id = 10L, name = "KAL Hub", permalink = "kal-hub", forumId = 42L,
    )

    private fun repoWithRoute(route: (String) -> String) =
        FeedRepository(routingApiClient(route = route))

    private fun singleTopicRepo(
        topicId: Long = 100L,
        imagesCount: Int = 0,
        sticky: Boolean = false,
        repliedAt: String? = "2024-01-15",
        summary: String? = "Body text",
    ) = repoWithRoute { path ->
        when {
            path.contains("/forums/") -> topicsJson(topicId)
            path.contains("/topics/") -> topicDetailJson(topicId, imagesCount, sticky, repliedAt, summary)
            else -> error("Unexpected: $path")
        }
    }

    @Test
    fun `getFeedItems lists a topic with images like any other topic`() = runTest {
        // Issue #77: images in a thread must not change how the topic is listed.
        val items = singleTopicRepo(imagesCount = 3).getFeedItems(listOf(group))
        assertFalse(items.single().sticky)
    }

    @Test
    fun `getFeedItems marks a sticky topic sticky`() = runTest {
        val items = singleTopicRepo(sticky = true).getFeedItems(listOf(group))
        assertTrue(items.single().sticky)
    }

    @Test
    fun `getFeedItems marks a plain topic not sticky`() = runTest {
        val items = singleTopicRepo().getFeedItems(listOf(group))
        assertFalse(items.single().sticky)
    }

    @Test
    fun `getFeedItems sticky topic with images is still sticky`() = runTest {
        // A pinned topic must pin regardless of photos in the thread (issue #77: the old
        // image-first classification hid three of a real group's four stickies).
        val items = singleTopicRepo(imagesCount = 1, sticky = true).getFeedItems(listOf(group))
        assertTrue(items.single().sticky)
    }

    @Test
    fun `getFeedItems populates groupId and groupName from group`() = runTest {
        val items = singleTopicRepo().getFeedItems(listOf(group))
        assertEquals(10L, items.single().groupId)
        assertEquals("KAL Hub", items.single().groupName)
    }

    @Test
    fun `getFeedItems uses unknown author when createdByUser is null`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> """{"topic":{"id":100,"title":"T"}}"""
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.getFeedItems(listOf(group)).single()
        assertEquals("unknown", item.author.username)
    }

    @Test
    fun `getFeedItems truncates bodyPreview to 200 chars but keeps full bodySummary`() = runTest {
        val long = "x".repeat(300)
        val items = singleTopicRepo(summary = long).getFeedItems(listOf(group))
        val item = items.single()
        assertEquals(200, item.bodyPreview.length)
        assertEquals(300, item.bodySummary.length)
    }

    @Test
    fun `getFeedItems strips html tags from bodyPreview`() = runTest {
        // Ravelry's `summary` field is documented as plain text but isn't reliably so in
        // practice — raw markup must be stripped before it reaches the feed card (#104).
        val items = singleTopicRepo(summary = "<b>bold</b> text").getFeedItems(listOf(group))
        val item = items.single()
        assertEquals("bold text", item.bodyPreview)
    }

    @Test
    fun `getFeedItems leaves plain-text bodyPreview unaffected`() = runTest {
        val items = singleTopicRepo(summary = "Plain text summary").getFeedItems(listOf(group))
        val item = items.single()
        assertEquals("Plain text summary", item.bodyPreview)
    }

    @Test
    fun `getFeedItems strips markdown bold from bodyPreview`() = runTest {
        // Captured live from a real group: Ravelry's summary field is a raw Markdown
        // excerpt, not HTML — the earlier htmlPreview()-only fix left "**...**" untouched.
        val items = singleTopicRepo(
            summary = "**Post in this thread if you need your test threads' title changed.** Some detail.",
        ).getFeedItems(listOf(group))
        val item = items.single()
        assertEquals(
            "Post in this thread if you need your test threads' title changed. Some detail.",
            item.bodyPreview,
        )
    }

    @Test
    fun `getFeedItems strips markdown reference-style image syntax from bodyPreview`() = runTest {
        // Captured live: "[image title][1]" with the reference definition further down
        // in the (untruncated) body. Uses \n (not a raw newline) so the naive JSON
        // fixture builder in Fakes.kt doesn't need real multi-line/quote escaping.
        val items = singleTopicRepo(
            summary = "[image title][1] and more text.\\n\\n[1]: https://example.com/photo.jpg",
        ).getFeedItems(listOf(group))
        val item = items.single()
        assertEquals("image title and more text.", item.bodyPreview)
    }

    @Test
    fun `getFeedItems strips markdown inline link and code from bodyPreview`() = runTest {
        val items = singleTopicRepo(
            summary = "See [the pattern](https://example.com/p) and run `gauge swatch` first.",
        ).getFeedItems(listOf(group))
        val item = items.single()
        assertEquals("See the pattern and run gauge swatch first.", item.bodyPreview)
    }

    @Test
    fun `getFeedItems strips an unterminated bold marker left by Ravelry's own server-side truncation`() = runTest {
        // Captured live: Ravelry truncates topic.summary server-side, sometimes before the
        // closing "**" is ever sent — so no regex can pair-match it as real emphasis. It
        // must still be dropped as noise rather than shown raw.
        val items = singleTopicRepo(
            summary = "**Please use this thread to promote YOUR lace patterns. This is a NO CHAT thread!!",
        ).getFeedItems(listOf(group))
        val item = items.single()
        assertEquals(
            "Please use this thread to promote YOUR lace patterns. This is a NO CHAT thread!!",
            item.bodyPreview,
        )
    }

    @Test
    fun `getFeedItems uses empty string for both preview and summary when summary is null`() = runTest {
        val items = singleTopicRepo(summary = null).getFeedItems(listOf(group))
        val item = items.single()
        assertEquals("", item.bodyPreview)
        assertEquals("", item.bodySummary)
    }

    @Test
    fun `getFeedItems populates bodySummary on sticky topics`() = runTest {
        val items = singleTopicRepo(sticky = true, summary = "Pinned info").getFeedItems(listOf(group))
        val item = items.single()
        assertEquals("Pinned info", item.bodySummary)
        assertEquals("Pinned info", item.bodyPreview)
    }

    @Test
    fun `getFeedItems sorts results by lastPostAt descending`() = runTest {
        val group2 = group.copy(id = 11L, forumId = 43L)
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/42/") -> topicsJson(100L)
                path.contains("/forums/43/") -> topicsJson(101L)
                path.contains("/topics/100") -> topicDetailJson(100L, repliedAt = "2024-01-10")
                path.contains("/topics/101") -> topicDetailJson(101L, repliedAt = "2024-01-20")
                else -> error("Unexpected: $path")
            }
        }
        val items = repo.getFeedItems(listOf(group, group2))
        assertEquals(listOf(101L, 100L), items.map { it.id })
    }

    @Test
    fun `getFeedItems pins sticky topics above newer discussions`() = runTest {
        val group2 = group.copy(id = 11L, forumId = 43L)
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/42/") -> topicsJson(100L)
                path.contains("/forums/43/") -> topicsJson(101L)
                // The sticky topic's last reply is OLDER than the discussion's,
                // yet it must still sort first.
                path.contains("/topics/100") -> topicDetailJson(100L, sticky = true, repliedAt = "2024-01-10")
                path.contains("/topics/101") -> topicDetailJson(101L, repliedAt = "2024-01-20")
                else -> error("Unexpected: $path")
            }
        }
        val items = repo.getFeedItems(listOf(group, group2))
        assertEquals(listOf(100L, 101L), items.map { it.id })
        assertTrue(items.first().sticky)
    }

    @Test
    fun `getFeedItems sorts sticky topics among themselves by lastPostAt`() = runTest {
        val group2 = group.copy(id = 11L, forumId = 43L)
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/42/") -> topicsJson(100L)
                path.contains("/forums/43/") -> topicsJson(101L)
                path.contains("/topics/100") -> topicDetailJson(100L, sticky = true, repliedAt = "2024-01-10")
                path.contains("/topics/101") -> topicDetailJson(101L, sticky = true, repliedAt = "2024-01-20")
                else -> error("Unexpected: $path")
            }
        }
        val items = repo.getFeedItems(listOf(group, group2))
        assertEquals(listOf(101L, 100L), items.map { it.id })
    }

    @Test
    fun `getFeedItems returns empty list when groups have no topics`() = runTest {
        val repo = repoWithRoute { """{"topics":[]}""" }
        assertEquals(emptyList(), repo.getFeedItems(listOf(group)))
    }

    @Test
    fun `getFeedItems returns empty list when group list is empty`() = runTest {
        val repo = repoWithRoute { error("should not be called") }
        assertEquals(emptyList(), repo.getFeedItems(emptyList()))
    }

    @Test
    fun `getCurrentUser delegates to api client`() = runTest {
        val repo = FeedRepository(routingApiClient { CURRENT_USER_JSON })
        assertEquals("yarnie", repo.getCurrentUser().username)
    }

    @Test
    fun `getUserGroups delegates to api client`() = runTest {
        val repo = FeedRepository(routingApiClient { path ->
            when {
                path.contains("memberships") -> MEMBERSHIPS_HTML
                path.contains("groups/search") -> GROUPS_JSON
                else -> """{"groups":[]}"""
            }
        })
        val groups = repo.getUserGroups("yarnie")
        assertEquals(1, groups.size)
        assertEquals("KAL Hub", groups[0].name)
    }

    @Test
    fun `getFeedItems attributes discussion card to latest replier`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(username = "replier")
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.getFeedItems(listOf(group)).single()
        assertEquals("replier", item.latestReplyAuthor?.username)
        assertEquals("Latest reply text", item.latestReplyPreview)
        assertEquals("replier", item.displayAuthor.username)
        assertEquals("yarnie", item.author.username) // opening poster preserved for detail view
    }

    @Test
    fun `getFeedItems skips latest-post fetch for single-post topics`() = runTest {
        val requestedPaths = mutableListOf<String>()
        val repo = repoWithRoute { path ->
            requestedPaths += path
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L, postsCount = 1)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.getFeedItems(listOf(group)).single()
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(emptyList(), requestedPaths.filter { it.contains("/posts.json") })
    }

    @Test
    fun `getFeedItems skips latest-post fetch for sticky topics`() = runTest {
        // Announcements discard reply attribution — the extra request would be pure waste.
        val requestedPaths = mutableListOf<String>()
        val repo = repoWithRoute { path ->
            requestedPaths += path
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L, sticky = true)
                else -> error("Unexpected: $path")
            }
        }
        assertTrue(repo.getFeedItems(listOf(group)).single().sticky)
        assertEquals(emptyList(), requestedPaths.filter { it.contains("/posts.json") })

        // The field-level contract the old AnnouncementTopic type used to guarantee
        // structurally: pinned cards attribute to the opening post.
        val item = repo.getFeedItems(listOf(group)).single()
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(null, item.latestReplyPreview)
        assertEquals(item.author, item.displayAuthor)
    }

    @Test
    fun `getFeedItems attributes a topic with images to its latest replier like any other`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(username = "replier")
                path.contains("/topics/") -> topicDetailJson(100L, imagesCount = 5)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.getFeedItems(listOf(group)).single()
        assertEquals("replier", item.latestReplyAuthor?.username)
    }

    @Test
    fun `getFeedItems falls back to opening post when latest-post fetch fails`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> error("Simulated posts failure")
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.getFeedItems(listOf(group)).single()
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(null, item.latestReplyPreview)
        assertEquals("yarnie", item.displayAuthor.username)
    }

    @Test
    fun `getFeedItems ignores a latest reply whose user is missing`() = runTest {
        // Author and preview must stand or fall together: a reply without a user
        // must not show its text attributed to the opening poster.
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") ->
                    """{"posts":[{"id":7,"body_html":"<p>orphan reply</p>","created_at":"2024-01-16T10:00:00Z"}]}"""
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.getFeedItems(listOf(group)).single()
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(null, item.latestReplyPreview)
        assertEquals("yarnie", item.displayAuthor.username)
    }

    @Test
    fun `getFeedItems strips html and truncates latest reply preview`() = runTest {
        val longBody = "<p>" + "y".repeat(300) + "</p>"
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(bodyHtml = longBody)
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.getFeedItems(listOf(group)).single()
        assertEquals(200, item.latestReplyPreview?.length)
        assertEquals("y".repeat(200), item.latestReplyPreview)
    }

    @Test
    fun `getFeedItems leaves entity-escaped literal angle brackets alone`() = runTest {
        // Re-parsing already-decoded text to catch entity-escaped tags was tried and
        // reverted: it can't tell "&lt;b&gt;" (escaped markup) apart from "&lt;total&gt;"
        // (a literal comparison someone typed), and re-parsing the latter as HTML
        // corrupts it. A single pass at least renders entities correctly either way.
        val items = singleTopicRepo(summary = "5&lt;total&gt;10").getFeedItems(listOf(group))
        assertEquals("5<total>10", items.single().bodyPreview)
    }
}
