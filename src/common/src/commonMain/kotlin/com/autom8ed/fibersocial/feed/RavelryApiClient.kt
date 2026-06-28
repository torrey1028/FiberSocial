package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.TokenStorage
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.Topic
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.http.HttpHeaders
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

private const val BASE_URL = "https://api.ravelry.com"

class RavelryApiClient(
    private val httpClient: HttpClient,
    private val tokenStorage: TokenStorage,
) {
    private suspend fun accessToken(): String =
        tokenStorage.load()?.accessToken ?: error("Not authenticated")

    suspend fun getCurrentUser(): RavelryUser =
        httpClient.get("$BASE_URL/current_user.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }.body<CurrentUserResponse>().user

    // Confirmed endpoint: /groups/search.json?username={username}
    suspend fun getUserGroups(username: String): List<Group> =
        httpClient.get("$BASE_URL/groups/search.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            url.parameters.append("username", username)
        }.body<GroupsResponse>().groups

    // Docs: /forums/{forum_id}/topics.json — takes the numeric forum_id from the Group
    suspend fun getGroupTopics(forumId: Long, page: Int = 1, pageSize: Int = 25): List<Topic> =
        httpClient.get("$BASE_URL/forums/$forumId/topics.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            url.parameters.apply {
                append("page", page.toString())
                append("page_size", pageSize.toString())
            }
        }.body<TopicsResponse>().topics

    // Detail endpoint adds created_by_user and summary (not in list response)
    suspend fun getTopicDetail(topicId: Long): Topic =
        httpClient.get("$BASE_URL/topics/$topicId.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }.body<TopicDetailResponse>().topic

    @Serializable private data class CurrentUserResponse(val user: RavelryUser)
    @Serializable private data class GroupsResponse(val groups: List<Group> = emptyList())
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
