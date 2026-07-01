package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.auth.TokenStorage
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.Topic
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://api.ravelry.com"
private val lenientJson = Json { ignoreUnknownKeys = true }

// Ravelry has no API endpoint for "groups this user is a member of".
// We scrape the memberships page on www.ravelry.com using the session cookie
// captured during WebView OAuth login, then resolve each group's forum_id via the API.
private val GROUP_PERMALINK_REGEX = Regex("""href="https://www\.ravelry\.com/groups/([^"]+)"""")

/**
 * Low-level HTTP client for the Ravelry API and Ravelry web scraping.
 *
 * All API calls use the Bearer token from [TokenStorage]. On a 401/403 response,
 * [refreshToken] is invoked (if provided) and the request is retried once. If the
 * refresh fails or [refreshToken] is null, [SessionExpiredException] is thrown.
 * Group membership data is obtained by scraping `www.ravelry.com` with the session
 * cookie (also from [TokenStorage]), because Ravelry exposes no API endpoint for
 * listing a user's group memberships.
 *
 * @param httpClient Ktor client with JSON content negotiation configured.
 * @param tokenStorage Source of the Bearer token and session cookie.
 * @param refreshToken Suspend callback that refreshes the access token and saves the
 *   new token to [tokenStorage]. Called on 401/403 responses and proactively when
 *   the token is within 60 seconds of expiry. Pass `null` to disable auto-refresh.
 */
class RavelryApiClient(
    private val httpClient: HttpClient,
    private val tokenStorage: TokenStorage,
    private val refreshToken: (suspend () -> Unit)? = null,
) {
    private suspend fun accessToken(): String =
        tokenStorage.load()?.accessToken ?: error("Not authenticated")

    private suspend fun sessionCookie(): String =
        tokenStorage.load()?.sessionCookie ?: error("No session cookie — re-login required")

    private suspend fun authenticatedRequest(block: suspend () -> HttpResponse): String {
        // Proactive: refresh if token expires within 60 seconds (best-effort; 401 path handles failures)
        tokenStorage.load()?.let { token ->
            val now = Clock.System.now().toEpochMilliseconds()
            if (token.refreshToken.isNotEmpty() && token.expiresAt - now < 60_000L) {
                runCatching { tryRefresh() }
            }
        }

        val response = block()
        if (response.status == HttpStatusCode.Unauthorized || response.status == HttpStatusCode.Forbidden) {
            tryRefresh()
            val retried = block()
            if (retried.status == HttpStatusCode.Unauthorized || retried.status == HttpStatusCode.Forbidden) {
                throw SessionExpiredException("Session expired")
            }
            return retried.bodyAsText()
        }
        return response.bodyAsText()
    }

    private suspend fun tryRefresh() {
        val doRefresh = refreshToken ?: throw SessionExpiredException("Session expired")
        try {
            doRefresh()
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            throw SessionExpiredException("Token refresh failed: ${e.message}")
        }
    }

    /**
     * Returns the currently authenticated Ravelry user.
     *
     * @return [RavelryUser] for the owner of the stored Bearer token.
     */
    suspend fun getCurrentUser(): RavelryUser {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/current_user.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            }
        }
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

    private suspend fun getGroup(permalink: String): Group? = try {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/groups/search.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                url.parameters.apply {
                    append("query", permalink.replace("-", " "))
                    append("page_size", "25")
                }
            }
        }
        val match = lenientJson.decodeFromString<GroupsSearchResponse>(raw).groups
            .find { it.permalink == permalink }
        println("FiberSocial: getGroup($permalink) -> ${if (match != null) "found forum_id=${match.forumId}" else "NOT FOUND"}")
        match
    } catch (e: SessionExpiredException) {
        throw e
    } catch (e: Exception) {
        println("FiberSocial: getGroup($permalink) error: ${e.message}")
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
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/forums/$forumId/topics.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                url.parameters.apply {
                    append("page", page.toString())
                    append("page_size", pageSize.toString())
                }
            }
        }
        return lenientJson.decodeFromString<TopicsResponse>(raw).topics
    }

    /**
     * Returns all posts (replies) for a topic, ordered oldest-first.
     *
     * @param topicId Ravelry topic ID.
     */
    suspend fun getTopicPosts(topicId: Long): List<Post> {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/topics/$topicId/posts.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            }
        }
        return lenientJson.decodeFromString<PostsResponse>(raw).posts
    }

    /**
     * Returns the full detail for a single topic, including [Topic.createdByUser] and [Topic.summary].
     *
     * @param topicId Ravelry topic ID.
     */
    suspend fun getTopicDetail(topicId: Long): Topic {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/topics/$topicId.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            }
        }
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
