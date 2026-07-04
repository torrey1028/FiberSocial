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
                        // Sticky topics deliberately keep opening-post attribution (an
                        // announcement is its opening post, not its latest comment — this
                        // mirrors the website; FeedItem's init enforces it), and only
                        // topics with replies have a latest reply distinct from the
                        // opening post; skip the extra request otherwise. The !sticky
                        // condition is load-bearing, not a leftover from the old card split.
                        val wantsLatestReply = detail.postsCount > 1 && !detail.sticky
                        val latestReply = if (wantsLatestReply) latestPostOrNull(detail.id) else null
                        detail.toFeedItem(groupId = group.id, groupName = group.name, latestReply = latestReply)
                    }
                }
                .awaitAll()
        }.sortedWith(
            // Sticky topics are pinned to the top of a group's forum on the website;
            // mirror that here, then newest-reply-first within each band (issue #78).
            compareByDescending<FeedItem> { it.sticky }
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
        // Ravelry's `summary` field is documented as plain text but is actually a
        // truncated excerpt of the *raw Markdown source*, confirmed against a live post
        // whose preview showed "**bold**" and "[label][ref]" leaking straight through
        // (issue #104) — passing it through htmlPreview() alone did nothing, since
        // there's no HTML for Ksoup to strip. Strip Markdown syntax first (over the full,
        // untruncated text, so a closing token past the 200-char window still matches),
        // then run the result through htmlPreview() for entity decoding and truncation.
        val preview = htmlPreview(stripMarkdownSyntax(full))
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
            replyCount = postsCount,
            sticky = sticky,
            // Author and preview stand or fall together: a reply whose user is
            // missing must not show its text attributed to the opening poster.
            latestReplyAuthor = attributableReply?.user,
            latestReplyPreview = attributableReply?.let { htmlPreview(it.bodyHtml) },
        )
    }

    /**
     * Plain-text excerpt of an HTML (or HTML-ish) body, used for both the opening-post
     * preview and the latest-reply preview so raw tags/entities never reach the feed UI.
     *
     * Deliberately a single parse pass: re-parsing the already-decoded text to catch
     * entity-escaped markup (e.g. `&lt;b&gt;`) was tried and reverted — it can't be
     * distinguished from legitimate text that uses entities to display literal angle
     * brackets (e.g. `"5&lt;total&gt;10"`), and re-parsing that corrupts it (verified:
     * `Ksoup.parse("5<total>10").text()` returns `"510"`, silently dropping "total").
     * A cosmetic leak of decoded tag text is a smaller problem than silent data loss.
     */
    private fun htmlPreview(bodyHtml: String): String =
        Ksoup.parse(bodyHtml).text().take(200)

    /**
     * Strips common Markdown syntax down to its visible text, for [Topic.summary] excerpts
     * that turn out to be raw Markdown source rather than HTML (see caller). This is a
     * stripper, not a renderer: emphasis markers are dropped rather than reproduced as
     * bold/italic, and links/images collapse to their label text.
     *
     * Order matters: reference-style link *definitions* (whole lines like `[1]: url`) are
     * dropped first since they carry no visible content of their own; links/images resolve
     * to their label before emphasis stripping runs, so a bold link label still gets
     * cleaned; emphasis runs last since a label can itself contain `**`.
     *
     * [Topic.summary] is Ravelry's own excerpt, truncated server-side — confirmed on-device
     * that this sometimes cuts a post off before its closing `**` ever arrives, leaving an
     * unpaired opening marker no regex can validly match as emphasis. [MARKDOWN_STRAY_MARKER]
     * is a final unconditional cleanup pass for exactly that case: any emphasis-looking
     * punctuation left over after paired stripping can't be rendered as formatting anyway
     * (its pair was never received), so it's dropped as visual noise rather than shown raw.
     */
    private fun stripMarkdownSyntax(text: String): String {
        var result = MARKDOWN_REFERENCE_DEFINITION.replace(text, "")
        result = MARKDOWN_LINK_OR_IMAGE.replace(result) { match ->
            match.groupValues[1].ifEmpty { match.groupValues[2] }
        }
        result = MARKDOWN_INLINE_CODE.replace(result) { it.groupValues[1] }
        result = MARKDOWN_HEADING.replace(result, "")
        result = MARKDOWN_EMPHASIS.replace(result) { it.groupValues[2] }
        return MARKDOWN_STRAY_MARKER.replace(result, "")
    }

    companion object {
        private val MARKDOWN_REFERENCE_DEFINITION = Regex("""(?m)^\s*\[[^]]+]:\s*\S.*$""")
        private val MARKDOWN_LINK_OR_IMAGE = Regex("""!?\[([^]]*)]\([^)]*\)|!?\[([^]]*)]\[[^]]*]""")
        private val MARKDOWN_INLINE_CODE = Regex("""`([^`]*)`""")
        private val MARKDOWN_HEADING = Regex("""(?m)^#{1,6}\s*""")
        private val MARKDOWN_EMPHASIS = Regex("""(\*\*\*|___|\*\*|__|\*|_|~~)(.+?)\1""")
        private val MARKDOWN_STRAY_MARKER = Regex("""\*{1,3}|_{1,3}|~~""")
    }
}
