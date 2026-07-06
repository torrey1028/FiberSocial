package com.autom8ed.fibersocial.feed

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

    private suspend fun FeedRepository.singlePageItems(group: com.autom8ed.fibersocial.feed.models.Group = this@FeedRepositoryTest.group) =
        getFeedItemsPage(group, page = 1).items

    @Test
    fun `getFeedItemsPage lists a topic with images like any other topic`() = runTest {
        // Issue #77: images in a thread must not change how the topic is listed.
        val items = singleTopicRepo(imagesCount = 3).singlePageItems()
        assertFalse(items.single().sticky)
    }

    @Test
    fun `getFeedItemsPage marks a sticky topic sticky`() = runTest {
        val items = singleTopicRepo(sticky = true).singlePageItems()
        assertTrue(items.single().sticky)
    }

    @Test
    fun `getFeedItemsPage marks a plain topic not sticky`() = runTest {
        val items = singleTopicRepo().singlePageItems()
        assertFalse(items.single().sticky)
    }

    @Test
    fun `getFeedItemsPage sticky topic with images is still sticky`() = runTest {
        // A pinned topic must pin regardless of photos in the thread (issue #77: the old
        // image-first classification hid three of a real group's four stickies).
        val items = singleTopicRepo(imagesCount = 1, sticky = true).singlePageItems()
        assertTrue(items.single().sticky)
    }

    @Test
    fun `getFeedItemsPage populates groupId and groupName from group`() = runTest {
        val items = singleTopicRepo().singlePageItems()
        assertEquals(10L, items.single().groupId)
        assertEquals("KAL Hub", items.single().groupName)
    }

    @Test
    fun `getFeedItemsPage uses unknown author when createdByUser is null`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> """{"topic":{"id":100,"title":"T"}}"""
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals("unknown", item.author.username)
    }

    @Test
    fun `getFeedItemsPage truncates bodyPreview to 200 chars but keeps full bodySummary`() = runTest {
        val long = "x".repeat(300)
        val items = singleTopicRepo(summary = long).singlePageItems()
        val item = items.single()
        assertEquals(200, item.bodyPreview.length)
        assertEquals(300, item.bodySummary.length)
    }

    @Test
    fun `getFeedItemsPage uses empty string for both preview and summary when summary is null`() = runTest {
        val items = singleTopicRepo(summary = null).singlePageItems()
        val item = items.single()
        assertEquals("", item.bodyPreview)
        assertEquals("", item.bodySummary)
    }

    @Test
    fun `getFeedItemsPage populates bodySummary on sticky topics`() = runTest {
        val items = singleTopicRepo(sticky = true, summary = "Pinned info").singlePageItems()
        val item = items.single()
        assertEquals("Pinned info", item.bodySummary)
        assertEquals("Pinned info", item.bodyPreview)
    }

    @Test
    fun `getFeedItemsPage builds the preview from summary_html when present`() = runTest {
        // Ravelry's rendering resolves the dangling ** its raw summary field drops
        // (issue #104) — prefer it over stripping the damaged Markdown ourselves.
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(
                    100L,
                    summary = "**Please use this thread",
                    summaryHtml = "<p><strong>Please use this thread</strong></p>",
                )
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals("Please use this thread", item.bodyPreview)
        assertEquals("<p><strong>Please use this thread</strong></p>", item.bodySummaryHtml)
        assertEquals("**Please use this thread", item.bodySummary)
    }

    @Test
    fun `getFeedItemsPage strips markdown from the preview when summary_html is absent`() = runTest {
        val items = singleTopicRepo(summary = "**Please use this thread").singlePageItems()
        val item = items.single()
        assertEquals("Please use this thread", item.bodyPreview)
        assertEquals("", item.bodySummaryHtml)
    }

    @Test
    fun `getFeedItemsPage falls back to markdown stripping when summary_html is blank`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L, summary = "**Bold** text", summaryHtml = "")
                else -> error("Unexpected: $path")
            }
        }
        assertEquals("Bold text", repo.singlePageItems().single().bodyPreview)
    }

    @Test
    fun `getFeedItemsPage sorts a page's results by lastPostAt descending`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L, 101L)
                path.contains("/topics/100") -> topicDetailJson(100L, repliedAt = "2024-01-10")
                path.contains("/topics/101") -> topicDetailJson(101L, repliedAt = "2024-01-20")
                else -> error("Unexpected: $path")
            }
        }
        val items = repo.singlePageItems()
        assertEquals(listOf(101L, 100L), items.map { it.id })
    }

    @Test
    fun `getFeedItemsPage pins sticky topics above newer discussions`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L, 101L)
                // The sticky topic's last reply is OLDER than the discussion's,
                // yet it must still sort first.
                path.contains("/topics/100") -> topicDetailJson(100L, sticky = true, repliedAt = "2024-01-10")
                path.contains("/topics/101") -> topicDetailJson(101L, repliedAt = "2024-01-20")
                else -> error("Unexpected: $path")
            }
        }
        val items = repo.singlePageItems()
        assertEquals(listOf(100L, 101L), items.map { it.id })
        assertTrue(items.first().sticky)
    }

    @Test
    fun `getFeedItemsPage sorts sticky topics among themselves by lastPostAt`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L, 101L)
                path.contains("/topics/100") -> topicDetailJson(100L, sticky = true, repliedAt = "2024-01-10")
                path.contains("/topics/101") -> topicDetailJson(101L, sticky = true, repliedAt = "2024-01-20")
                else -> error("Unexpected: $path")
            }
        }
        val items = repo.singlePageItems()
        assertEquals(listOf(101L, 100L), items.map { it.id })
    }

    @Test
    fun `getFeedItemsPage returns empty list when the group has no topics`() = runTest {
        val repo = repoWithRoute { """{"topics":[]}""" }
        assertEquals(emptyList(), repo.singlePageItems())
    }

    @Test
    fun `getFeedItemsPage resolves topics fetched concurrently within one page`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L, 200L)
                path.contains("/topics/100") -> topicDetailJson(100L, postsCount = 1)
                path.contains("/topics/200") -> topicDetailJson(200L, postsCount = 1)
                else -> error("Unexpected: $path")
            }
        }
        val items = repo.singlePageItems()
        assertEquals(setOf(100L, 200L), items.map { it.id }.toSet())
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
    fun `getFeedItemsPage attributes discussion card to latest replier`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(username = "replier")
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals("replier", item.latestReplyAuthor?.username)
        assertEquals("Latest reply text", item.latestReplyPreview)
        assertEquals("replier", item.displayAuthor.username)
        assertEquals("yarnie", item.author.username) // opening poster preserved for detail view
    }

    @Test
    fun `getFeedItemsPage fetches the opening post for single-post topics`() = runTest {
        // Reversed from the original skip (issues #154/#185): the summary can't be
        // trusted for formatting or images, so the opening post body is fetched.
        val requestedPaths = mutableListOf<String>()
        val repo = repoWithRoute { path ->
            requestedPaths += path
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(username = "yarnie")
                path.contains("/topics/") -> topicDetailJson(100L, postsCount = 1)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals(1, requestedPaths.count { it.contains("/posts.json") })
        // ...but never as a "reply": attribution stays with the opener.
        assertEquals(null, item.latestReplyAuthor)
    }

    @Test
    fun `getFeedItemsPage skips latest-post fetch for sticky topics`() = runTest {
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
        assertTrue(repo.singlePageItems().single().sticky)
        assertEquals(emptyList(), requestedPaths.filter { it.contains("/posts.json") })

        // The field-level contract the old AnnouncementTopic type used to guarantee
        // structurally: pinned cards attribute to the opening post.
        val item = repo.singlePageItems().single()
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(null, item.latestReplyPreview)
        assertEquals(item.author, item.displayAuthor)
    }

    @Test
    fun `getFeedItemsPage attributes a topic with images to its latest replier like any other`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(username = "replier")
                path.contains("/topics/") -> topicDetailJson(100L, imagesCount = 5)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals("replier", item.latestReplyAuthor?.username)
    }

    @Test
    fun `getFeedItemsPage falls back to opening post when latest-post fetch fails`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> error("Simulated posts failure")
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(null, item.latestReplyPreview)
        assertEquals("yarnie", item.displayAuthor.username)
    }

    @Test
    fun `getFeedItemsPage ignores a latest reply whose user is missing`() = runTest {
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
        val item = repo.singlePageItems().single()
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(null, item.latestReplyPreview)
        assertEquals("yarnie", item.displayAuthor.username)
    }

    @Test
    fun `getFeedItemsPage strips html and truncates latest reply preview`() = runTest {
        val longBody = "<p>" + "y".repeat(300) + "</p>"
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(bodyHtml = longBody)
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals(200, item.latestReplyPreview?.length)
        assertEquals("y".repeat(200), item.latestReplyPreview)
        // The raw HTML is carried untruncated for the card's rich preview (issue #154).
        assertEquals(longBody, item.latestReplyHtml)
    }

    @Test
    fun `getFeedItemsPage previews a no-reply topic from its opening post body`() = runTest {
        // The topic summary is unreliable about formatting and never carries images, so
        // a topic without replies fetches its opening post for the card (issues #154/#185).
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(
                    username = "yarnie",
                    body = "Opening *italic* body",
                    bodyHtml = "<p>Opening <em>italic</em> body</p>",
                )
                path.contains("/topics/") -> topicDetailJson(100L, postsCount = 1)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        // Both fields carried: the Markdown source is canonical, the rendering
        // resolves emoji (same contract as Post.parseBodyDocument).
        assertEquals("Opening *italic* body", item.openingPostBody)
        assertEquals("<p>Opening <em>italic</em> body</p>", item.openingPostHtml)
        // The opening post is not a "reply": attribution stays with the opener.
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(null, item.latestReplyHtml)
    }

    @Test
    fun `getFeedItemsPage previews a sticky single-post topic from its opening post`() = runTest {
        // A pinned announcement with no replies still gets the rich opening-post
        // preview; the newest post IS the opening post, so the sticky invariant
        // (no reply attribution) holds.
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson(bodyHtml = "<p>Pinned rules</p>")
                path.contains("/topics/") -> topicDetailJson(100L, sticky = true, postsCount = 1)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertTrue(item.sticky)
        assertEquals("<p>Pinned rules</p>", item.openingPostHtml)
        assertEquals(null, item.latestReplyAuthor)
    }

    @Test
    fun `getFeedItemsPage keeps openingPostHtml empty for replied topics`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/posts.json") -> latestPostJson()
                path.contains("/topics/") -> topicDetailJson(100L, postsCount = 5)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals("", item.openingPostHtml)
        assertEquals("", item.openingPostBody)
        assertEquals("replier", item.latestReplyAuthor?.username)
        assertEquals("Latest **reply** text", item.latestReplyBody)
    }


    @Test
    fun `getFeedItemsPage reports hasMore true when more pages remain`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") ->
                    """{"topics":[{"id":100,"title":"Topic 100"}],"paginator":{"page":2,"page_count":3,"results":60}}"""
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val page = repo.getFeedItemsPage(group, page = 2)
        assertEquals(1, page.items.size)
        assertTrue(page.hasMore)
    }

    @Test
    fun `getFeedItemsPage reports hasMore false on the last page`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") ->
                    """{"topics":[{"id":100,"title":"Topic 100"}],"paginator":{"page":3,"page_count":3,"results":60}}"""
                path.contains("/topics/") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val page = repo.getFeedItemsPage(group, page = 3)
        assertFalse(page.hasMore)
    }
}
