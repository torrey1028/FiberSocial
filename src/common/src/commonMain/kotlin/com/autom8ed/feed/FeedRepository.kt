package com.autom8ed.feed

import com.autom8ed.feed.models.FeedItem
import com.autom8ed.feed.models.Group
import com.autom8ed.feed.models.RavelryUser
import com.autom8ed.feed.models.Topic

class FeedRepository(private val apiClient: RavelryApiClient) {

    suspend fun getCurrentUser(): RavelryUser = apiClient.getCurrentUser()

    suspend fun getUserGroups(username: String): List<Group> =
        apiClient.getUserGroups(username)

    suspend fun getFeedItems(groups: List<Group>): List<FeedItem> =
        groups.flatMap { group ->
            apiClient.getGroupTopics(group.permalink).map { topic ->
                topic.toFeedItem(groupId = group.id, groupName = group.name)
            }
        }.sortedByDescending { it.lastPostAt }

    private fun Topic.toFeedItem(groupId: Long, groupName: String): FeedItem {
        val author = firstPoster ?: lastPoster
        return if (coverImageUrl != null && postsCount > 1) {
            // Topics with images and replies → treat as project showcase
            FeedItem.ProjectPost(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = lastPostAt,
                author = author!!,
                title = title,
                imageUrls = listOf(coverImageUrl),
                replyCount = postsCount,
            )
        } else {
            // Text-heavy topics → treat as event/announcement
            FeedItem.EventPost(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = lastPostAt,
                author = author!!,
                title = title,
                coverImageUrl = coverImageUrl,
                bodyPreview = "",
                replyCount = postsCount,
            )
        }
    }
}
