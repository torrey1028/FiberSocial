package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope

/**
 * One page of a single group's feed items, as returned by [FeedRepository.getFeedItemsPage].
 *
 * @property hasMore Whether requesting the next page would return further items.
 */
data class FeedItemsPage(
    val items: List<FeedItem>,
    val hasMore: Boolean,
)

/**
 * Translates raw Ravelry API data into [FeedItem]s ready for display.
 *
 * Orchestrates the multi-step fetch: groups → topic lists → topic details (in parallel),
 * then maps each [Topic] to a [FeedItem] (one card shape; `sticky` is a flag, not a type).
 *
 * @param apiClient Low-level Ravelry HTTP client.
 */
class FeedRepository(private val apiClient: RavelryApiClient) {

    /** @see RavelryApiClient.getCurrentUser */
    suspend fun getCurrentUser(): RavelryUser = apiClient.getCurrentUser()

    /** @see RavelryApiClient.getUserGroups */
    suspend fun getUserGroups(username: String): List<Group> =
        apiClient.getUserGroups(username)

    /** @see RavelryApiClient.joinGroup */
    suspend fun joinGroup(permalink: String) = apiClient.joinGroup(permalink)

    /**
     * Fetches one page of [group]'s topics (issue #106 — infinite scroll). The feed only
     * ever pages through whichever single group is currently selected.
     *
     * @param page 1-based page number.
     * @return This page's items (already sorted sticky-first, newest-reply-first) plus
     *   whether a further page remains.
     */
    suspend fun getFeedItemsPage(group: Group, page: Int): FeedItemsPage = coroutineScope {
        fetchTopicsPage(group, page)
    }

    private suspend fun CoroutineScope.fetchTopicsPage(group: Group, page: Int): FeedItemsPage {
        val topicsPage = apiClient.getGroupTopics(group.forumId, page = page)
        val items = topicsPage.topics
            .map { topic ->
                // The topic list omits the summary and starter, so fetch the detail per
                // topic for those (issue #185's card renders the summary in full). The
                // read marker (last_read/latest_reply) rides along on the detail too, so
                // no extra request is needed for the unread count.
                async { apiClient.getTopicDetail(topic.id).toFeedItem(group.id, group.name) }
            }
            .awaitAll()
            .sortedWith(
                compareByDescending<FeedItem> { it.sticky }
                    .thenByDescending { it.lastPostAt },
            )
        return FeedItemsPage(items = items, hasMore = topicsPage.hasMore)
    }

    private fun Topic.toFeedItem(groupId: Long, groupName: String): FeedItem {
        // The card attributes to the topic's starter (issue #185), not the latest replier.
        val author = createdByUser ?: RavelryUser(username = "unknown")
        // Unread from Ravelry's own read marker: posts numbered (lastRead, postsCount]
        // are unread, and the first of them is where opening the topic should land. A
        // topic never read (lastRead 0) counts all posts as unread. postsCount is the
        // latest post number (Ravelry's latest_reply field comes back 0 in practice, so
        // the total-count is the reliable upper bound). Guarded so a marker transiently
        // ahead of the count never yields a negative.
        val unread = (postsCount - lastRead).coerceAtLeast(0)
        val firstUnread = if (unread > 0) lastRead + 1 else null
        return FeedItem(
            id = id,
            groupId = groupId,
            groupName = groupName,
            lastPostAt = repliedAt,
            author = author,
            title = title,
            // Total posts, including the opening post, so it matches unreadCount which
            // counts from post 1 (issue #185 walkthrough).
            postCount = postsCount,
            bodySummary = summary.orEmpty(),
            bodySummaryHtml = summaryHtml.orEmpty(),
            unreadCount = unread,
            firstUnreadPostNumber = firstUnread,
            sticky = sticky,
        )
    }

}
