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
        val author = createdByUser ?: RavelryUser(username = "unknown")
        val preview = summary?.take(200) ?: ""
        return when {
            imagesCount > 0 -> FeedItem.ProjectPost(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = repliedAt,
                author = author,
                title = title,
                imageCount = imagesCount,
                imageUrls = emptyList(), // requires fetching posts; deferred to future phase
                replyCount = postsCount,
            )
            sticky -> FeedItem.AnnouncementPost(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = repliedAt,
                author = author,
                title = title,
                bodyPreview = preview,
                replyCount = postsCount,
            )
            else -> FeedItem.DiscussionPost(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = repliedAt,
                author = author,
                title = title,
                bodyPreview = preview,
                replyCount = postsCount,
            )
        }
    }
}
