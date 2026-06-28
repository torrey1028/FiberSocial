package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.TokenStorage
import com.autom8ed.fibersocial.feed.models.Group
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

class RavelryApiClient(
    private val httpClient: HttpClient,
    private val tokenStorage: TokenStorage,
) {
    private suspend fun accessToken(): String =
        tokenStorage.load()?.accessToken ?: error("Not authenticated")

    private suspend fun sessionCookie(): String =
        tokenStorage.load()?.sessionCookie ?: error("No session cookie — re-login required")

    suspend fun getCurrentUser(): RavelryUser {
        val raw = httpClient.get("$BASE_URL/current_user.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }.bodyAsText()
        return lenientJson.decodeFromString<CurrentUserResponse>(raw).user
    }

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
        val raw = httpClient.get("$BASE_URL/groups/search.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            url.parameters.apply {
                append("query", permalink.replace("-", " "))
                append("page_size", "25")
            }
        }.bodyAsText()
        val match = lenientJson.decodeFromString<GroupsSearchResponse>(raw).groups
            .find { it.permalink == permalink }
        println("FiberSocial: getGroup($permalink) -> ${if (match != null) "found forum_id=${match.forumId}" else "NOT FOUND"}")
        match
    } catch (e: Exception) {
        println("FiberSocial: getGroup($permalink) error: ${e.message}")
        null
    }

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

    suspend fun getTopicDetail(topicId: Long): Topic {
        val raw = httpClient.get("$BASE_URL/topics/$topicId.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }.bodyAsText()
        return lenientJson.decodeFromString<TopicDetailResponse>(raw).topic
    }

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
