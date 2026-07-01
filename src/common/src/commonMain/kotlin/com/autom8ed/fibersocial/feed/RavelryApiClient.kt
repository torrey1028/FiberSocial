package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.TokenStorage
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.Topic
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://api.ravelry.com"
private val lenientJson = Json { ignoreUnknownKeys = true }

// Ravelry has no API endpoint for "groups this user is a member of".
// We scrape the memberships page on www.ravelry.com using the session cookie
// captured during WebView OAuth login, then resolve each group's forum_id via the API.
private val GROUP_PERMALINK_REGEX = Regex("""href="https://www\.ravelry\.com/groups/([^"]+)"""")

// Ravelry appends "-2", "-3", etc. to a group's permalink when its base slug is already
// taken by another group (e.g. "kirkland-fiber-arts-circle-2"). That trailing digit isn't
// part of the group's actual name, so including it in a search query pollutes the match
// and can push small/niche groups out of the top page_size=25 results.
private val TRAILING_DISAMBIGUATOR_REGEX = Regex("""-\d+$""")

/**
 * Low-level HTTP client for the Ravelry API and Ravelry web scraping.
 *
 * All API calls use the Bearer token from [TokenStorage]. Group membership data is obtained
 * by scraping `www.ravelry.com` with the session cookie (also from [TokenStorage]), because
 * Ravelry exposes no API endpoint for listing a user's group memberships.
 *
 * @param httpClient Ktor client with JSON content negotiation configured.
 * @param tokenStorage Source of the Bearer token and session cookie.
 */
class RavelryApiClient(
    private val httpClient: HttpClient,
    private val tokenStorage: TokenStorage,
) {
    private suspend fun accessToken(): String =
        tokenStorage.load()?.accessToken ?: error("Not authenticated")

    private suspend fun sessionCookie(): String =
        tokenStorage.load()?.sessionCookie ?: error("No session cookie — re-login required")

    /**
     * Returns the currently authenticated Ravelry user.
     *
     * @return [RavelryUser] for the owner of the stored Bearer token.
     */
    suspend fun getCurrentUser(): RavelryUser {
        val raw = httpClient.get("$BASE_URL/current_user.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }.bodyAsText()
        return lenientJson.decodeFromString<CurrentUserResponse>(raw).user
    }

    /**
     * Returns the list of groups [username] is a member of.
     *
     * Scrapes `www.ravelry.com/people/{username}/groups/memberships` using the session cookie,
     * extracts group permalinks, then resolves each via a search call to get the [Group]
     * (including `forum_id`). The search calls are made in parallel.
     *
     * @param username Ravelry username whose memberships to fetch.
     * @return Groups the user belongs to. Groups not found via search are silently omitted.
     */
    suspend fun getUserGroups(username: String): List<Group> = coroutineScope {
        val html = httpClient.get("https://www.ravelry.com/people/$username/groups/memberships") {
            header(HttpHeaders.Cookie, sessionCookie())
            header(HttpHeaders.Accept, "text/html")
        }.bodyAsText()

        println("FiberSocial: MEMBERSHIPS_HTML_START")
        html.chunked(900).forEach { println("FiberSocial: $it") }
        println("FiberSocial: MEMBERSHIPS_HTML_END")

        val permalinks = GROUP_PERMALINK_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        println("FiberSocial: getUserGroups scraped ${permalinks.size} groups: $permalinks")

        permalinks.map { permalink ->
            async { getGroup(permalink) }
        }.awaitAll().filterNotNull()
    }

    /**
     * Resolves [permalink] to a [Group] by searching, trying progressively narrower
     * queries until a match is found. The full permalink-derived query misses for small
     * or local groups that rank low in Ravelry's search index (see [searchQueriesFor]).
     */
    private suspend fun getGroup(permalink: String): Group? {
        for (query in searchQueriesFor(permalink)) {
            val match = searchGroupByQuery(query, permalink)
            if (match != null) {
                println("FiberSocial: getGroup($permalink) -> found forum_id=${match.forumId} via query=\"$query\"")
                return match
            }
        }
        println("FiberSocial: getGroup($permalink) -> NOT FOUND")
        return null
    }

    /**
     * Builds an ordered list of search queries to try for [permalink], from most to
     * least specific: the full permalink, then the permalink with any trailing
     * Ravelry disambiguation number (e.g. "-2") stripped, then progressively fewer
     * leading words of that stripped form.
     */
    private fun searchQueriesFor(permalink: String): List<String> {
        val withoutSuffix = permalink.replace(TRAILING_DISAMBIGUATOR_REGEX, "")
        val words = withoutSuffix.split("-").filter { it.isNotEmpty() }
        return buildList {
            add(permalink.replace("-", " "))
            add(words.joinToString(" "))
            if (words.size > 3) add(words.take(3).joinToString(" "))
            if (words.size > 2) add(words.take(2).joinToString(" "))
        }.distinct()
    }

    private suspend fun searchGroupByQuery(query: String, permalink: String): Group? = try {
        val raw = httpClient.get("$BASE_URL/groups/search.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            url.parameters.apply {
                append("query", query)
                append("page_size", "25")
            }
        }.bodyAsText()
        lenientJson.decodeFromString<GroupsSearchResponse>(raw).groups
            .find { it.permalink == permalink }
    } catch (e: Exception) {
        println("FiberSocial: getGroup($permalink) query=\"$query\" error: ${e.message}")
        null
    }

    /**
     * Returns a page of topics for the given forum.
     *
     * @param forumId The `forum_id` from a [Group].
     * @param page 1-based page number.
     * @param pageSize Number of topics per page.
     * @return Topics ordered by most-recently-replied first (Ravelry default).
     */
    suspend fun getGroupTopics(forumId: Long, page: Int = 1, pageSize: Int = 25): List<Topic> {
        val raw = httpClient.get("$BASE_URL/forums/$forumId/topics.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            url.parameters.apply {
                append("page", page.toString())
                append("page_size", pageSize.toString())
            }
        }.bodyAsText()
        return lenientJson.decodeFromString<TopicsResponse>(raw).topics
    }

    /**
     * Returns all posts (replies) for a topic, ordered oldest-first.
     *
     * @param topicId Ravelry topic ID.
     */
    suspend fun getTopicPosts(topicId: Long): List<Post> {
        val raw = httpClient.get("$BASE_URL/topics/$topicId/posts.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }.bodyAsText()
        return lenientJson.decodeFromString<PostsResponse>(raw).posts
    }

    /**
     * Returns the full detail for a single topic, including [Topic.createdByUser] and [Topic.summary].
     *
     * @param topicId Ravelry topic ID.
     */
    suspend fun getTopicDetail(topicId: Long): Topic {
        val raw = httpClient.get("$BASE_URL/topics/$topicId.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }.bodyAsText()
        return lenientJson.decodeFromString<TopicDetailResponse>(raw).topic
    }

    @Serializable private data class PostsResponse(val posts: List<Post> = emptyList())
    @Serializable private data class CurrentUserResponse(val user: RavelryUser)
    @Serializable private data class GroupsSearchResponse(val groups: List<Group> = emptyList())
    @Serializable private data class TopicsResponse(
        val topics: List<Topic> = emptyList(),
        val paginator: Paginator? = null,
    )
    @Serializable private data class TopicDetailResponse(val topic: Topic)
    @Serializable private data class Paginator(
        val page: Int = 1,
        @SerialName("page_count") val pageCount: Int = 1,
        @SerialName("results") val totalResults: Int = 0,
    )
}
