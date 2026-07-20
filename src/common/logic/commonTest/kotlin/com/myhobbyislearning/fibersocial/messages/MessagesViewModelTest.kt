package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.feed.errorApiClient
import com.myhobbyislearning.fibersocial.feed.routingApiClientCapturing
import com.myhobbyislearning.fibersocial.feed.sessionExpiredApiClient
import com.myhobbyislearning.fibersocial.feed.suspendableRoutingApiClient
import io.ktor.http.Url
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
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
}
