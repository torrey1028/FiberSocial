package com.autom8ed.fibersocial.feed

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

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
        postsCount: Int = 2,
        lastRead: Int = 0,
        starter: String = "yarnie",
    ) = repoWithRoute { path ->
        when {
            path.contains("/forums/") -> topicsJson(topicId)
            path.contains("/topics/") -> topicDetailJson(
                id = topicId,
                imagesCount = imagesCount,
                sticky = sticky,
                repliedAt = repliedAt,
                summary = summary,
                postsCount = postsCount,
                lastRead = lastRead,
                starter = starter,
            )
            else -> error("Unexpected: $path")
        }
    }

    private suspend fun FeedRepository.singlePageItems(group: com.autom8ed.fibersocial.feed.models.Group = this@FeedRepositoryTest.group) =
        getFeedItemsPage(group, page = 1).items

    @Test
    fun `getFeedItemsPage caps concurrent topic-detail fetches at 4`() = runTest {
        // Issue #258: a group page fans out one getTopicDetail request per topic with no
        // cap, so an image-heavy group with many topics fired a burst of simultaneous
        // requests — rate-limit bait, and a burst of concurrent 401s races the token
        // refresh. EventSyncRunner already caps its own fan-out at 4 for the same reason.
        val topicIds = (1L..10L).toList()
        // MockEngine dispatches concurrent requests on real threads (not the runTest
        // virtual scheduler), so plain vars here would race — a Mutex-guarded update
        // avoids a flaky/inflated peak reading that isn't actually the semaphore's fault.
        val counterLock = Mutex()
        var current = 0
        var peak = 0
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            when {
                path.contains("/forums/") -> respond(
                    topicsJson(*topicIds.toLongArray()),
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                path.contains("/topics/") -> {
                    counterLock.withLock { current++; peak = maxOf(peak, current) }
                    delay(10)
                    counterLock.withLock { current-- }
                    val id = path.substringAfterLast("/").removeSuffix(".json").toLong()
                    respond(
                        topicDetailJson(id = id),
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                else -> error("Unexpected: $path")
            }
        }
        val httpClient = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repo = FeedRepository(RavelryApiClient(httpClient, FakeFeedTokenStorage()))

        repo.getFeedItemsPage(group, page = 1)

        assertTrue(peak <= 4, "peak concurrent topic-detail requests was $peak, expected <= 4")
    }

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
    fun `getFeedItemsPage attributes the card to the topic starter`() = runTest {
        // Issue #185: the card is the topic starter's, taken from the detail's
        // created_by_user, not the latest replier.
        val item = singleTopicRepo(starter = "opener").singlePageItems().single()
        assertEquals("opener", item.author.username)
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
    fun `getFeedItemsPage carries the full topic summary as source and html`() = runTest {
        val long = "x".repeat(300)
        val item = singleTopicRepo(summary = long).singlePageItems().single()
        assertEquals(300, item.bodySummary.length)
        assertTrue(item.hasSummary)
    }

    @Test
    fun `getFeedItemsPage uses empty summary and reports no summary when the topic has none`() = runTest {
        val item = singleTopicRepo(summary = null).singlePageItems().single()
        assertEquals("", item.bodySummary)
        assertEquals("", item.bodySummaryHtml)
        assertFalse(item.hasSummary)
    }

    @Test
    fun `getFeedItemsPage populates bodySummary on sticky topics`() = runTest {
        val item = singleTopicRepo(sticky = true, summary = "Pinned info").singlePageItems().single()
        assertEquals("Pinned info", item.bodySummary)
    }

    @Test
    fun `getFeedItemsPage carries summary_html when present alongside the raw source`() = runTest {
        // Ravelry's rendering resolves the dangling ** its raw summary field drops
        // (issue #104) — both fields are carried so the card can prefer the html.
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
        assertEquals("<p><strong>Please use this thread</strong></p>", item.bodySummaryHtml)
        assertEquals("**Please use this thread", item.bodySummary)
    }

    @Test
    fun `getFeedItemsPage leaves bodySummaryHtml empty when summary_html is absent`() = runTest {
        val item = singleTopicRepo(summary = "**Please use this thread").singlePageItems().single()
        assertEquals("**Please use this thread", item.bodySummary)
        assertEquals("", item.bodySummaryHtml)
    }

    @Test
    fun `getFeedItemsPage carries createdAt from the topic detail`() = runTest {
        // Issue #242: the card needs the topic's original start time, not just repliedAt.
        val repo = repoWithRoute { path ->
            when {
                path.contains("/forums/") -> topicsJson(100L)
                path.contains("/topics/") -> topicDetailJson(100L, createdAt = "2024-01-01")
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.singlePageItems().single()
        assertEquals("2024-01-01", item.createdAt)
    }

    @Test
    fun `getFeedItemsPage leaves createdAt null when the topic detail omits it`() = runTest {
        val item = singleTopicRepo().singlePageItems().single()
        assertNull(item.createdAt)
    }

    @Test
    fun `getFeedItemsPage sets postCount to the topic's total post count`() = runTest {
        // postCount includes the opening post, so it lines up with unreadCount (issue #185).
        val item = singleTopicRepo(postsCount = 5).singlePageItems().single()
        assertEquals(5, item.postCount)
    }

    @Test
    fun `getFeedItemsPage counts posts after the read marker as unread`() = runTest {
        // 5 posts, read up to 2 → posts 3,4,5 unread, first unread is post 3 (issue #185).
        val item = singleTopicRepo(postsCount = 5, lastRead = 2).singlePageItems().single()
        assertEquals(3, item.unreadCount)
        assertEquals(3, item.firstUnreadPostNumber)
    }

    @Test
    fun `getFeedItemsPage reports nothing unread once the marker reaches the last post`() = runTest {
        val item = singleTopicRepo(postsCount = 5, lastRead = 5).singlePageItems().single()
        assertEquals(0, item.unreadCount)
        assertNull(item.firstUnreadPostNumber)
    }

    @Test
    fun `getFeedItemsPage counts every post unread for a never-read topic`() = runTest {
        // lastRead 0 (never opened) → all posts unread, land on post 1.
        val item = singleTopicRepo(postsCount = 4, lastRead = 0).singlePageItems().single()
        assertEquals(4, item.unreadCount)
        assertEquals(1, item.firstUnreadPostNumber)
    }

    @Test
    fun `getFeedItemsPage floors unread at zero when the marker runs ahead of the count`() = runTest {
        // A marker transiently ahead of postsCount must never yield a negative unread.
        val item = singleTopicRepo(postsCount = 2, lastRead = 5).singlePageItems().single()
        assertEquals(0, item.unreadCount)
        assertNull(item.firstUnreadPostNumber)
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
