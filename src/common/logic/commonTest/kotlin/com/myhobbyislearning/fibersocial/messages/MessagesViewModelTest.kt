package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.feed.CURRENT_USER_JSON
import com.myhobbyislearning.fibersocial.feed.FakeFeedTokenStorage
import com.myhobbyislearning.fibersocial.feed.RavelryApiClient
import com.myhobbyislearning.fibersocial.feed.errorApiClient
import com.myhobbyislearning.fibersocial.feed.routingApiClientCapturing
import com.myhobbyislearning.fibersocial.feed.sessionExpiredApiClient
import com.myhobbyislearning.fibersocial.feed.suspendableRoutingApiClient
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest

/**
 * One message as `/messages/list.json` serves it. [sender] and [recipient] are emitted as
 * JSON `null` when absent, which is the shape behind a null counterpart (system notices,
 * deleted accounts).
 */
private fun messageJson(
    id: Long,
    subject: String = "Yarn talk",
    sender: String? = "friend",
    recipient: String? = "yarnie",
    sentAt: String? = "2026/07/03 10:00:00 +0000",
    read: Boolean = true,
    parentId: Long? = null,
    contentHtml: String? = "<p>Hello there</p>",
): String {
    fun user(name: String?) =
        if (name == null) "null" else """{"username":"$name"}"""
    return """
        {
          "id": $id,
          "subject": "$subject",
          "sender": ${user(sender)},
          "recipient": ${user(recipient)},
          "sent_at": ${sentAt?.let { "\"$it\"" } ?: "null"},
          "read_message": $read,
          "parent_message_id": ${parentId ?: "null"},
          "content_html": ${contentHtml?.let { "\"$it\"" } ?: "null"}
        }
    """.trimIndent()
}

private fun listJson(
    messages: List<String>,
    page: Int = 1,
    pageCount: Int = 1,
): String = """
    {
      "messages": [${messages.joinToString(",")}],
      "paginator": {"page": $page, "page_count": $pageCount, "results": ${messages.size}}
    }
""".trimIndent()

/**
 * The message id in a `GET /messages/{id}.json`, or `null` for any other request —
 * notably `/messages/list.json`, which the digits-only group is what excludes.
 */
private fun messageIdIn(url: Url): Long? =
    Regex("""^/messages/(\d+)\.json$""").find(url.encodedPath)?.groupValues?.get(1)?.toLong()

private const val EMPTY_LIST_JSON =
    """{"messages":[],"paginator":{"page":1,"page_count":1,"results":0}}"""

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessagesViewModelTest {

    private suspend fun awaitChildren(job: Job) = job.children.toList().forEach { it.join() }

    @Test
    fun `initial state is Loading`() = runTest(UnconfinedTestDispatcher()) {
        val vm = MessagesViewModel(routingApiClientCapturing(onRequest = {}) { EMPTY_LIST_JSON }, this)
        assertIs<MessagesState.Loading>(vm.state.value)
    }

    /**
     * THE cross-folder contract (`MessageThreads.kt`): `groupIntoThreads` is folder-agnostic
     * and threads exactly what it is handed, so an inbox-only fetch would hide every
     * conversation the user started and leave the rest one-sided. This asserts the requests
     * themselves, not just the merged output — an implementation that fetched the inbox
     * twice would still produce plausible-looking threads.
     */
    @Test
    fun `load requests both the inbox and the sent folder`() = runTest(UnconfinedTestDispatcher()) {
        val folders = mutableListOf<String>()
        val client = routingApiClientCapturing(
            onRequest = { url -> url.parameters["folder"]?.let { folders += it } },
        ) { EMPTY_LIST_JSON }

        MessagesViewModel(client, this).load("yarnie")
        awaitChildren(coroutineContext[Job]!!)

        assertEquals(listOf("inbox", "sent"), folders.sorted())
    }

    /**
     * The merge has to actually reach `groupIntoThreads` as ONE list: a reply living in
     * `sent` must join its parent from `inbox` rather than forming a second thread.
     */
    @Test
    fun `load merges inbox and sent into one thread`() = runTest(UnconfinedTestDispatcher()) {
        val client = suspendableRoutingApiClient { url ->
            when (url.parameters["folder"]) {
                "inbox" -> listJson(listOf(messageJson(id = 1, subject = "Yarn talk")))
                else -> listJson(
                    listOf(
                        messageJson(
                            id = 2,
                            subject = "Re: Yarn talk",
                            sender = "yarnie",
                            recipient = "friend",
                            sentAt = "2026/07/03 11:00:00 +0000",
                            parentId = 1,
                        ),
                    ),
                )
            }
        }

        val vm = MessagesViewModel(client, this)
        vm.load("yarnie")
        awaitChildren(coroutineContext[Job]!!)

        val state = assertIs<MessagesState.Loaded>(vm.state.value)
        assertEquals(1, state.threads.size)
        assertEquals(listOf(1L, 2L), state.threads.single().messages.map { it.id })
        assertEquals("Yarn talk", state.threads.single().subject)
        assertEquals("friend", state.threads.single().counterpart?.username)
    }

    @Test
    fun `an empty mailbox loads as an empty thread list rather than an error`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = MessagesViewModel(
                routingApiClientCapturing(onRequest = {}) { EMPTY_LIST_JSON },
                this,
            )
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            assertTrue(state.threads.isEmpty())
            assertTrue(!state.hasMore)
        }

    @Test
    fun `a failed load surfaces an error`() = runTest(UnconfinedTestDispatcher()) {
        val vm = MessagesViewModel(errorApiClient(), this)
        vm.load("yarnie")
        awaitChildren(coroutineContext[Job]!!)

        assertIs<MessagesState.Error>(vm.state.value)
    }

    /**
     * Issue #330 in miniature: the events screen's error recovery called a refresh that
     * no-ops unless already loaded, so its error screen could never be left. [retry] must
     * work from [MessagesState.Error] and must not need the username handed back.
     */
    @Test
    fun `retry after an error succeeds without being given the username again`() =
        runTest(UnconfinedTestDispatcher()) {
            var fail = true
            val client = suspendableRoutingApiClient { _ ->
                if (fail) error("Simulated network error")
                listJson(listOf(messageJson(id = 1)))
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<MessagesState.Error>(vm.state.value)

            fail = false
            vm.retry()
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(1, state.threads.size)
            // The retained username still drove the threading: `friend` is the counterpart
            // only if `yarnie` was recognised as us.
            assertEquals("friend", state.threads.single().counterpart?.username)
        }

    /** Page 2 must ADD conversations, not replace page 1's. */
    @Test
    fun `loadMore appends the next page rather than replacing it`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = suspendableRoutingApiClient { url ->
                val page = url.parameters["page"]?.toInt() ?: 1
                val folder = url.parameters["folder"]
                when {
                    // Only the inbox has a second page; `sent` finishes at page 1. The
                    // folders therefore page independently, which is the point.
                    folder == "sent" -> listJson(listOf(messageJson(id = 100)), pageCount = 1)
                    page == 1 -> listJson(listOf(messageJson(id = 1)), page = 1, pageCount = 2)
                    else -> listJson(listOf(messageJson(id = 2)), page = 2, pageCount = 2)
                }
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            val first = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(setOf(1L, 100L), first.threads.map { it.rootId }.toSet())
            assertTrue(first.hasMore)

            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)

            val second = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(setOf(1L, 2L, 100L), second.threads.map { it.rootId }.toSet())
        }

    /** A folder that ran out must not keep being asked for pages past its end. */
    @Test
    fun `loadMore stops requesting a folder that has no further pages`() =
        runTest(UnconfinedTestDispatcher()) {
            val requested = mutableListOf<Pair<String, String>>()
            val client = suspendableRoutingApiClient { url ->
                requested += (url.parameters["folder"] ?: "") to (url.parameters["page"] ?: "")
                val page = url.parameters["page"]?.toInt() ?: 1
                if (url.parameters["folder"] == "sent") {
                    listJson(listOf(messageJson(id = 100)), pageCount = 1)
                } else {
                    listJson(listOf(messageJson(id = page.toLong())), page = page, pageCount = 2)
                }
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)
            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(requested.contains("inbox" to "2"))
            assertTrue(!requested.contains("sent" to "2"))
        }

    /** loadMore is documented as a no-op unless loaded — before the first load there is
     * nothing to append to, and firing requests would race the initial round. */
    @Test
    fun `loadMore before anything has loaded makes no requests`() =
        runTest(UnconfinedTestDispatcher()) {
            var requests = 0
            val client = routingApiClientCapturing(onRequest = { requests++ }) { EMPTY_LIST_JSON }

            val vm = MessagesViewModel(client, this)
            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)

            assertIs<MessagesState.Loading>(vm.state.value)
            assertEquals(0, requests)
        }

    /** The scroll trigger fires repeatedly near the list end; a second loadMore while one
     * is in flight must not double-fetch (and double-append) the same page. */
    @Test
    fun `a second loadMore while one is in flight is ignored`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            var pageTwoRequests = 0
            val client = suspendableRoutingApiClient { url ->
                val page = url.parameters["page"]?.toInt() ?: 1
                if (url.parameters["folder"] == "sent") {
                    listJson(listOf(messageJson(id = 100)), pageCount = 1)
                } else if (page == 1) {
                    listJson(listOf(messageJson(id = 1)), page = 1, pageCount = 2)
                } else {
                    pageTwoRequests++
                    gate.await()
                    listJson(listOf(messageJson(id = 2)), page = 2, pageCount = 2)
                }
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.loadMore()
            vm.loadMore() // in-flight: must be ignored, not queued
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(1, pageTwoRequests)
            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(setOf(1L, 2L, 100L), state.threads.map { it.rootId }.toSet())
        }

    // --- the scrape fallback reaching the screen (issue #396) -------------------------

    /**
     * An API host that 403s message reads (the ungrantable `message-read` scope) after
     * [jsonListPages] successful list calls, plus a web host serving [inboxHtml]/[sentHtml]
     * folder pages. `jsonListPages = 0` is scrape-from-the-start; a positive value lets the
     * revocation land mid-session, on a loadMore.
     */
    private fun scrapeModeClient(
        inboxHtml: String,
        sentHtml: String,
        jsonListPages: Int = 0,
        jsonListPage: (Url) -> String = { EMPTY_LIST_JSON },
    ): RavelryApiClient {
        var listCalls = 0
        val engine = MockEngine { request ->
            val host = request.url.host
            val path = request.url.encodedPath
            when {
                host == "api.ravelry.com" && path == "/current_user.json" ->
                    respond(
                        CURRENT_USER_JSON,
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                host == "api.ravelry.com" && path == "/messages/list.json" -> {
                    listCalls++
                    if (listCalls <= jsonListPages) {
                        respond(
                            jsonListPage(request.url),
                            HttpStatusCode.OK,
                            headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    } else {
                        respond(
                            """{"error":"message-read scope is required for this action"}""",
                            HttpStatusCode.Forbidden,
                            headersOf("Content-Type", ContentType.Application.Json.toString()),
                        )
                    }
                }
                host == "www.ravelry.com" && path.endsWith("/sent") ->
                    respond(sentHtml, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                host == "www.ravelry.com" ->
                    respond(inboxHtml, HttpStatusCode.OK, headersOf("Content-Type", "text/html"))
                else -> error("Unexpected request to $host$path")
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(client, FakeFeedTokenStorage())
    }

    private fun folderRowHtml(id: Long, username: String, subject: String, date: String) = """
        <div class="message_row message_row--read" id="message_row_$id">
          <div class="resizable_table__cell message_row__date rsp_only">$date</div>
          <div class="message_row___avatar"><div class="message_row__username">$username</div></div>
          <div class="message_row__subject resizable_table__cell"><a href="#">$subject</a></div>
        </div>
    """.trimIndent()

    /**
     * End-to-end through the ViewModel: scraped pages carry no `parent_message_id`, so
     * without the subject-merge fallback engaging the exchange below would render as three
     * one-message "conversations". The `viaScrape` flag must survive the trip from
     * `MessagesPage` through `fetchRound` into `groupIntoThreads`.
     */
    @Test
    fun `a scraped load merges the folders into conversations by subject`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = scrapeModeClient(
                inboxHtml = folderRowHtml(101, "WoolyWendy", "Yarn swap?", "July 11, 2026 3:38 AM") +
                    folderRowHtml(103, "WoolyWendy", "Re: Yarn swap?", "July 13, 2026 9:00 AM"),
                sentHtml = folderRowHtml(102, "WoolyWendy", "Re: Yarn swap?", "July 12, 2026 8:00 AM"),
            )

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            val thread = state.threads.single()
            assertEquals(listOf(101L, 102L, 103L), thread.messages.map { it.id })
            assertEquals("Yarn swap?", thread.subject)
            assertEquals("WoolyWendy", thread.counterpart?.username)
            // The whole web folder arrives in one page.
            assertTrue(!state.hasMore)
        }

    /**
     * The latch can flip mid-session: a load that succeeded over JSON followed by a
     * loadMore that 403s. `anyPageViaScrape` (and therefore `groupIntoThreads`'
     * `mergeBySubjectFallback`) is session-wide — it accumulates with OR across rounds —
     * but the subject+counterpart merge itself is gated per-message by [Message.viaScrape],
     * so messages that were genuinely fetched over JSON in an earlier round must NOT be
     * swept into the merge just because a LATER round in the same session fell back to
     * scraping. Regression pin for the bug where a session-wide flag caused the merge to
     * run over the entire accumulated corpus, including pure-JSON pages.
     */
    @Test
    fun `a loadMore that falls back to scrape does not merge earlier all JSON pages`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = scrapeModeClient(
                // The scraped folder re-serves the same mail the JSON pages already
                // delivered; duplicate ids collapse in the grouping, and the JSON
                // (non-scraped) copy wins as the first occurrence.
                inboxHtml = folderRowHtml(1, "friend", "Yarn talk", "July 3, 2026 10:00 AM"),
                sentHtml = folderRowHtml(2, "friend", "Re: Yarn talk", "July 3, 2026 11:00 AM"),
                jsonListPages = 2,
                jsonListPage = { url ->
                    if (url.parameters["folder"] == "sent") {
                        // Genuinely parentless JSON message — NOT scrape-shaped data, even
                        // though it (like a scraped row) carries no parent_message_id.
                        listJson(
                            listOf(
                                messageJson(
                                    id = 2,
                                    subject = "Re: Yarn talk",
                                    sender = "yarnie",
                                    recipient = "friend",
                                    sentAt = "2026/07/03 11:00:00 +0000",
                                ),
                            ),
                            pageCount = 1,
                        )
                    } else {
                        listJson(listOf(messageJson(id = 1, subject = "Yarn talk")), pageCount = 2)
                    }
                },
            )

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)
            // Over JSON, with no parent ids, the two messages are two threads.
            val beforeFallback = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(2, beforeFallback.threads.size)
            assertTrue(beforeFallback.hasMore)

            vm.loadMore() // inbox page 2 403s -> latches -> scrapes
            awaitChildren(coroutineContext[Job]!!)

            // The scraped round re-serves the same two ids, which dedup collapses onto
            // the ORIGINAL JSON messages (first occurrence wins) — so both stay
            // non-scrape and the subject+counterpart merge must still leave them apart,
            // even though `anyPageViaScrape` is now true for the session.
            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(2, state.threads.size)
            assertEquals(setOf(1L), state.threads.first { it.rootId == 1L }.messages.map { it.id }.toSet())
            assertEquals(setOf(2L), state.threads.first { it.rootId == 2L }.messages.map { it.id }.toSet())
        }

    /**
     * Expiry is signalled, never swallowed into an error screen — the host logs the user
     * out. (A plain 403 is NOT expiry, issue #82; only the client's own exception is, and
     * that is what `sessionExpiredApiClient` throws.)
     */
    @Test
    fun `session expiry signals instead of surfacing an error`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = MessagesViewModel(sessionExpiredApiClient(), this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            // Loading, not Error: the app is about to navigate to login, so an error
            // flash would be misleading (matches FeedViewModel/EventsViewModel).
            assertIs<MessagesState.Loading>(vm.state.value)
            // The signal is a BUFFERED channel, so collecting after the fact still sees it.
            assertEquals(Unit, vm.sessionExpired.first())
        }

    /**
     * Both parties can be absent (a system notice, a deleted account). The thread must
     * still be produced with a null counterpart — the screen renders "(unknown)" for it —
     * rather than the ViewModel crashing or dropping the conversation.
     */
    @Test
    fun `a thread whose parties are both absent loads with a null counterpart`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = suspendableRoutingApiClient { url ->
                if (url.parameters["folder"] == "inbox") {
                    listJson(
                        listOf(
                            messageJson(
                                id = 7,
                                subject = "System notice",
                                sender = null,
                                recipient = null,
                            ),
                        ),
                    )
                } else {
                    EMPTY_LIST_JSON
                }
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            val thread = state.threads.single()
            assertNull(thread.counterpart)
            assertEquals("System notice", thread.subject)
        }

    /**
     * `lastActivityAt` is `Long?` and must stay null for an unparseable `sent_at` rather
     * than collapsing to 0L — the screen renders unknown, and 0L would sort a live
     * conversation to 1970.
     */
    @Test
    fun `a thread with no parseable timestamp reports null activity`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = suspendableRoutingApiClient { url ->
                if (url.parameters["folder"] == "inbox") {
                    listJson(listOf(messageJson(id = 9, sentAt = null)))
                } else {
                    EMPTY_LIST_JSON
                }
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            assertNull(state.threads.single().lastActivityAt)
        }

    /**
     * A failed page-2 fetch must keep page 1 on screen AND stop paging — the list's scroll
     * trigger re-arms when `loadingMore` drops, so leaving `hasMore` true would loop on the
     * failing endpoint.
     */
    @Test
    fun `a failed loadMore keeps existing threads and suspends paging`() =
        runTest(UnconfinedTestDispatcher()) {
            var firstRound = true
            val client = suspendableRoutingApiClient { url ->
                if (!firstRound) error("Simulated network error")
                val page = url.parameters["page"]?.toInt() ?: 1
                listJson(listOf(messageJson(id = 1)), page = page, pageCount = 2)
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)
            assertTrue(assertIs<MessagesState.Loaded>(vm.state.value).hasMore)

            firstRound = false
            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(1, state.threads.size)
            assertTrue(!state.hasMore)
            assertTrue(!state.loadingMore)
        }

    /**
     * A failed pull-to-refresh reports the failure rather than silently leaving stale
     * content under a spinner that stopped, and the resulting error screen is recoverable
     * by the same [MessagesViewModel.retry] the error state offers.
     */
    @Test
    fun `a failed refresh surfaces a retryable error`() =
        runTest(UnconfinedTestDispatcher()) {
            var fail = false
            val client = suspendableRoutingApiClient { _ ->
                if (fail) error("Simulated network error")
                listJson(listOf(messageJson(id = 1)))
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)
            assertIs<MessagesState.Loaded>(vm.state.value)

            fail = true
            vm.refresh()
            awaitChildren(coroutineContext[Job]!!)
            assertIs<MessagesState.Error>(vm.state.value)

            fail = false
            vm.retry()
            awaitChildren(coroutineContext[Job]!!)
            assertEquals(1, assertIs<MessagesState.Loaded>(vm.state.value).threads.size)
        }

    @Test
    fun `loadMore is a no-op when there are no further pages`() =
        runTest(UnconfinedTestDispatcher()) {
            var requests = 0
            val client = suspendableRoutingApiClient { _ ->
                requests++
                listJson(listOf(messageJson(id = 1)))
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)
            val afterLoad = requests

            vm.loadMore()
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(afterLoad, requests)
        }

    /** Bodies drive the row preview, so they must survive decoding off the list call. */
    @Test
    fun `loaded threads carry the message body used for the row preview`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = suspendableRoutingApiClient { url ->
                if (url.parameters["folder"] == "inbox") {
                    listJson(listOf(messageJson(id = 1, contentHtml = "<p>Hello there</p>")))
                } else {
                    EMPTY_LIST_JSON
                }
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            val newest = state.threads.single().messages.last()
            assertEquals("Hello there", messagePreviewText(newest.contentHtml))
        }

    /** A body-less list response is a routine degrade, not a crash — see MessagesViewModel. */
    @Test
    fun `a message with no body previews as empty rather than failing`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = suspendableRoutingApiClient { url ->
                if (url.parameters["folder"] == "inbox") {
                    listJson(listOf(messageJson(id = 1, contentHtml = null)))
                } else {
                    EMPTY_LIST_JSON
                }
            }

            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            val state = assertIs<MessagesState.Loaded>(vm.state.value)
            val newest = state.threads.single().messages.last()
            assertEquals("", messagePreviewText(newest.contentHtml))
        }

    /** Ravelry's `folder=` param is case-sensitive wire text, not the enum's name. */
    @Test
    fun `folders are requested by their lowercase wire names`() =
        runTest(UnconfinedTestDispatcher()) {
            val urls = mutableListOf<Url>()
            val client = routingApiClientCapturing(onRequest = { urls += it }) { EMPTY_LIST_JSON }

            MessagesViewModel(client, this).load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(urls.all { it.encodedPath == "/messages/list.json" })
            assertEquals(
                setOf("inbox", "sent"),
                urls.mapNotNull { it.parameters["folder"] }.toSet(),
            )
        }

    // ---------------------------------------------------------------------------------
    // Conversation detail (issue #371): opening a thread reads it.
    // ---------------------------------------------------------------------------------

    /**
     * The three-message conversation every detail test below opens.
     *
     * Deliberately mixed so "only unread inbound" has something to get wrong:
     * - **1** inbound and UNREAD — the one and only message that may be marked read.
     * - **2** OUTBOUND and `read_message: false`, served from the `sent` folder. That flag
     *   describes whether *friend* has read it; POSTing mark_read for it would be marking
     *   someone else's mailbox.
     * - **3** inbound but ALREADY read — re-marking it is a wasted request.
     */
    private fun conversationClient(
        bodies: String? = "<p>Hello there</p>",
        onRequest: (Url) -> Unit = {},
        route: suspend (Url) -> String? = { null },
    ) = suspendableRoutingApiClient { url ->
        onRequest(url)
        route(url) ?: when {
            url.encodedPath != "/messages/list.json" -> "{}"
            url.parameters["folder"] == "inbox" -> listJson(
                listOf(
                    messageJson(id = 1, read = false, contentHtml = bodies),
                    messageJson(id = 3, read = true, parentId = 1, contentHtml = bodies),
                ),
            )
            else -> listJson(
                listOf(
                    messageJson(
                        id = 2,
                        sender = "yarnie",
                        recipient = "friend",
                        read = false,
                        parentId = 1,
                        contentHtml = bodies,
                    ),
                ),
            )
        }
    }

    private fun markReadPaths(urls: List<Url>) =
        urls.map { it.encodedPath }.filter { it.endsWith("/mark_read.json") }

    @Test
    fun `opening a thread marks only the unread inbound messages read`() =
        runTest(UnconfinedTestDispatcher()) {
            val urls = mutableListOf<Url>()
            val vm = MessagesViewModel(conversationClient(onRequest = { urls += it }), this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            // NOT /messages/2/mark_read.json (outbound) and NOT /messages/3 (already read).
            assertEquals(listOf("/messages/1/mark_read.json"), markReadPaths(urls))
        }

    @Test
    fun `reading a thread clears the unread state on the thread and on its list row`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = MessagesViewModel(conversationClient(), this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)
            assertTrue(assertIs<MessagesState.Loaded>(vm.state.value).threads.single().hasUnread)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            // Both views come from one regrouping, so they cannot disagree — assert both.
            assertEquals(false, assertIs<MessagesState.Loaded>(vm.state.value).threads.single().hasUnread)
            assertEquals(false, vm.openThread.value?.thread?.hasUnread)
        }

    /**
     * A mark-read that fails must cost the user nothing but the dot: the conversation stays
     * open, ordered and readable, and the list behind it stays loaded.
     */
    @Test
    fun `a failed mark-read leaves the thread open and readable`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = conversationClient(
                route = { url ->
                    if (url.encodedPath.endsWith("/mark_read.json")) error("mark_read exploded") else null
                },
            )
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            val open = vm.openThread.value
            assertEquals(listOf(1L, 3L, 2L).sorted(), open?.thread?.messages?.map { it.id }?.sorted())
            // Still unread — nothing was marked, and pretending otherwise would hide the
            // message from the very dot that exists to surface it.
            assertEquals(true, open?.thread?.hasUnread)
            assertIs<MessagesState.Loaded>(vm.state.value)
        }

    /**
     * THE MARK-READ RACE (issue #371; the same trap documented at
     * `FeedViewModel.refreshDrawerUnreadAfterReading` and `RavelryApiClient.markMessageRead`).
     *
     * The drawer dot's re-probe is a GET. Fired next to the mark_read POST rather than after
     * it, it can win, observe the pre-POST state and re-light the dot reading the thread just
     * cleared. This gates the POST open so the two would visibly overlap if they were
     * concurrent — asserting the callback has NOT run while the POST is still in flight is
     * what makes this a race test rather than an ordering-of-a-completed-list test.
     */
    @Test
    fun `the unread re-probe runs only after the mark-read POST has returned`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            // Ktor's engine does not necessarily run the request on the caller's dispatcher,
            // so the test waits for the POST to actually be in flight rather than assuming
            // launching openThread got it there.
            val postStarted = CompletableDeferred<Unit>()
            val order = mutableListOf<String>()
            val client = conversationClient(
                route = { url ->
                    if (!url.encodedPath.endsWith("/mark_read.json")) {
                        null
                    } else {
                        order += "post-started"
                        postStarted.complete(Unit)
                        gate.await()
                        order += "post-returned"
                        "{}"
                    }
                },
            )
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1) { order += "re-probe" }
            postStarted.await()

            assertEquals(listOf("post-started"), order)
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(listOf("post-started", "post-returned", "re-probe"), order)
        }

    /** Backing out of an already-read conversation must cost no POST and no re-probe GET. */
    @Test
    fun `an already-read thread neither posts nor re-probes`() =
        runTest(UnconfinedTestDispatcher()) {
            val urls = mutableListOf<Url>()
            var probes = 0
            val client = suspendableRoutingApiClient { url ->
                urls += url
                if (url.parameters["folder"] == "inbox") {
                    listJson(listOf(messageJson(id = 1, read = true)))
                } else {
                    EMPTY_LIST_JSON
                }
            }
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1) { probes++ }
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(emptyList(), markReadPaths(urls))
            assertEquals(0, probes)
        }

    /**
     * The body backfill (see `MessagesViewModel.backfillBodies`): the list shape may omit
     * `content_html`, so a body-less message is re-fetched through `getMessage`, which
     * always returns the full shape. Bounded to the ONE conversation the user opened.
     */
    @Test
    fun `opening a thread backfills the bodies the list did not carry`() =
        runTest(UnconfinedTestDispatcher()) {
            val urls = mutableListOf<Url>()
            val client = conversationClient(
                bodies = null,
                onRequest = { urls += it },
                route = { url ->
                    messageIdIn(url)?.let {
                        """{"message": ${messageJson(id = it, contentHtml = "<p>Body $it</p>")}}"""
                    }
                },
            )
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(setOf(1L, 2L, 3L), urls.mapNotNull { messageIdIn(it) }.toSet())
            val open = assertNotNull(vm.openThread.value)
            assertEquals(false, open.loadingBodies)
            assertNull(open.bodyError)
            assertTrue(open.thread.messages.all { !it.contentHtml.isNullOrBlank() })
        }

    /** A message whose body the list already carried is never re-fetched. */
    @Test
    fun `bodies already present cost no extra request`() =
        runTest(UnconfinedTestDispatcher()) {
            val urls = mutableListOf<Url>()
            val vm = MessagesViewModel(conversationClient(onRequest = { urls += it }), this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(urls.none { messageIdIn(it) != null })
            assertEquals(false, vm.openThread.value?.loadingBodies)
        }

    /** A failed backfill is a partially blank conversation, never a dead screen. */
    @Test
    fun `a failed body backfill keeps the thread open and reports itself`() =
        runTest(UnconfinedTestDispatcher()) {
            val client = conversationClient(
                bodies = null,
                route = { url ->
                    if (messageIdIn(url) != null) {
                        error("show exploded")
                    } else {
                        null
                    }
                },
            )
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            val open = vm.openThread.value
            assertEquals(false, open?.loadingBodies)
            assertTrue(open?.bodyError != null)
            assertEquals(3, open?.thread?.messages?.size)
        }

    @Test
    fun `closing a thread returns to the list`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = MessagesViewModel(conversationClient(), this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)
            assertTrue(vm.openThread.value != null)

            vm.closeThread()

            assertNull(vm.openThread.value)
            assertIs<MessagesState.Loaded>(vm.state.value)
        }

    /**
     * Backing out of a conversation must not cancel the read it started.
     *
     * `openThread` deliberately does not tie its work to the screen's lifetime: a user who
     * taps Back faster than the network still expects the dot to go out, and losing the
     * POST would put it straight back on the next probe. The gate here holds the POST open
     * across the close so the two genuinely overlap.
     */
    @Test
    fun `closing a thread mid mark-read still clears the row`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val postStarted = CompletableDeferred<Unit>()
            val client = conversationClient(
                route = { url ->
                    if (!url.encodedPath.endsWith("/mark_read.json")) {
                        null
                    } else {
                        postStarted.complete(Unit)
                        gate.await()
                        "{}"
                    }
                },
            )
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            postStarted.await()
            vm.closeThread()
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertNull(vm.openThread.value)
            assertEquals(false, assertIs<MessagesState.Loaded>(vm.state.value).threads.single().hasUnread)
        }

    /** Same contract for the backfill: a late body lands in the list the user went back to. */
    @Test
    fun `closing a thread mid backfill still lands the bodies in the list`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val showStarted = CompletableDeferred<Unit>()
            val client = conversationClient(
                bodies = null,
                route = { url ->
                    val id = messageIdIn(url) ?: return@conversationClient null
                    showStarted.complete(Unit)
                    gate.await()
                    """{"message": ${messageJson(id = id, contentHtml = "<p>Body $id</p>")}}"""
                },
            )
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            showStarted.await()
            vm.closeThread()
            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertNull(vm.openThread.value)
            val threads = assertIs<MessagesState.Loaded>(vm.state.value).threads
            assertTrue(threads.single().messages.all { !it.contentHtml.isNullOrBlank() })
        }

    /**
     * Only the body-less messages are re-fetched — including one whose `content_html` came
     * back as an empty string rather than absent, which is just as unrenderable. This is
     * what bounds the backfill's cost when `output_format=full` half-works.
     */
    @Test
    fun `only the messages actually missing a body are re-fetched`() =
        runTest(UnconfinedTestDispatcher()) {
            val urls = mutableListOf<Url>()
            val client = suspendableRoutingApiClient { url ->
                urls += url
                val id = messageIdIn(url)
                when {
                    id != null -> """
                        {"message": {
                          "id": $id, "subject": "Yarn talk",
                          "sender": {"username":"friend"}, "recipient": {"username":"yarnie"},
                          "read_message": true,
                          "content_html": "<p>Fetched $id</p>",
                          "content": "Fetched $id",
                          "folder_name": "inbox"
                        }}
                    """.trimIndent()
                    url.parameters["folder"] == "inbox" -> listJson(
                        listOf(
                            messageJson(id = 1, contentHtml = "<p>Already here</p>"),
                            messageJson(id = 3, parentId = 1, contentHtml = ""),
                        ),
                    )
                    else -> EMPTY_LIST_JSON
                }
            }
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(listOf(3L), urls.mapNotNull { messageIdIn(it) })
            val messages = assertNotNull(vm.openThread.value).thread.messages.associateBy { it.id }
            // Message 1 kept the body it already had; only 3 was replaced.
            assertEquals("<p>Already here</p>", messages.getValue(1L).contentHtml)
            assertEquals("<p>Fetched 3</p>", messages.getValue(3L).contentHtml)
            // The full shape's other fields come along when the response carries them.
            assertEquals("Fetched 3", messages.getValue(3L).content)
            assertEquals("inbox", messages.getValue(3L).folderName)
        }

    /**
     * A message that names neither party — a system notice, or an account since deleted —
     * is [MessageDirection.UNKNOWN], and UNKNOWN is not INBOUND. Marking it read would be
     * acting on a guess about whose message it even is, and `groupIntoThreads` already
     * declines to make that guess for the unread dot.
     */
    @Test
    fun `a message of unknown direction is never marked read`() =
        runTest(UnconfinedTestDispatcher()) {
            val urls = mutableListOf<Url>()
            val client = suspendableRoutingApiClient { url ->
                urls += url
                if (url.parameters["folder"] == "inbox") {
                    listJson(
                        listOf(
                            messageJson(id = 1, sender = null, recipient = null, read = false),
                        ),
                    )
                } else {
                    EMPTY_LIST_JSON
                }
            }
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(emptyList(), markReadPaths(urls))
        }

    /**
     * A mark-read landing while the LIST has fallen into an error state must apply to the
     * open thread without resurrecting the list: republishing content over
     * [MessagesState.Error] would turn a failed refresh into a screen that silently looks
     * fine, hiding the failure the user still needs to retry.
     */
    @Test
    fun `a late mark-read does not turn a failed list back into content`() =
        runTest(UnconfinedTestDispatcher()) {
            val gate = CompletableDeferred<Unit>()
            val postStarted = CompletableDeferred<Unit>()
            var failList = false
            val client = conversationClient(
                route = { url ->
                    when {
                        url.encodedPath.endsWith("/mark_read.json") -> {
                            postStarted.complete(Unit)
                            gate.await()
                            "{}"
                        }
                        failList && url.encodedPath == "/messages/list.json" ->
                            error("Simulated network error")
                        else -> null
                    }
                },
            )
            val vm = MessagesViewModel(client, this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 1)
            postStarted.await()
            failList = true
            vm.retry()
            // Awaited on the state rather than on the children: the gated mark-read job is
            // a child too, so joining them all here would deadlock against the gate below.
            assertIs<MessagesState.Error>(vm.state.first { it is MessagesState.Error })

            gate.complete(Unit)
            awaitChildren(coroutineContext[Job]!!)

            assertIs<MessagesState.Error>(vm.state.value)
            // The open conversation still took the read — it is what's on screen.
            assertEquals(false, vm.openThread.value?.thread?.hasUnread)
        }

    /** Reachable if a notification deep link ever opens a thread before the list loads. */
    @Test
    fun `opening a thread before the list has loaded is a no-op`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = MessagesViewModel(conversationClient(), this)

            vm.openThread(rootId = 1)
            awaitChildren(coroutineContext[Job]!!)

            assertNull(vm.openThread.value)
            assertIs<MessagesState.Loading>(vm.state.value)
        }

    /** Only reachable if a refresh dropped the conversation between render and tap. */
    @Test
    fun `opening an unknown thread is a no-op`() =
        runTest(UnconfinedTestDispatcher()) {
            val vm = MessagesViewModel(conversationClient(), this)
            vm.load("yarnie")
            awaitChildren(coroutineContext[Job]!!)

            vm.openThread(rootId = 999)
            awaitChildren(coroutineContext[Job]!!)

            assertNull(vm.openThread.value)
        }
}
