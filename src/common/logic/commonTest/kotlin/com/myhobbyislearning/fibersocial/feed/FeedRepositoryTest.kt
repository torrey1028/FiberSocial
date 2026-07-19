package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.Group
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
    private val group = com.myhobbyislearning.fibersocial.feed.models.Group(
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

    private suspend fun FeedRepository.singlePageItems(group: com.myhobbyislearning.fibersocial.feed.models.Group = this@FeedRepositoryTest.group) =
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

    /** KAL Hub (forum 42) plus a second group, for cross-group my-posts attribution. */
    private val twoGroups = listOf(
        group,
        com.myhobbyislearning.fibersocial.feed.models.Group(
            id = 20L, name = "Lace Society", permalink = "lace", forumId = 77L,
        ),
    )

    @Test
    fun `getMyPostsPage attributes topics to groups by the list entry's forum id`() = runTest {
        // The detail response reports forum_id as 0, so attribution MUST come from the
        // filtered_topics list entry — this fails if the mapping ever reads the detail.
        val repo = repoWithRoute { path ->
            when {
                path.contains("filtered_topics") -> """{"topics":[
                    {"id":100,"title":"Topic 100","forum_id":42},
                    {"id":200,"title":"Topic 200","forum_id":77}
                ]}"""
                path.contains("/topics/100") -> topicDetailJson(100L)
                path.contains("/topics/200") -> topicDetailJson(200L)
                else -> error("Unexpected: $path")
            }
        }
        val page = repo.getMyPostsPage(twoGroups, page = 1)
        assertEquals(
            mapOf(100L to "KAL Hub", 200L to "Lace Society"),
            page.items.associate { it.id to it.groupName },
        )
        assertEquals(
            mapOf(100L to 10L, 200L to 20L),
            page.items.associate { it.id to it.groupId },
        )
    }

    @Test
    fun `getMyPostsPage keeps a topic whose forum matches no group`() = runTest {
        // e.g. posted in a since-left group: still the user's post, shown without a
        // group attribution rather than silently dropped.
        val repo = repoWithRoute { path ->
            when {
                path.contains("filtered_topics") ->
                    """{"topics":[{"id":100,"title":"Topic 100","forum_id":999}]}"""
                path.contains("/topics/100") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        val item = repo.getMyPostsPage(twoGroups, page = 1).items.single()
        assertEquals("", item.groupName)
        assertEquals(0L, item.groupId)
    }

    @Test
    fun `getMyPostsPage sorts by recency only and ignores sticky`() = runTest {
        // Sticky means "pinned in its own forum" — meaningless across groups, so a
        // sticky topic with older activity must NOT jump ahead of a newer plain one.
        val repo = repoWithRoute { path ->
            when {
                path.contains("filtered_topics") -> """{"topics":[
                    {"id":100,"title":"Topic 100","forum_id":42},
                    {"id":200,"title":"Topic 200","forum_id":42}
                ]}"""
                path.contains("/topics/100") ->
                    topicDetailJson(100L, sticky = true, repliedAt = "2024-01-10")
                path.contains("/topics/200") ->
                    topicDetailJson(200L, repliedAt = "2024-01-20")
                else -> error("Unexpected: $path")
            }
        }
        val page = repo.getMyPostsPage(twoGroups, page = 1)
        assertEquals(listOf(200L, 100L), page.items.map { it.id })
    }

    @Test
    fun `getMyPostsPage threads hasMore through from the paginator`() = runTest {
        val repo = repoWithRoute { path ->
            when {
                path.contains("filtered_topics") ->
                    """{"topics":[{"id":100,"title":"Topic 100","forum_id":42}],
                        "paginator":{"page":1,"page_count":2,"results":30}}"""
                path.contains("/topics/100") -> topicDetailJson(100L)
                else -> error("Unexpected: $path")
            }
        }
        assertTrue(repo.getMyPostsPage(twoGroups, page = 1).hasMore)
    }

    @Test
    fun `getMyPostsPage skips a topic whose detail fetch 403s rather than failing the whole page`() = runTest {
        // A topic posted in a group the user has since left can 403 on its own detail
        // fetch (the forum itself is off-limits) even though the list entry that named
        // it came back fine. awaitAll's fail-fast/cancel-siblings behavior means an
        // unguarded fetch here would blank the whole cross-group page over one topic —
        // this is what actually makes "keep unattributed rather than dropped" (see the
        // test above) true when the topic isn't just unattributed but inaccessible.
        val engine = io.ktor.client.engine.mock.MockEngine { request ->
            when {
                request.url.encodedPath.contains("filtered_topics") -> respond(
                    content = """{"topics":[
                        {"id":100,"title":"Topic 100","forum_id":42},
                        {"id":200,"title":"Topic 200","forum_id":999}
                    ]}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.contains("/topics/100") -> respond(
                    content = topicDetailJson(100L),
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
                request.url.encodedPath.contains("/topics/200") -> respond("", HttpStatusCode.Forbidden)
                else -> error("Unexpected: ${request.url}")
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repo = FeedRepository(RavelryApiClient(client, FakeFeedTokenStorage()))

        val page = repo.getMyPostsPage(twoGroups, page = 1)

        assertEquals(listOf(100L), page.items.map { it.id })
    }

    // ---- getDrawerUnread (issue #350 part 3) ----

    /** Group 1 sits on forum 42; group 2 on forum 77. */
    private val dotGroups = listOf(
        Group(id = 1, name = "Alpha", permalink = "alpha", forumId = 42),
        Group(id = 2, name = "Beta", permalink = "beta", forumId = 77),
    )

    /** A topics-list entry with a Ravelry-format `replied_at`. */
    private fun activityTopic(id: Long, repliedAt: String?, sticky: Boolean = false) =
        """{"id":$id,"title":"T$id","sticky":$sticky,"replied_at":${
            if (repliedAt != null) "\"$repliedAt\"" else "null"
        }}"""

    /** Epoch millis for a UTC Ravelry timestamp, so tests can express "before/after". */
    private fun at(timestamp: String): Long =
        parseRavelryTimestamp(timestamp)!!.toEpochMilliseconds()

    /**
     * A repo whose per-forum topic lists come from [topicsByForumId] and whose
     * `filtered_topics?status=posting` leg returns [postingJson].
     *
     * A forum absent from [topicsByForumId] responds 500, which is how the
     * "a failing group is simply omitted" case is exercised.
     */
    private fun drawerRepo(
        topicsByForumId: Map<Long, String>,
        postingJson: String = """{"topics":[]}""",
    ): FeedRepository {
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            // filtered_topics lives under /forums/ too, so it must be matched first.
            if (path.contains("filtered_topics")) {
                return@MockEngine respond(
                    postingJson,
                    HttpStatusCode.OK,
                    headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
            val forumId = Regex("""/forums/(\d+)/topics""").find(path)?.groupValues?.get(1)?.toLong()
            val body = topicsByForumId[forumId]
                ?: return@MockEngine respond("boom", HttpStatusCode.InternalServerError)
            respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return FeedRepository(RavelryApiClient(client, FakeFeedTokenStorage()))
    }

    @Test
    fun `getDrawerUnread dots a group whose latest activity is newer than the last view`() = runTest {
        val repo = drawerRepo(
            mapOf(
                42L to """{"topics":[${activityTopic(1, "2026/07/10 12:00:00 +0000")}]}""",
                77L to """{"topics":[${activityTopic(2, "2026/07/01 12:00:00 +0000")}]}""",
            ),
        )

        val result = repo.getDrawerUnread(
            groups = dotGroups,
            // Both groups viewed on the 5th: group 1's activity is after it, group 2's before.
            groupLastViewed = mapOf(1L to at("2026/07/05 00:00:00 +0000"), 2L to at("2026/07/05 00:00:00 +0000")),
            now = at("2026/07/19 00:00:00 +0000"),
        )

        assertEquals(setOf(42L), result.unread.unreadGroupForumIds)
    }

    @Test
    fun `getDrawerUnread seeds an unseen group silently instead of lighting it`() = runTest {
        // A fresh install (or a newly joined group) has no stored timestamp. Lighting every
        // row at once would be noise, so the first pass records "now" and shows nothing.
        val repo = drawerRepo(
            mapOf(
                42L to """{"topics":[${activityTopic(1, "2026/07/10 12:00:00 +0000")}]}""",
                77L to """{"topics":[${activityTopic(2, "2026/07/11 12:00:00 +0000")}]}""",
            ),
        )
        val now = at("2026/07/19 00:00:00 +0000")

        val result = repo.getDrawerUnread(dotGroups, groupLastViewed = emptyMap(), now = now)

        assertTrue(result.unread.unreadGroupForumIds.isEmpty())
        assertEquals(mapOf(1L to now, 2L to now), result.groupLastViewed)
    }

    @Test
    fun `getDrawerUnread takes the newest topic on the page, not the first`() = runTest {
        // THE TRAP this design guards against: getGroupTopics sends no explicit sort, and
        // forums pin sticky topics (issue #332), so slot 1 can hold a pinned thread whose
        // last reply is ancient while a genuinely new reply sits below it. A pageSize=1
        // "take the first topic" implementation would report the stale timestamp and miss
        // the new activity entirely.
        val repo = drawerRepo(
            mapOf(
                42L to """{"topics":[
                    ${activityTopic(1, "2026/01/01 00:00:00 +0000", sticky = true)},
                    ${activityTopic(2, "2026/07/10 12:00:00 +0000")}
                ]}""",
            ),
        )

        val result = repo.getDrawerUnread(
            groups = listOf(dotGroups.first()),
            groupLastViewed = mapOf(1L to at("2026/07/05 00:00:00 +0000")),
            now = at("2026/07/19 00:00:00 +0000"),
        )

        assertEquals(setOf(42L), result.unread.unreadGroupForumIds)
    }

    @Test
    fun `getDrawerUnread requests only a small page per group`() = runTest {
        // This is one request PER GROUP, so the page has to stay small; only the newest
        // timestamp is wanted, never the topics themselves.
        var capturedPageSize: String? = null
        val engine = MockEngine { request ->
            if (!request.url.encodedPath.contains("filtered_topics")) {
                capturedPageSize = request.url.parameters["page_size"]
            }
            respond(
                """{"topics":[]}""",
                HttpStatusCode.OK,
                headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val repo = FeedRepository(RavelryApiClient(client, FakeFeedTokenStorage()))

        repo.getDrawerUnread(listOf(dotGroups.first()), emptyMap(), now = 0L)

        // Small, and emphatically not 1 — see the sticky-topic test above.
        assertEquals("10", capturedPageSize)
    }

    @Test
    fun `getDrawerUnread shows no dot for a group whose fetch failed`() = runTest {
        // Forum 77 is absent from the map, so its request 500s. A transient failure must
        // not invent a dot — nor take the healthy group down with it (awaitAll would).
        val repo = drawerRepo(
            mapOf(42L to """{"topics":[${activityTopic(1, "2026/07/10 12:00:00 +0000")}]}"""),
        )

        val result = repo.getDrawerUnread(
            groups = dotGroups,
            groupLastViewed = mapOf(1L to at("2026/07/05 00:00:00 +0000"), 2L to at("2026/07/05 00:00:00 +0000")),
            now = at("2026/07/19 00:00:00 +0000"),
        )

        assertEquals(setOf(42L), result.unread.unreadGroupForumIds)
    }

    @Test
    fun `getDrawerUnread shows no dot when no topic carries a parseable timestamp`() = runTest {
        val repo = drawerRepo(
            mapOf(
                42L to """{"topics":[
                    ${activityTopic(1, null)},
                    ${activityTopic(2, "not-a-timestamp")}
                ]}""",
            ),
        )

        val result = repo.getDrawerUnread(
            groups = listOf(dotGroups.first()),
            groupLastViewed = mapOf(1L to at("2026/01/01 00:00:00 +0000")),
            now = at("2026/07/19 00:00:00 +0000"),
        )

        assertTrue(result.unread.unreadGroupForumIds.isEmpty())
    }

    @Test
    fun `getDrawerUnread still reports the read-marker-based Your Posts dot`() = runTest {
        // The "Your Posts" dot is cross-group and deliberately unchanged by part 3: it
        // stays read-marker based, since the user has by definition opened those topics.
        val repo = drawerRepo(
            topicsByForumId = mapOf(42L to """{"topics":[]}""", 77L to """{"topics":[]}"""),
            postingJson = """{"topics":[
                {"id":3,"title":"C","forum_id":42,"forum_posts_count":9,"last_read":2}
            ]}""",
        )

        val result = repo.getDrawerUnread(dotGroups, emptyMap(), now = 0L)

        assertTrue(result.unread.yourPostsHasUnread)
    }

    @Test
    fun `getYourPostsUnread is false when every posted-in topic is at its read marker`() = runTest {
        val repo = drawerRepo(
            topicsByForumId = emptyMap(),
            postingJson = """{"topics":[
                {"id":1,"title":"A","forum_id":42,"forum_posts_count":3,"last_read":3}
            ]}""",
        )

        assertFalse(repo.getYourPostsUnread())
    }

    @Test
    fun `getDrawerUnread prunes last-viewed entries for groups the user left`() = runTest {
        // Otherwise the map grows forever — same maintenance rule as reconcileGroupOrder.
        val repo = drawerRepo(mapOf(42L to """{"topics":[]}"""))
        val viewedAt = at("2026/07/05 00:00:00 +0000")

        val result = repo.getDrawerUnread(
            groups = listOf(dotGroups.first()),
            groupLastViewed = mapOf(1L to viewedAt, 999L to viewedAt),
            now = at("2026/07/19 00:00:00 +0000"),
        )

        assertEquals(mapOf(1L to viewedAt), result.groupLastViewed)
    }
}
