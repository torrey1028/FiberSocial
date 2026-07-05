package com.autom8ed.fibersocial.feed

import com.autom8ed.fibersocial.auth.ForbiddenException
import com.autom8ed.fibersocial.auth.SessionExpiredException
import com.fleeksoft.ksoup.Ksoup
import com.autom8ed.fibersocial.auth.TokenStorage
import com.autom8ed.fibersocial.events.EventAttendee
import com.autom8ed.fibersocial.events.EventDetail
import com.autom8ed.fibersocial.events.EventPageParser
import com.autom8ed.fibersocial.events.EventPeopleParser
import com.autom8ed.fibersocial.events.EventSummary
import com.autom8ed.fibersocial.events.GroupEventsParser
import com.autom8ed.fibersocial.events.SavedEvent
import com.autom8ed.fibersocial.events.SavedEventsParser
import com.autom8ed.fibersocial.feed.models.Group
import com.autom8ed.fibersocial.feed.models.Post
import com.autom8ed.fibersocial.feed.models.RavelryUser
import com.autom8ed.fibersocial.feed.models.Topic
import com.autom8ed.fibersocial.feed.models.VoteType
import io.ktor.client.HttpClient
import io.ktor.client.request.forms.FormDataContent
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.request
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Parameters
import io.ktor.http.Url
import io.ktor.http.isSuccess
import io.ktor.http.parameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Clock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val BASE_URL = "https://api.ravelry.com"
private const val WWW_URL = "https://www.ravelry.com"

/**
 * Topics requested per [RavelryApiClient.getGroupTopics] page. Tunable knob for feed
 * responsiveness (issue #106) — a smaller value returns the first screenful faster, a
 * larger one means fewer round-trips while the user scrolls.
 */
const val DEFAULT_FEED_PAGE_SIZE = 25
// coerceInputValues: a defensive safety net for when Ravelry returns an explicit JSON null
// for a field our model declares non-nullable-with-default. kotlinx.serialization applies a
// field default only when the key is ABSENT — an explicit null otherwise throws — so this
// coerces such nulls back to the default. (The known offender, forum_post.editable, is now
// modelled as nullable because its null carries meaning; see Post.editable. This flag stays
// on to guard the remaining non-null fields, e.g. a null body_html/body.)
private val lenientJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }

// Ravelry has no API endpoint for "groups this user is a member of".
// We scrape the memberships page on www.ravelry.com using the session cookie
// captured during WebView OAuth login, then resolve each group's forum_id via the API.
private val GROUP_PERMALINK_REGEX = Regex("""href="https://www\.ravelry\.com/groups/([^"]+)"""")

// Ravelry appends "-2", "-3", etc. to a group's permalink when its base slug is already
// taken by another group (e.g. "kirkland-fiber-arts-circle-2"). That trailing digit isn't
// part of the group's actual name, so including it in a search query pollutes the match
// and can push small/niche groups out of the top page_size=25 results.
private val TRAILING_DISAMBIGUATOR_REGEX = Regex("""-\d+$""")

/**
 * Low-level HTTP client for the Ravelry API and Ravelry web scraping.
 *
 * All API calls use the Bearer token from [TokenStorage]. On a 401/403 response,
 * [refreshToken] is invoked (if provided) and the request is retried once. If the
 * refresh fails or [refreshToken] is null, [SessionExpiredException] is thrown.
 * Group membership data is obtained by scraping `www.ravelry.com` with the session
 * cookie (also from [TokenStorage]), because Ravelry exposes no API endpoint for
 * listing a user's group memberships.
 *
 * @param httpClient Ktor client with JSON content negotiation configured.
 * @param tokenStorage Source of the Bearer token and session cookie.
 * @param refreshToken Suspend callback that refreshes the access token and saves the
 *   new token to [tokenStorage]. Called on 401 responses and proactively when
 *   the token is within 60 seconds of expiry. Pass `null` to disable auto-refresh.
 *   403 responses are never treated as an expired session — a valid token without
 *   permission surfaces as [ForbiddenException] instead.
 */
class RavelryApiClient(
    private val httpClient: HttpClient,
    private val tokenStorage: TokenStorage,
    private val refreshToken: (suspend () -> Unit)? = null,
) {
    private suspend fun accessToken(): String =
        tokenStorage.load()?.accessToken ?: error("Not authenticated")

    private suspend fun sessionCookie(): String =
        tokenStorage.load()?.sessionCookie ?: error("No session cookie — re-login required")

    private suspend fun authenticatedRequest(block: suspend () -> HttpResponse): String {
        // Proactive: refresh if token expires within 60 seconds (best-effort; 401 path handles failures)
        tokenStorage.load()?.let { token ->
            val now = Clock.System.now().toEpochMilliseconds()
            if (token.refreshToken.isNotEmpty() && token.expiresAt - now < 60_000L) {
                runCatching { tryRefresh() }
            }
        }

        val response = block()
        // 403 means the token is valid but lacks permission (e.g. missing OAuth scope,
        // moderator-only action). Refreshing or re-logging-in cannot fix it, so it must
        // not be classified as session expiry (issue #82).
        if (response.status == HttpStatusCode.Forbidden) {
            throw ForbiddenException(forbiddenMessage(response))
        }
        if (response.status == HttpStatusCode.Unauthorized) {
            tryRefresh()
            val retried = block()
            if (retried.status == HttpStatusCode.Unauthorized) {
                throw SessionExpiredException("Session expired")
            }
            if (retried.status == HttpStatusCode.Forbidden) {
                throw ForbiddenException(forbiddenMessage(retried))
            }
            return retried.bodyAsText()
        }
        return response.bodyAsText()
    }

    // Deliberately avoids the literal status code: parts of the UI pattern-match
    // "401"/"403" in error messages to detect expired sessions.
    private fun forbiddenMessage(response: HttpResponse): String =
        "Permission denied for ${response.request.url.encodedPath}"

    private suspend fun tryRefresh() {
        val doRefresh = refreshToken ?: throw SessionExpiredException("Session expired")
        try {
            doRefresh()
        } catch (e: SessionExpiredException) {
            throw e
        } catch (e: Exception) {
            throw SessionExpiredException("Token refresh failed: ${e.message}")
        }
    }

    /**
     * Returns the currently authenticated Ravelry user.
     *
     * @return [RavelryUser] for the owner of the stored Bearer token.
     */
    suspend fun getCurrentUser(): RavelryUser {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/current_user.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            }
        }
        return lenientJson.decodeFromString<CurrentUserResponse>(raw).user
    }

    /**
     * Returns the list of groups [username] is a member of.
     *
     * Scrapes `www.ravelry.com/people/{username}/groups/memberships` using the session cookie,
     * extracts group permalinks, then resolves each via a search call to get the [Group]
     * (including `forum_id`). The search calls are made in parallel.
     *
     * @param username Ravelry username whose memberships to fetch.
     * @return Groups the user belongs to. Groups not found via search are silently omitted.
     */
    suspend fun getUserGroups(username: String): List<Group> = coroutineScope {
        val html = httpClient.get("https://www.ravelry.com/people/$username/groups/memberships") {
            header(HttpHeaders.Cookie, sessionCookie())
            header(HttpHeaders.Accept, "text/html")
        }.bodyAsText()

        val permalinks = GROUP_PERMALINK_REGEX.findAll(html)
            .map { it.groupValues[1] }
            .distinct()
            .toList()

        println("FiberSocial: getUserGroups scraped ${permalinks.size} groups: $permalinks")

        permalinks.map { permalink ->
            async { getGroup(permalink) }
        }.awaitAll().filterNotNull()
    }

    /**
     * Returns the upcoming events listed on a group's page.
     *
     * Ravelry has no events API, so this scrapes `www.ravelry.com/groups/{permalink}`
     * with the session cookie and parses the "upcoming events" box (see
     * [GroupEventsParser]). Groups without the box yield an empty list.
     *
     * @param groupPermalink The group's permalink, e.g. `kirkland-fiber-arts-circle-2`.
     * @throws ForbiddenException on 403 — valid session, but no permission for this page.
     * @throws SessionExpiredException if the session cookie is rejected (401, or a
     *   redirect off the group page — Ravelry sends expired sessions to the login page).
     * @throws IllegalStateException on any other non-2xx response.
     */
    suspend fun getGroupEvents(groupPermalink: String): List<EventSummary> {
        val html = scrapeHtml("https://www.ravelry.com/groups/$groupPermalink", "/groups/",
            "Group page for $groupPermalink")
        val events = GroupEventsParser.parse(html)
        println("FiberSocial: getGroupEvents($groupPermalink) -> ${events.size} events")
        return events
    }

    /**
     * Returns the full details of an event, or null when [eventPermalink] doesn't
     * resolve to an event page.
     *
     * Ravelry has no events API, so this scrapes `www.ravelry.com/events/{permalink}`
     * with the session cookie (see [EventPageParser]).
     *
     * @param eventPermalink The event's slug, e.g. `wednesday-hh-at-chainline-39`
     *   (from [EventSummary.permalink]).
     * @throws ForbiddenException on 403 — valid session, but no permission for this page.
     * @throws SessionExpiredException if the session cookie is rejected (401, or a
     *   redirect off the event page — Ravelry sends expired sessions to the login page).
     * @throws IllegalStateException on any other non-2xx response.
     */
    suspend fun getEvent(eventPermalink: String): EventDetail? {
        val html = scrapeHtml("https://www.ravelry.com/events/$eventPermalink", "/events/",
            "Event page for $eventPermalink")
        val detail = EventPageParser.parse(html)
        println("FiberSocial: getEvent($eventPermalink) -> ${detail?.title ?: "NOT AN EVENT PAGE"}")
        return detail
    }

    /**
     * Returns the people attending an event, in the order the people page lists them.
     *
     * Ravelry has no events API, so this scrapes
     * `www.ravelry.com/events/{permalink}/people` with the session cookie (see
     * [EventPeopleParser]). An event with no attendees yields an empty list.
     *
     * @throws ForbiddenException on 403 — valid session, but no permission for this page.
     * @throws SessionExpiredException if the session cookie is rejected (401, or a
     *   redirect off the events path — Ravelry sends expired sessions to the login page).
     * @throws IllegalStateException on any other non-2xx response.
     */
    suspend fun getEventAttendees(eventPermalink: String): List<EventAttendee> {
        val html = scrapeHtml("https://www.ravelry.com/events/$eventPermalink/people", "/events/",
            "Event people page for $eventPermalink")
        val attendees = EventPeopleParser.parse(html)
        println("FiberSocial: getEventAttendees($eventPermalink) -> ${attendees.size} attendees")
        return attendees
    }

    /**
     * Returns the events the current user has saved (RSVP'd to), in the order the
     * "My Saved Events" page lists them.
     *
     * Ravelry has no events API, so this scrapes `www.ravelry.com/events/saved` with
     * the session cookie (see [SavedEventsParser]). The listing carries dates but no
     * times — call [getEvent] for a saved event's exact start time. It may include
     * past events and repeats recurring events once per occurrence; filtering is the
     * consumer's job.
     *
     * @throws ForbiddenException on 403 — valid session, but no permission for this page.
     * @throws SessionExpiredException per [scrapeHtml].
     */
    suspend fun getSavedEvents(): List<SavedEvent> {
        // Exact prefix, unlike the permalink scrapers: there is no canonicalization
        // to tolerate for this fixed page, and a session-limited redirect to another
        // /events/* page (search, landing) renders similar markup that would
        // otherwise parse as a bogus RSVP list.
        val html = scrapeHtml("https://www.ravelry.com/events/saved", "/events/saved",
            "Saved events page")
        val saved = SavedEventsParser.parse(html)
        println("FiberSocial: getSavedEvents -> ${saved.size} saved events")
        return saved
    }

    /**
     * Fetches a `www.ravelry.com` page with the session cookie, failing loudly when the
     * response is not an authenticated 200 — otherwise auth failures would be
     * indistinguishable from a page that merely lacks the scraped markup.
     *
     * @throws ForbiddenException on 403 — the session is valid but lacks permission for
     *   this page (e.g. a members-only group). Re-authenticating can't fix it, so callers
     *   must surface an error instead of bouncing the user to login (issue #82).
     * @throws SessionExpiredException on 401, or when the (Ktor-followed) redirect chain
     *   lands outside [expectedPathPrefix] — an expired session cookie surfaces as a 302
     *   to the login page, not an error status. Redirects within the prefix (permalink
     *   canonicalization) are fine.
     * @throws IllegalStateException on any other non-2xx response.
     */
    private suspend fun scrapeHtml(url: String, expectedPathPrefix: String, what: String): String {
        val response = httpClient.get(url) {
            header(HttpHeaders.Cookie, sessionCookie())
            header(HttpHeaders.Accept, "text/html")
        }
        when {
            // 403 means the cookie is valid but this page is off-limits (permission), not
            // an expired session — distinguish it so it doesn't force a needless re-login.
            // forbiddenMessage() deliberately omits the literal status code: FeedErrorState
            // pattern-matches "401"/"403" in error text to detect expired sessions, and a
            // message containing "403" here would make this route back to that same
            // session-expired UI text despite being classified as ForbiddenException.
            response.status == HttpStatusCode.Forbidden ->
                throw ForbiddenException(forbiddenMessage(response))
            response.status == HttpStatusCode.Unauthorized ->
                throw SessionExpiredException("$what returned ${response.status}")
            !response.request.url.encodedPath.startsWith(expectedPathPrefix) ->
                throw SessionExpiredException(
                    "$what redirected to ${response.request.url.encodedPath}"
                )
            !response.status.isSuccess() ->
                error("$what returned ${response.status}")
        }
        return response.bodyAsText()
    }

    /**
     * Saves or un-saves an event on the current user's calendar (Ravelry's RSVP).
     *
     * There is no events API: this posts to the website's own endpoints
     * (`/events/{permalink}/attend?attending=1` or `/events/{permalink}/unattend`),
     * authenticated by the session cookie plus the page's Rails authenticity token
     * (see [EventDetail.csrfToken]).
     *
     * @param eventPermalink The event's slug.
     * @param attending `true` to save the event, `false` to remove it.
     * @param csrfToken Authenticity token scraped from the event page.
     * @return `true` when the site accepted the change.
     */
    suspend fun setEventAttendance(
        eventPermalink: String,
        attending: Boolean,
        csrfToken: String,
    ): Boolean {
        val url = if (attending) {
            "https://www.ravelry.com/events/$eventPermalink/attend?attending=1"
        } else {
            "https://www.ravelry.com/events/$eventPermalink/unattend"
        }
        val response = httpClient.post(url) {
            header(HttpHeaders.Cookie, sessionCookie())
            setBody(FormDataContent(Parameters.build { append("authenticity_token", csrfToken) }))
        }
        println("FiberSocial: setEventAttendance($eventPermalink, attending=$attending) -> ${response.status}")
        return response.status == HttpStatusCode.OK
    }

    /**
     * Resolves [permalink] to a [Group] by searching, trying progressively narrower
     * queries until a match is found. The full permalink-derived query misses for small
     * or local groups that rank low in Ravelry's search index (see [searchQueriesFor]).
     */
    private suspend fun getGroup(permalink: String): Group? {
        for (query in searchQueriesFor(permalink)) {
            val match = searchGroupByQuery(query, permalink)
            if (match != null) {
                println("FiberSocial: getGroup($permalink) -> found forum_id=${match.forumId} via query=\"$query\"")
                return match
            }
        }
        println("FiberSocial: getGroup($permalink) -> NOT FOUND")
        return null
    }

    /**
     * Builds an ordered list of search queries to try for [permalink], from most to
     * least specific: the full permalink, then the permalink with any trailing
     * Ravelry disambiguation number (e.g. "-2") stripped, then progressively fewer
     * leading words of that stripped form.
     */
    private fun searchQueriesFor(permalink: String): List<String> {
        val withoutSuffix = permalink.replace(TRAILING_DISAMBIGUATOR_REGEX, "")
        val words = withoutSuffix.split("-").filter { it.isNotEmpty() }
        return buildList {
            add(permalink.replace("-", " "))
            add(words.joinToString(" "))
            if (words.size > 3) add(words.take(3).joinToString(" "))
            if (words.size > 2) add(words.take(2).joinToString(" "))
        }.distinct()
    }

    private suspend fun searchGroupByQuery(query: String, permalink: String): Group? = try {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/groups/search.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                url.parameters.apply {
                    append("query", query)
                    append("page_size", "25")
                }
            }
        }
        lenientJson.decodeFromString<GroupsSearchResponse>(raw).groups
            .find { it.permalink == permalink }
    } catch (e: SessionExpiredException) {
        throw e
    } catch (e: Exception) {
        println("FiberSocial: getGroup($permalink) query=\"$query\" error: ${e.message}")
        null
    }

    /**
     * Returns a page of topics for the given forum.
     *
     * @param forumId The `forum_id` from a [Group].
     * @param page 1-based page number.
     * @param pageSize Number of topics per page.
     * @return Topics for [page] (most-recently-replied first, Ravelry default) plus
     *   whether any further pages remain.
     */
    suspend fun getGroupTopics(
        forumId: Long,
        page: Int = 1,
        pageSize: Int = DEFAULT_FEED_PAGE_SIZE,
    ): TopicsPage {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/forums/$forumId/topics.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                url.parameters.apply {
                    append("page", page.toString())
                    append("page_size", pageSize.toString())
                }
            }
        }
        val response = lenientJson.decodeFromString<TopicsResponse>(raw)
        val paginator = response.paginator
        return TopicsPage(
            topics = response.topics,
            page = paginator?.page ?: page,
            hasMore = paginator != null && paginator.page < paginator.pageCount,
        )
    }

    /**
     * Returns all posts (replies) for a topic, ordered oldest-first. Each post's
     * [Post.voteTotals] and [Post.userVotes] are populated with its current reaction state.
     *
     * @param topicId Ravelry topic ID.
     */
    suspend fun getTopicPosts(topicId: Long): List<Post> {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/topics/$topicId/posts.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                url.parameters.append("include", "vote_totals user_votes")
            }
        }
        val response = lenientJson.decodeFromString<PostsResponse>(raw)
        // Unlike forum_posts/vote, this endpoint doesn't nest vote_totals/user_votes inside
        // each post — it returns them as separate maps keyed by post ID (as a string),
        // alongside the posts array. Merge them onto their matching post here.
        return response.posts.map { post ->
            val postKey = post.id.toString()
            post.copy(
                voteTotals = response.voteTotals[postKey] ?: emptyMap(),
                userVotes = response.userVotes[postKey] ?: emptyList(),
            )
        }
    }

    /**
     * Returns the most recent post in a topic, or `null` if the topic has no posts.
     *
     * Uses `sort_reverse=1` with `page_size=1` so only the newest post is transferred —
     * cheap enough to call once per feed topic. Vote data is not requested; feed cards
     * don't display it.
     *
     * @param topicId Ravelry topic ID.
     */
    suspend fun getLatestPost(topicId: Long): Post? {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/topics/$topicId/posts.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                url.parameters.apply {
                    append("sort_reverse", "1")
                    append("page_size", "1")
                }
            }
        }
        return lenientJson.decodeFromString<PostsResponse>(raw).posts.firstOrNull()
    }

    /**
     * Casts or clears the current user's vote of [type] on a forum post.
     *
     * @param postId Ravelry forum post ID.
     * @param type Which reaction to cast (interesting, educational, funny, agree, disagree, love).
     * @param voted `true` to cast the vote, `false` to clear it.
     * @return The post's updated vote totals and the current user's vote types.
     */
    suspend fun voteOnPost(postId: Long, type: VoteType, voted: Boolean): VoteResult {
        val raw = authenticatedRequest {
            httpClient.post("$BASE_URL/forum_posts/$postId/vote.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                url.parameters.apply {
                    append("type", type.wireValue)
                    append("vote", if (voted) "1" else "0")
                }
            }
        }
        val response = lenientJson.decodeFromString<VoteResponse>(raw)
        return VoteResult(voteTotals = response.voteTotals, userVotes = response.userVotes)
    }

    /**
     * Posts a plain-text reply to a topic.
     *
     * @param topicId Topic being replied to.
     * @param body Reply content.
     * @return The newly created post as returned by Ravelry.
     */
    suspend fun postReply(topicId: Long, body: String): Post {
        val raw = authenticatedRequest {
            httpClient.post("$BASE_URL/topics/$topicId/reply.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                // Form body, not a query parameter: free text can be multi-KB (URLs
                // have request-line limits, 414s) and would land in server logs.
                setBody(FormDataContent(Parameters.build { append("body", body) }))
            }
        }
        return try {
            lenientJson.decodeFromString<ForumPostResponse>(raw).forumPost
        } catch (e: Exception) {
            println("FiberSocial: postReply($topicId) unexpected response: ${raw.take(200)}")
            error("Unexpected Ravelry response — check the thread before retrying, the reply may have posted.")
        }
    }

    /**
     * Creates a new topic, with [body] as its opening post, in a forum.
     *
     * @param forumId Forum to create the topic in ([Group.forumId]).
     * @param title Topic title. Ravelry caps titles at 250 characters.
     * @param body Plain-text content of the opening post.
     * @return The newly created topic as returned by Ravelry.
     */
    suspend fun createTopic(forumId: Long, title: String, body: String): Topic {
        val raw = authenticatedRequest {
            httpClient.post("$BASE_URL/topics/create.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                // Form body, not query parameters — same reasoning as postReply.
                setBody(
                    FormDataContent(
                        Parameters.build {
                            append("forum_id", forumId.toString())
                            append("title", title)
                            append("body", body)
                        },
                    ),
                )
            }
        }
        return try {
            lenientJson.decodeFromString<TopicCreateResponse>(raw).topic
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("FiberSocial: createTopic(forumId=$forumId) unexpected response: ${raw.take(200)}")
            error("Unexpected Ravelry response — check the forum before retrying, the topic may have been created.")
        }
    }

    /**
     * Deletes the current user's own forum post.
     *
     * The API exposes no delete endpoint for forum posts (only `forum_posts/update`), so
     * this uses the website protocol: fetch the session-stable `authenticity_token` from
     * `meta#authenticity-token`, then replay Ravelry's own delete request — Prototype.js
     * `Ajax.Request('/forum_posts/ID', {method:'delete'})`, i.e. a POST with a Rails
     * `_method=delete` override plus the CSRF token.
     *
     * @param postId Ravelry forum post ID; must belong to the signed-in user.
     * @throws IllegalStateException if Ravelry rejects the deletion (non-2xx/3xx response).
     */
    suspend fun deletePost(postId: Long) {
        val token = fetchAuthenticityToken()
        val cookie = sessionCookie()
        val response = httpClient.submitForm(
            url = "$WWW_URL/forum_posts/$postId",
            formParameters = parameters {
                append("_method", "delete")
                append("authenticity_token", token)
            },
        ) {
            header(HttpHeaders.Cookie, cookie)
        }
        println("FiberSocial: deletePost($postId) -> ${response.status}")
        // Ktor doesn't follow redirects for POST, so the 3xx surfaces here directly.
        // A redirect is how BOTH outcomes look: success bounces back to the topic,
        // an expired session bounces to the login page — the Location header is the
        // only thing that tells them apart. Treating a login redirect as success
        // would remove the post locally while it lives on at Ravelry. Matched against
        // the redirect's URL PATH, not a raw substring of the whole Location string —
        // a real topic permalink containing "account"/"login" elsewhere (e.g. a group
        // named "login-fanatics") must not false-positive as a session-expiry redirect.
        val redirectPath = response.headers[HttpHeaders.Location]
            ?.let { runCatching { Url(it).encodedPath }.getOrDefault(it) }
            .orEmpty()
        when {
            redirectPath.startsWith("/login") || redirectPath.startsWith("/account") -> {
                cachedAuthenticityToken = null
                throw SessionExpiredException("Delete of post $postId redirected to login")
            }
            response.status == HttpStatusCode.Forbidden -> throw ForbiddenException(forbiddenMessage(response))
            response.status.isSuccess() || response.status.value in 300..399 -> Unit
            else -> {
                // The stale-token case rejects with 4xx; drop the cache so a retry
                // re-scrapes a fresh one.
                cachedAuthenticityToken = null
                error("Delete rejected: HTTP ${response.status.value}")
            }
        }
    }

    /** Session-stable CSRF token, cached after the first scrape (see [fetchAuthenticityToken]). */
    private var cachedAuthenticityToken: String? = null

    /**
     * Reads the session-stable CSRF token Ravelry embeds on every page as
     * `<meta name="authenticity-token" content="...">`, cached per client so repeated
     * deletes don't re-download the homepage. Extraction uses the same Ksoup selector
     * as [EventPageParser]'s RSVP token scrape so the two can't drift.
     */
    private suspend fun fetchAuthenticityToken(): String {
        cachedAuthenticityToken?.let { return it }
        val html = httpClient.get(WWW_URL) {
            header(HttpHeaders.Cookie, sessionCookie())
            header(HttpHeaders.Accept, "text/html")
        }.bodyAsText()
        val token = Ksoup.parse(html)
            .selectFirst("meta#authenticity-token, meta[name=authenticity-token], meta[name=csrf-token]")
            ?.attr("content")
        if (token.isNullOrEmpty()) error("No authenticity token found — re-login required")
        cachedAuthenticityToken = token
        return token
    }

    /**
     * Edits the current user's own forum post via the documented `forum_posts/update` endpoint.
     *
     * @param postId Ravelry forum post ID; must be editable by the signed-in user.
     * @param body New plain-text/markdown post body.
     * @return The updated post as returned by Ravelry.
     */
    suspend fun editPost(postId: Long, body: String): Post {
        val raw = authenticatedRequest {
            httpClient.post("$BASE_URL/forum_posts/$postId.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
                // Form body for the same reasons as postReply: multi-KB free text
                // breaks URL length limits and leaks into server logs.
                setBody(FormDataContent(Parameters.build { append("body", body) }))
            }
        }
        return try {
            lenientJson.decodeFromString<ForumPostResponse>(raw).forumPost
        } catch (e: Exception) {
            println("FiberSocial: editPost($postId) unexpected response: ${raw.take(200)}")
            error("Unexpected Ravelry response — check the thread before retrying, the edit may have applied.")
        }
    }

    /**
     * Returns the full detail for a single topic, including [Topic.createdByUser] and [Topic.summary].
     *
     * @param topicId Ravelry topic ID.
     */
    suspend fun getTopicDetail(topicId: Long): Topic {
        val raw = authenticatedRequest {
            httpClient.get("$BASE_URL/topics/$topicId.json") {
                header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            }
        }
        return lenientJson.decodeFromString<TopicDetailResponse>(raw).topic
    }

    @Serializable private data class PostsResponse(
        val posts: List<Post> = emptyList(),
        @SerialName("vote_totals") val voteTotals: Map<String, Map<String, Int>> = emptyMap(),
        @SerialName("user_votes") val userVotes: Map<String, List<String>> = emptyMap(),
    )
    @Serializable private data class ForumPostResponse(@SerialName("forum_post") val forumPost: Post)
    @Serializable private data class TopicCreateResponse(val topic: Topic)
    @Serializable private data class CurrentUserResponse(val user: RavelryUser)
    @Serializable private data class GroupsSearchResponse(val groups: List<Group> = emptyList())
    @Serializable private data class TopicsResponse(
        val topics: List<Topic> = emptyList(),
        val paginator: Paginator? = null,
    )
    @Serializable private data class TopicDetailResponse(val topic: Topic)
    @Serializable private data class Paginator(
        val page: Int = 1,
        @SerialName("page_count") val pageCount: Int = 1,
        @SerialName("results") val totalResults: Int = 0,
    )
    @Serializable private data class VoteResponse(
        @SerialName("vote_totals") val voteTotals: Map<String, Int> = emptyMap(),
        @SerialName("user_votes") val userVotes: List<String> = emptyList(),
    )
}

/**
 * Result of casting or clearing a vote on a forum post.
 *
 * @property voteTotals Vote-type name to total vote count on the post, after the vote.
 * @property userVotes Vote-type names the current user has cast on the post, after the vote.
 */
data class VoteResult(
    val voteTotals: Map<String, Int>,
    val userVotes: List<String>,
)

/**
 * One page of a forum's topics, as returned by [RavelryApiClient.getGroupTopics].
 *
 * @property hasMore Whether requesting `page + 1` would return further topics.
 */
data class TopicsPage(
    val topics: List<Topic>,
    val page: Int,
    val hasMore: Boolean,
)
