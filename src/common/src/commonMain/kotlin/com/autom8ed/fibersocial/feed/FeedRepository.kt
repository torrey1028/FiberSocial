package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.Topic
import com.fleeksoft.ksoup.Ksoup
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
                        // Only topics with replies have a "latest reply" distinct from the
                        // opening post; skip the extra request otherwise.
                        val latestReply = if (detail.postsCount > 1) latestPostOrNull(detail.id) else null
                        detail.toFeedItem(groupId = group.id, groupName = group.name, latestReply = latestReply)
                    }
                }
                .awaitAll()
        }.sortedByDescending { it.lastPostAt }
    }

    /**
     * Best-effort fetch of a topic's newest post. A failure here degrades the card to
     * opening-post attribution rather than failing the whole feed — except session
     * expiry, which must propagate so the auth flow can take over.
     */
    private suspend fun latestPostOrNull(topicId: Long): Post? = try {
        apiClient.getLatestPost(topicId)
    } catch (e: SessionExpiredException) {
        throw e
    } catch (e: Exception) {
        null
    }

    private fun Topic.toFeedItem(groupId: Long, groupName: String, latestReply: Post? = null): FeedItem {
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
                latestReplyAuthor = latestReply?.user,
                latestReplyPreview = latestReply?.let { htmlPreview(it.bodyHtml) },
            )
        }
    }

    /** Plain-text excerpt of a post's HTML body, matching the opening-post preview length. */
    private fun htmlPreview(bodyHtml: String): String =
        Ksoup.parse(bodyHtml).text().take(200)
}
