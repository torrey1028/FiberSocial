package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.Topic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

class FeedRepository(private val apiClient: RavelryApiClient) {

    suspend fun getCurrentUser(): RavelryUser = apiClient.getCurrentUser()

    suspend fun getUserGroups(username: String): List<Group> =
        apiClient.getUserGroups(username)

    // Fetches topic list per group then resolves details in parallel for author + summary.
    suspend fun getFeedItems(groups: List<Group>): List<FeedItem> = coroutineScope {
        groups.flatMap { group ->
            apiClient.getGroupTopics(group.forumId)
                .map { topic ->
                    async {
                        val detail = apiClient.getTopicDetail(topic.id)
                        detail.toFeedItem(groupId = group.id, groupName = group.name)
                    }
                }
                .awaitAll()
        }.sortedByDescending { it.lastPostAt }
    }

    private fun Topic.toFeedItem(groupId: Long, groupName: String): FeedItem {
        return if (imagesCount > 0) {
            FeedItem.ProjectPost(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = repliedAt,
                author = createdByUser ?: RavelryUser(username = "unknown"),
                title = title,
                imageUrls = emptyList(), // actual image URLs require fetching posts; Phase 3
                imageCount = imagesCount,
                replyCount = postsCount,
            )
        } else {
            FeedItem.EventPost(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = repliedAt,
                author = createdByUser ?: RavelryUser(username = "unknown"),
                title = title,
                coverImageUrl = null,
                bodyPreview = summary?.take(200) ?: "",
                replyCount = postsCount,
            )
        }
    }
}
