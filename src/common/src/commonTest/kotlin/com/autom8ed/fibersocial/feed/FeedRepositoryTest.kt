package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.models.FeedItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
    fun `getFeedItems classifies topic with images as ProjectTopic`() = runTest {
        val items = singleTopicRepo(imagesCount = 3).getFeedItems(listOf(group))
        assertIs<FeedItem.ProjectTopic>(items.single())
        assertEquals(3, (items.single() as FeedItem.ProjectTopic).imageCount)
    }

    @Test
    fun `getFeedItems classifies sticky topic as AnnouncementTopic`() = runTest {
        val items = singleTopicRepo(sticky = true).getFeedItems(listOf(group))
        assertIs<FeedItem.AnnouncementTopic>(items.single())
    }

    @Test
    fun `getFeedItems classifies plain topic as DiscussionTopic`() = runTest {
        val items = singleTopicRepo().getFeedItems(listOf(group))
        assertIs<FeedItem.DiscussionTopic>(items.single())
    }

    @Test
    fun `getFeedItems images flag takes precedence over sticky`() = runTest {
        // imagesCount > 0 wins even when sticky = true
        val items = singleTopicRepo(imagesCount = 1, sticky = true).getFeedItems(listOf(group))
        assertIs<FeedItem.ProjectTopic>(items.single())
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
        val item = repo.getFeedItems(listOf(group)).single() as FeedItem.DiscussionTopic
        assertEquals("unknown", item.author.username)
    }

    @Test
    fun `getFeedItems truncates bodyPreview to 200 chars but keeps full bodySummary`() = runTest {
        val long = "x".repeat(300)
        val items = singleTopicRepo(summary = long).getFeedItems(listOf(group))
        val item = items.single() as FeedItem.DiscussionTopic
        assertEquals(200, item.bodyPreview.length)
        assertEquals(300, item.bodySummary.length)
    }

    @Test
    fun `getFeedItems uses empty string for both preview and summary when summary is null`() = runTest {
        val items = singleTopicRepo(summary = null).getFeedItems(listOf(group))
        val item = items.single() as FeedItem.DiscussionTopic
        assertEquals("", item.bodyPreview)
        assertEquals("", item.bodySummary)
    }

    @Test
    fun `getFeedItems populates bodySummary on AnnouncementTopic`() = runTest {
        val items = singleTopicRepo(sticky = true, summary = "Pinned info").getFeedItems(listOf(group))
        val item = items.single() as FeedItem.AnnouncementTopic
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
        val item = repo.getFeedItems(listOf(group)).single() as FeedItem.DiscussionTopic
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
        val item = repo.getFeedItems(listOf(group)).single() as FeedItem.DiscussionTopic
        assertEquals(null, item.latestReplyAuthor)
        assertEquals(emptyList(), requestedPaths.filter { it.contains("/posts.json") })
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
        val item = repo.getFeedItems(listOf(group)).single() as FeedItem.DiscussionTopic
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
        val item = repo.getFeedItems(listOf(group)).single() as FeedItem.DiscussionTopic
        assertEquals(200, item.latestReplyPreview?.length)
        assertEquals("y".repeat(200), item.latestReplyPreview)
    }
}
