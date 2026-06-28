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
    fun `getFeedItems classifies topic with images as ProjectPost`() = runTest {
        val items = singleTopicRepo(imagesCount = 3).getFeedItems(listOf(group))
        assertIs<FeedItem.ProjectPost>(items.single())
        assertEquals(3, (items.single() as FeedItem.ProjectPost).imageCount)
    }

    @Test
    fun `getFeedItems classifies sticky topic as AnnouncementPost`() = runTest {
        val items = singleTopicRepo(sticky = true).getFeedItems(listOf(group))
        assertIs<FeedItem.AnnouncementPost>(items.single())
    }

    @Test
    fun `getFeedItems classifies plain topic as DiscussionPost`() = runTest {
        val items = singleTopicRepo().getFeedItems(listOf(group))
        assertIs<FeedItem.DiscussionPost>(items.single())
    }

    @Test
    fun `getFeedItems images flag takes precedence over sticky`() = runTest {
        // imagesCount > 0 wins even when sticky = true
        val items = singleTopicRepo(imagesCount = 1, sticky = true).getFeedItems(listOf(group))
        assertIs<FeedItem.ProjectPost>(items.single())
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
        val item = repo.getFeedItems(listOf(group)).single() as FeedItem.DiscussionPost
        assertEquals("unknown", item.author.username)
    }

    @Test
    fun `getFeedItems truncates summary to 200 chars`() = runTest {
        val long = "x".repeat(300)
        val items = singleTopicRepo(summary = long).getFeedItems(listOf(group))
        val preview = (items.single() as FeedItem.DiscussionPost).bodyPreview
        assertEquals(200, preview.length)
    }

    @Test
    fun `getFeedItems uses empty string when summary is null`() = runTest {
        val items = singleTopicRepo(summary = null).getFeedItems(listOf(group))
        assertEquals("", (items.single() as FeedItem.DiscussionPost).bodyPreview)
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
}
