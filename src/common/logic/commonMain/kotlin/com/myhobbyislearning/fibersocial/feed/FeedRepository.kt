package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.feed.models.Topic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

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

    // A group page fans out one getTopicDetail request per topic (up to ~25) with no cap,
    // so a single refresh on an image-heavy group could fire two dozen simultaneous
    // requests — rate-limit bait, and a burst of concurrent 401s races the token refresh.
    // EventSyncRunner already caps its own fan-out at 4 for the same reason; mirrored here.
    private val topicFetchConcurrency = Semaphore(4)

    /** @see RavelryApiClient.getCurrentUser */
    suspend fun getCurrentUser(): RavelryUser = apiClient.getCurrentUser()

    /** @see RavelryApiClient.getUserGroups */
    suspend fun getUserGroups(username: String): List<Group> =
        apiClient.getUserGroups(username)

    /** @see RavelryApiClient.joinGroup */
    suspend fun joinGroup(permalink: String) = apiClient.joinGroup(permalink)

    /** @see RavelryApiClient.leaveGroup */
    suspend fun leaveGroup(permalink: String) = apiClient.leaveGroup(permalink)

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

    /**
     * Fetches one page of the cross-group "My Posts" feed: topics the user has posted in,
     * across every group ([RavelryApiClient.getMyTopics]), newest-activity-first.
     *
     * Each topic's group attribution comes from matching the LIST entry's [Topic.forumId]
     * against [groups] — the detail response reports `forum_id` as 0 (see [Topic]), so the
     * list entry is the only place the forum is known. A topic whose forum matches none of
     * the user's groups (e.g. posted in a since-left group) keeps an empty group name
     * rather than being dropped — it's still the user's post.
     *
     * @param groups The user's groups, for forum-to-group attribution.
     * @param page 1-based page number.
     */
    suspend fun getMyPostsPage(groups: List<Group>, page: Int): FeedItemsPage = coroutineScope {
        val topicsPage = apiClient.getMyTopics(page = page)
        val groupsByForumId = groups.associateBy { it.forumId }
        val items = topicsPage.topics
            .map { topic ->
                // Same per-topic detail fetch as the group feed (summary/starter/read
                // marker), under the same concurrency cap. Unlike the single-group feed,
                // every topic here can belong to a different forum — including one the
                // user has since left, which 403s. Isolated per topic (runCatching, not
                // a bare fetch) so one inaccessible topic doesn't take the whole
                // cross-group page down via awaitAll's fail-fast/cancel-siblings
                // behavior; this is what actually makes the "keep unattributed rather
                // than dropped" design above true in practice, not just for topics whose
                // forum_id merely doesn't match a group.
                async {
                    topicFetchConcurrency.withPermit {
                        val group = groupsByForumId[topic.forumId]
                        runCatching {
                            apiClient.getTopicDetail(topic.id)
                                .toFeedItem(group?.id ?: 0L, group?.name.orEmpty())
                        }.onFailure {
                            println("FiberSocial: getMyPostsPage: skipping topic ${topic.id} (${it.message})")
                        }.getOrNull()
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            // No sticky-first here: sticky means "pinned in its own forum", which is
            // meaningless in a cross-group list — pure recency is the order that makes
            // sense for "what did I post in lately".
            .sortedByDescending { it.lastPostAt }
        FeedItemsPage(items = items, hasMore = topicsPage.hasMore)
    }

    private suspend fun CoroutineScope.fetchTopicsPage(group: Group, page: Int): FeedItemsPage {
        val topicsPage = apiClient.getGroupTopics(group.forumId, page = page)
        val items = topicsPage.topics
            .map { topic ->
                // The topic list omits the summary and starter, so fetch the detail per
                // topic for those (issue #185's card renders the summary in full). The
                // read marker (last_read/latest_reply) rides along on the detail too, so
                // no extra request is needed for the unread count.
                async {
                    topicFetchConcurrency.withPermit {
                        apiClient.getTopicDetail(topic.id).toFeedItem(group.id, group.name)
                    }
                }
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
            createdAt = createdAt,
        )
    }

}
