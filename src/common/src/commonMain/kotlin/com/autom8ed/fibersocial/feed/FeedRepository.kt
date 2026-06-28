package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.Topic
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * Translates raw Ravelry API data into [FeedItem]s ready for display.
 *
 * Orchestrates the multi-step fetch: groups → topic lists → topic details (in parallel),
 * then maps each [Topic] to a typed [FeedItem] subclass based on its fields.
 *
 * @param apiClient Low-level Ravelry HTTP client.
 */
class FeedRepository(private val apiClient: RavelryApiClient) {

    /** @see RavelryApiClient.getCurrentUser */
    suspend fun getCurrentUser(): RavelryUser = apiClient.getCurrentUser()

    /** @see RavelryApiClient.getUserGroups */
    suspend fun getUserGroups(username: String): List<Group> =
        apiClient.getUserGroups(username)

    /**
     * Fetches and merges feed items across all [groups].
     *
     * For each group, loads the topic list then resolves details in parallel (for author
     * and body preview). The combined list is sorted newest-reply-first.
     *
     * @param groups Groups whose forums should be included in the feed.
     * @return Merged, sorted list of [FeedItem]s.
     */
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
        val full = summary ?: ""
        val preview = full.take(200)
        return when {
            imagesCount > 0 -> FeedItem.ProjectTopic(
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
            sticky -> FeedItem.AnnouncementTopic(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = repliedAt,
                author = author,
                title = title,
                bodyPreview = preview,
                bodySummary = full,
                replyCount = postsCount,
            )
            else -> FeedItem.DiscussionTopic(
                id = id,
                groupId = groupId,
                groupName = groupName,
                lastPostAt = repliedAt,
                author = author,
                title = title,
                bodyPreview = preview,
                bodySummary = full,
                replyCount = postsCount,
            )
        }
    }
}
