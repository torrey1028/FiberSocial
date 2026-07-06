package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.SessionExpiredException
import kotlinx.coroutines.CancellationException
import com.autom8ed.fibersocial.feed.models.FeedItem
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.html.MarkdownPostParser
import com.autom8ed.fibersocial.feed.models.Topic
import com.fleeksoft.ksoup.Ksoup
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
                async {
                    val detail = apiClient.getTopicDetail(topic.id)
                    // Every card previews a real post body (issues #154/#185): the
                    // newest post is the latest reply when the topic has replies, and
                    // the opening post itself otherwise — Ravelry's topic summary is
                    // unreliable about formatting and never carries images. The one
                    // skip: sticky topics with replies. Sticky cards keep opening-post
                    // attribution (an announcement is its opening post, not its latest
                    // comment — mirrors the website; FeedItem's init enforces it), so
                    // their newest post would be fetched only to be discarded.
                    val wantsNewestPost = detail.postsCount <= 1 || !detail.sticky
                    val newestPost = if (wantsNewestPost) latestPostOrNull(detail.id) else null
                    detail.toFeedItem(groupId = group.id, groupName = group.name, newestPost = newestPost)
                }
            }
            .awaitAll()
            .sortedWith(
                compareByDescending<FeedItem> { it.sticky }
                    .thenByDescending { it.lastPostAt },
            )
        return FeedItemsPage(items = items, hasMore = topicsPage.hasMore)
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

    private fun Topic.toFeedItem(groupId: Long, groupName: String, newestPost: Post? = null): FeedItem {
        // With replies present the newest post is a reply; otherwise it IS the opening
        // post, which previews under opening-post attribution rather than as a "reply".
        val latestReply = newestPost?.takeIf { postsCount > 1 && !sticky }
        val openingPost = newestPost?.takeIf { postsCount <= 1 }
        val attributableReply = latestReply?.takeIf { it.user != null }
        val author = createdByUser ?: RavelryUser(username = "unknown")
        val full = summary ?: ""
        // The card shows plain text (issue #104). Prefer Ravelry's HTML rendering of the
        // summary — it resolves raw-Markdown damage (dangling emphasis from a dropped
        // closing `**`) the way the website does; the Markdown source is the fallback.
        val preview = summaryHtml?.takeIf { it.isNotBlank() }?.let { htmlPreview(it) }
            ?: MarkdownPostParser.plainText(full).take(200)
        // Whether posts carry images does not affect the card: a topic with a photo in
        // it is still a discussion (issue #77 — the old image-first ProjectTopic mapping
        // silently dropped nearly half of a real group's topics from the feed). Images
        // render in the topic detail view.
        return FeedItem(
            id = id,
            groupId = groupId,
            groupName = groupName,
            lastPostAt = repliedAt,
            author = author,
            title = title,
            bodyPreview = preview,
            bodySummary = full,
            bodySummaryHtml = summaryHtml.orEmpty(),
            replyCount = postsCount,
            sticky = sticky,
            // Author and preview stand or fall together: a reply whose user is
            // missing must not show its text attributed to the opening poster.
            latestReplyAuthor = attributableReply?.user,
            latestReplyPreview = attributableReply?.let { htmlPreview(it.bodyHtml) },
            latestReplyBody = attributableReply?.body,
            latestReplyHtml = attributableReply?.bodyHtml,
            openingPostBody = openingPost?.body.orEmpty(),
            openingPostHtml = openingPost?.bodyHtml.orEmpty(),
        )
    }

    /** Plain-text excerpt of a post's HTML body, matching the opening-post preview length. */
    private fun htmlPreview(bodyHtml: String): String =
        Ksoup.parse(bodyHtml).text().take(200)
}
