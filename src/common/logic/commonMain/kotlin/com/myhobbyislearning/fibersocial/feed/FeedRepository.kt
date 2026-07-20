package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.feed.models.FeedItem
import com.myhobbyislearning.fibersocial.feed.models.Group
import com.myhobbyislearning.fibersocial.feed.models.RavelryUser
import com.myhobbyislearning.fibersocial.feed.models.Topic
import com.myhobbyislearning.fibersocial.messages.MessageFolder
import kotlinx.coroutines.CancellationException
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
 * A lightweight signal for the drawer's indicators (unread dots): whether there is
 * anything worth looking at, not how much.
 *
 * The three dots deliberately mean different things (issue #350 part 3, issue #372):
 *
 * @property unreadGroupForumIds Forum ids ([Group.forumId]) of groups with **activity
 *   since the user last opened them** — see [resolveGroupDots]. Not a strict unread
 *   count: it is derived from each group's latest post time versus a locally stored
 *   last-viewed timestamp, because Ravelry's read markers only exist for topics the user
 *   has already opened (so a brand-new topic nobody has opened produced no dot at all)
 *   and could only be cleared by reading every unread topic in the group.
 * @property yourPostsHasUnread Whether any topic the user posted in has unread replies.
 *   This one IS read-marker based — the user has by definition opened those topics, so
 *   the marker exists — and is cross-group, so the last-viewed rule doesn't apply.
 * @property messagesHaveUnread Whether the message inbox holds anything unread (issue
 *   #372). The *easy* case, and deliberately modelled on [yourPostsHasUnread] rather than
 *   on the groups rule: Ravelry's message API carries a real per-message read flag
 *   ([com.myhobbyislearning.fibersocial.messages.Message.readMessage]) plus an
 *   `unread_only` filter, so the server already knows the answer and there is no local
 *   last-viewed state to invent. Reading a message is what clears it — see
 *   [FeedRepository.getMessagesUnread] for the narrow re-probe that notices.
 */
data class DrawerUnread(
    val unreadGroupForumIds: Set<Long> = emptySet(),
    val yourPostsHasUnread: Boolean = false,
    val messagesHaveUnread: Boolean = false,
)

/**
 * [FeedRepository.getDrawerUnread]'s result: the dots to show plus the last-viewed map to
 * persist (unseen groups get seeded, departed groups pruned — see [resolveGroupDots]).
 */
data class DrawerUnreadResult(
    val unread: DrawerUnread,
    val groupLastViewed: Map<Long, Long>,
)

// One page of most-recently-active posted-in topics is enough for a yes/no unread signal;
// 100 is Ravelry's cap for the filtered_topics endpoint.
private const val UNREAD_SCAN_PAGE_SIZE = 100

// Topics fetched per group when looking for its latest activity. Small on purpose: this
// is one request PER GROUP, and only the newest timestamp is wanted. It is deliberately
// not 1 — see getGroupActivity for why taking the max over a short page matters.
private const val GROUP_ACTIVITY_PAGE_SIZE = 10

// Messages requested by the inbox unread probe. ONE, deliberately — unlike the per-group
// leg above (where slot 1 can hold a stale pinned topic, so a page of 1 would lie), this
// asks a pure existence question of a server-side `unread_only` filter: any row at all
// means "unread mail exists". The inbox can be enormous and getDrawerUnread already costs
// 1 + groups.size requests, so it fetches the smallest page that can answer it.
private const val MESSAGES_UNREAD_PROBE_PAGE_SIZE = 1

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
     * Fetches the drawer's indicators (the unread dots) — see [DrawerUnread] for what the
     * two dots mean.
     *
     * Three kinds of leg, all run concurrently:
     *
     * - one page of the topics the user has *posted in*, for the "Posts" dot;
     * - a one-row `unread_only` inbox probe, for the "Messages" dot (issue #372);
     * - one small page of topics per group in [groups], for the per-group activity dots,
     *   compared against [groupLastViewed] as of [now].
     *
     * That makes this `2 + groups.size` requests, up from the 2 it used to be — the price
     * of dots that are both complete (a brand-new topic nobody has opened still counts)
     * and dismissible. The per-group fan-out shares the same concurrency cap as the feed's
     * detail fetches, and callers fire this on foreground activation and after a feed
     * load, NOT on every drawer open (issue #349's lazy design).
     *
     * @param groups The user's groups, in drawer order.
     * @param groupLastViewed Group id → epoch millis when the group was last opened.
     * @param now Current epoch millis, used to seed groups that have no entry yet.
     */
    suspend fun getDrawerUnread(
        groups: List<Group>,
        groupLastViewed: Map<Long, Long>,
        now: Long,
    ): DrawerUnreadResult = coroutineScope {
        val posting = async { getYourPostsUnread() }
        val messages = async { getMessagesUnreadOrNone() }
        val activity = async { getGroupActivity(groups) }
        val dots = resolveGroupDots(
            groups = groups,
            activity = activity.await(),
            lastViewed = groupLastViewed,
            now = now,
        )
        DrawerUnreadResult(
            unread = DrawerUnread(
                unreadGroupForumIds = dots.unreadGroupForumIds,
                yourPostsHasUnread = posting.await(),
                messagesHaveUnread = messages.await(),
            ),
            groupLastViewed = dots.lastViewed,
        )
    }

    /**
     * Whether any topic the user has posted in has replies past their read marker — the
     * "Posts" dot on its own, without the per-group fan-out. Exposed separately so
     * re-checking that dot after a read costs one request instead of a full
     * [getDrawerUnread] pass.
     */
    suspend fun getYourPostsUnread(): Boolean =
        apiClient.getMyTopics(pageSize = UNREAD_SCAN_PAGE_SIZE).topics.any { it.hasUnread }

    /**
     * Whether the message inbox holds anything unread — the "Messages" dot on its own,
     * without the rest of [getDrawerUnread] (issue #372).
     *
     * A pure existence probe: `unread_only` makes the server do the filtering, so any row
     * at all in the response means unread mail exists and
     * [MESSAGES_UNREAD_PROBE_PAGE_SIZE] rows are enough to find out. Exposed separately so
     * re-checking the dot after the user reads a message costs one request instead of a
     * full `2 + groups.size` pass — the message-side twin of [getYourPostsUnread].
     *
     * Throws on failure. [getDrawerUnread] softens that itself (see
     * [getMessagesUnreadOrNone]); the ViewModel's narrow re-probe catches it there.
     */
    suspend fun getMessagesUnread(): Boolean =
        apiClient
            .getMessages(
                folder = MessageFolder.INBOX,
                pageSize = MESSAGES_UNREAD_PROBE_PAGE_SIZE,
                unreadOnly = true,
            )
            .messages
            .isNotEmpty()

    /**
     * [getMessagesUnread] with the same soft-failure contract the per-group leg has: a
     * broken messages probe degrades to "no dot" rather than taking the Posts and group
     * dots down with it. The rethrow pair matches [getGroupActivity]'s, and for the same
     * reasons — a swallowed [SessionExpiredException] renders as "no mail" and hides an
     * expired session behind an innocent-looking drawer, and a swallowed
     * [CancellationException] breaks structured concurrency for the enclosing scope.
     */
    private suspend fun getMessagesUnreadOrNone(): Boolean =
        try {
            getMessagesUnread()
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("FiberSocial: getDrawerUnread: skipping the messages leg (${e.message})")
            false
        }

    /**
     * Latest activity per group: group id → the newest reply timestamp (epoch millis)
     * across a short page of the group's topics. Groups whose fetch failed, or where no
     * topic carried a parseable timestamp, are simply absent — the caller shows no dot for
     * them rather than guessing (a transient failure must not invent activity).
     *
     * **The maximum over a page, deliberately — not the first topic of a `pageSize = 1`
     * fetch.** [RavelryApiClient.getGroupTopics] relies on Ravelry's default ordering and
     * sends no explicit sort, and forums have pinned/sticky topics (issue #332), so the
     * first slot can hold a *pinned* thread whose last reply is months old while a genuinely
     * new reply sits below it. Taking the max over [GROUP_ACTIVITY_PAGE_SIZE] topics is
     * robust to both. Sticky topics counting toward the max is correct, not a leak: a reply
     * to a pinned thread is real new activity.
     *
     * Failures are isolated per group rather than left to `awaitAll`'s fail-fast — one
     * inaccessible group must not blank every other group's dot. Session expiry and
     * cancellation are the two exceptions and DO propagate; see the catch blocks for why
     * softening them would hide an expired session behind an innocent-looking empty drawer.
     */
    private suspend fun getGroupActivity(groups: List<Group>): Map<Long, Long> = coroutineScope {
        groups
            .map { group ->
                async {
                    topicFetchConcurrency.withPermit {
                        // A per-group failure is deliberately soft — one unreachable group
                        // shouldn't blank every other group's dot — but session expiry and
                        // cancellation must NOT be softened:
                        //
                        // - SessionExpiredException has to reach the caller so the app can
                        //   prompt a re-login. Swallowed here it renders as "no activity",
                        //   i.e. an expired session looks exactly like a quiet group. The
                        //   sibling getYourPostsUnread() leg usually throws on a concurrent
                        //   expiry, but it isn't guaranteed to be the one that sees it first.
                        //   Same rethrow-before-generic-catch shape as searchGroupByQuery.
                        // - CancellationException would otherwise be caught as an ordinary
                        //   failure, breaking structured concurrency for the enclosing scope.
                        try {
                            apiClient
                                .getGroupTopics(
                                    group.forumId,
                                    page = 1,
                                    pageSize = GROUP_ACTIVITY_PAGE_SIZE,
                                )
                                .topics
                                .mapNotNull {
                                    parseRavelryTimestamp(it.repliedAt)?.toEpochMilliseconds()
                                }
                                .maxOrNull()
                                ?.let { group.id to it }
                        } catch (e: SessionExpiredException) {
                            throw e
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            println(
                                "FiberSocial: getGroupActivity: skipping group ${group.id} (${e.message})",
                            )
                            null
                        }
                    }
                }
            }
            .awaitAll()
            .filterNotNull()
            .toMap()
    }

    /** A topic has unread posts when its post count is beyond the user's read marker. */
    private val Topic.hasUnread: Boolean get() = postsCount > lastRead

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
