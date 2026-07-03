package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import kotlinx.coroutines.CancellationException
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
                        // Only discussion cards render reply attribution (project/sticky
                        // topics map to other card types that discard it), and only topics
                        // with replies have a latest reply distinct from the opening post;
                        // skip the extra request otherwise.
                        val wantsLatestReply =
                            detail.postsCount > 1 && detail.imagesCount == 0 && !detail.sticky
                        val latestReply = if (wantsLatestReply) latestPostOrNull(detail.id) else null
                        detail.toFeedItem(groupId = group.id, groupName = group.name, latestReply = latestReply)
                    }
                }
                .awaitAll()
        }.sortedWith(
            // Sticky topics are pinned to the top of a group's forum on the website;
            // mirror that here, then newest-reply-first within each band (issue #78).
            compareByDescending<FeedItem> { it is FeedItem.AnnouncementTopic }
                .thenByDescending { it.lastPostAt },
        )
    }

    /**
     * Best-effort fetch of a topic's newest post. A failure here degrades the card to
     * opening-post attribution rather than failing the whole feed — except session
     * expiry, which must propagate so the auth flow can take over.
     */
    private suspend fun latestPostOrNull(topicId: Long): Post? = try {
        apiClient.getLatestPost(topicId)
    } catch (e: CancellationException) {
        throw e
    } catch (e: SessionExpiredException) {
        throw e
    } catch (e: Exception) {
        println("FiberSocial: latestPostOrNull($topicId) failed: ${e.message}")
        null
    }

    private fun Topic.toFeedItem(groupId: Long, groupName: String, latestReply: Post? = null): FeedItem {
        val attributableReply = latestReply?.takeIf { it.user != null }
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
                // Author and preview stand or fall together: a reply whose user is
                // missing must not show its text attributed to the opening poster.
                latestReplyAuthor = attributableReply?.user,
                latestReplyPreview = attributableReply?.let { htmlPreview(it.bodyHtml) },
            )
        }
    }

    /** Plain-text excerpt of a post's HTML body, matching the opening-post preview length. */
    private fun htmlPreview(bodyHtml: String): String =
        Ksoup.parse(bodyHtml).text().take(200)
}
