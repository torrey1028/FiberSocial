package com.autom8ed.feed

import com.autom8ed.auth.TokenStorage
import com.autom8ed.feed.models.Group
import com.autom8ed.feed.models.RavelryUser
import com.autom8ed.feed.models.Topic
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

    suspend fun getUserGroups(username: String): List<Group> =
        httpClient.get("$BASE_URL/people/$username/groups.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }.body<GroupsResponse>().groups

    suspend fun getGroupTopics(permalink: String, page: Int = 1, pageSize: Int = 25): List<Topic> =
        httpClient.get("$BASE_URL/groups/$permalink/topics.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            url.parameters.apply {
                append("page", page.toString())
                append("page_size", pageSize.toString())
                append("sort", "updated")
            }
        }.body<TopicsResponse>().topics

    // Response wrappers — field names to be verified against live API
    @Serializable private data class CurrentUserResponse(val user: RavelryUser)
    @Serializable private data class GroupsResponse(val groups: List<Group> = emptyList())
    @Serializable private data class TopicsResponse(
        val topics: List<Topic> = emptyList(),
        @SerialName("paginator") val paginator: Paginator? = null,
    )
    @Serializable private data class Paginator(
        val page: Int = 1,
        @SerialName("page_count") val pageCount: Int = 1,
        @SerialName("results") val totalResults: Int = 0,
    )
}
