package com.myhobbyislearning.fibersocial.messages

import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.auth.TokenStorage
import com.myhobbyislearning.fibersocial.feed.FakeFeedTokenStorage
import com.myhobbyislearning.fibersocial.feed.RavelryApiClient
import com.myhobbyislearning.fibersocial.feed.parseRavelryTimestamp
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json

private const val ME = "yarnie"

/** One request the composer made: which endpoint, and what it put in the body. */
private data class Recorded(val path: String, val body: String)

/**
 * A client that records every request (path AND body) and can be told to refuse specific
 * paths with a status code.
 *
 * The body matters here in a way it doesn't for the read-side tests: the subject and content
 * are user-authored free text that MUST travel in the body rather than the query string, and
 * the reply's derived `Re:` subject is only observable there.
 */
private fun composeApiClient(
    recorded: MutableList<Recorded>,
    storage: TokenStorage = FakeFeedTokenStorage(),
    status: (path: String) -> HttpStatusCode = { HttpStatusCode.OK },
    route: (path: String) -> String,
): RavelryApiClient {
    val engine = MockEngine { request ->
        val body = (request.body as? OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString().orEmpty()
        recorded += Recorded(request.url.encodedPath, body)
        val code = status(request.url.encodedPath)
        respond(
            content = if (code == HttpStatusCode.OK) route(request.url.encodedPath) else """{"errors":["no"]}""",
            status = code,
            headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
        )
    }
    val client = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    return RavelryApiClient(client, storage)
}

private fun messageJson(
    id: Long,
    sender: String? = "friend",
    recipient: String? = ME,
    subject: String = "Yarn talk",
    parentId: Long? = null,
    sentAt: String? = "2026/07/03 10:00:00 +0000",
): String {
    fun user(name: String?) = if (name == null) "null" else """{"username":"$name"}"""
    return """
        {
          "id": $id,
          "subject": "$subject",
          "sender": ${user(sender)},
          "recipient": ${user(recipient)},
          "sent_at": ${sentAt?.let { "\"$it\"" } ?: "null"},
          "read_message": true,
          "parent_message_id": ${parentId ?: "null"},
          "content_html": "<p>Body $id</p>"
        }
    """.trimIndent()
}

private fun listJson(vararg messages: String) = """
    {
      "messages": [${messages.joinToString(",")}],
      "paginator": {"page": 1, "page_count": 1, "results": ${messages.size}}
    }
"""

private fun searchJson(vararg usernames: String) = """
    {"results": [${
    usernames.joinToString(",") {
        """{"title":"$it","tiny_image_url":"https://example.com/$it.jpg",
           "record":{"type":"User","id":${it.length},"permalink":"$it"}}"""
    }
}]}
"""

private const val EMPTY_LIST_JSON =
    """{"messages":[],"paginator":{"page":1,"page_count":1,"results":0}}"""

/** The one-message inbox every send/reply test starts from: `friend` wrote to us. */
private fun inboxWithOneInboundMessage() = listJson(messageJson(id = 1))

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class MessageComposeViewModelTest {

    /**
     * Drains every coroutine the ViewModel spawned, repeatedly until none are left.
     *
     * NOT `awaitChildren(coroutineContext[Job]!!)`. Ktor's [MockEngine] dispatches request handling via
     * `withContext(Dispatchers.IO)` regardless of the calling coroutine's dispatcher, so a
     * mocked request genuinely runs on a real background thread that virtual time knows
     * nothing about — advancing the scheduler returns long before the response lands. Joining
     * the jobs waits for the real work AND lets `runTest` advance the debounce delay.
     *
     * Loops until the child list is empty because a send spawns work that spawns more (this
     * is `FeedViewModelTest.awaitChildren`'s fix, #387, for the same reason).
     */
    private suspend fun awaitChildren(job: Job) {
        while (true) {
            val children = job.children.toList()
            if (children.isEmpty()) return
            children.forEach { it.join() }
        }
    }

    /** Asserts the session-expiry signal did NOT fire. Virtual time, so it costs nothing. */
    private suspend fun assertNoSignOut(vm: MessagesViewModel) = assertNull(
        withTimeoutOrNull(1_000) { vm.sessionExpired.first() },
        "this failure must never sign the user out (issue #82)",
    )

    // ---- recipient search ----------------------------------------------------------

    @Test
    fun `recipient search debounces to one request for the final query`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) { searchJson("yarnbarn") },
                this,
            )
            vm.searchRecipients("y")
            vm.searchRecipients("ya")
            vm.searchRecipients("yar")
            vm.searchRecipients("yarn")
            awaitChildren(coroutineContext[Job]!!)

            val searches = recorded.filter { it.path == "/search.json" }
            assertEquals(1, searches.size, "only the last keystroke should reach the network")
            assertIs<RecipientSearchState.Results>(vm.recipientSearch.value)
        }

    @Test
    fun `recipient search maps results into the picker`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val vm = MessagesViewModel(composeApiClient(recorded) { searchJson("yarnbarn", "yarnly") }, this)
        vm.searchRecipients("yarn")
        awaitChildren(coroutineContext[Job]!!)

        val results = assertIs<RecipientSearchState.Results>(vm.recipientSearch.value)
        assertEquals(listOf("yarnbarn", "yarnly"), results.users.map { it.username })
        assertEquals("https://example.com/yarnbarn.jpg", results.users.first().avatarUrl)
    }

    @Test
    fun `a query shorter than the minimum never reaches the network`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(composeApiClient(recorded) { searchJson("yarnbarn") }, this)
            vm.searchRecipients("y")
            vm.searchRecipients("   ")
            awaitChildren(coroutineContext[Job]!!)

            assertTrue(recorded.none { it.path == "/search.json" })
            assertEquals(RecipientSearchState.Idle, vm.recipientSearch.value)
        }

    @Test
    fun `a failed recipient search reports without clearing the composer`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MockEngine { error("Simulated network error") }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = MessagesViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)
            vm.searchRecipients("yarn")
            awaitChildren(coroutineContext[Job]!!)

            val error = assertIs<RecipientSearchState.Error>(vm.recipientSearch.value)
            assertTrue(error.message.isNotBlank())
            // The send state is untouched: a bad search must not look like a failed send.
            assertEquals(SendMessageState.Idle, vm.sendState.value)
        }

    @Test
    fun `a session expiry during recipient search signals rather than erroring`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MockEngine { throw SessionExpiredException("Token expired") }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = MessagesViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)

            vm.searchRecipients("yarn")
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(Unit, vm.sessionExpired.first())
            assertEquals(RecipientSearchState.Idle, vm.recipientSearch.value)
        }

    // ---- sending a new conversation -------------------------------------------------

    @Test
    fun `sending a new message posts to create with the chosen recipient`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) { path ->
                    if (path == "/messages/create.json") {
                        """{"message":${messageJson(id = 50, sender = ME, recipient = "yarnbarn")}}"""
                    } else {
                        EMPTY_LIST_JSON
                    }
                },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendNewMessage("yarnbarn", "Hello", "Want to swap yarn?")
            awaitChildren(coroutineContext[Job]!!)

            val create = recorded.single { it.path == "/messages/create.json" }
            assertTrue(create.body.contains("recipient_username=yarnbarn"), create.body)
            assertTrue(create.body.contains("subject=Hello"), create.body)
            // Free text in the BODY, never the query string — the recurring bug class.
            assertTrue(create.body.contains("content=Want"), create.body)
            assertIs<SendMessageState.Sent>(vm.sendState.value)
        }

    @Test
    fun `a sent message appears in the list without a refetch`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) { path ->
                    if (path == "/messages/create.json") {
                        """{"message":${messageJson(id = 50, sender = ME, recipient = "yarnbarn", subject = "Hello")}}"""
                    } else {
                        EMPTY_LIST_JSON
                    }
                },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)
            val listCallsBefore = recorded.count { it.path == "/messages/list.json" }

            vm.sendNewMessage("yarnbarn", "Hello", "Want to swap yarn?")
            awaitChildren(coroutineContext[Job]!!)

            val loaded = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(listOf(50L), loaded.threads.map { it.rootId })
            assertEquals("Hello", loaded.threads.single().subject)
            assertEquals("yarnbarn", loaded.threads.single().counterpart?.username)
            // The whole point: no list request was issued to make that happen.
            assertEquals(listCallsBefore, recorded.count { it.path == "/messages/list.json" })
        }

    @Test
    fun `a sent message with no timestamp still sorts to the top of the mailbox`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                apiClient = composeApiClient(recorded) { path ->
                    if (path == "/messages/create.json") {
                        // Ravelry's create response omitting sent_at — the case that would
                        // otherwise sink the new conversation to the bottom of the list.
                        """{"message":${messageJson(id = 50, sender = ME, recipient = "yarnbarn", sentAt = null)}}"""
                    } else {
                        listJson(messageJson(id = 1))
                    }
                },
                scope = this,
                // Later than the fixture message's 2026/07/03, so the assertion is that the
                // stamped conversation sorts above a real one rather than merely existing.
                now = { 1_790_000_000_000 },
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendNewMessage("yarnbarn", "Hello", "Want to swap yarn?")
            awaitChildren(coroutineContext[Job]!!)

            val loaded = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(50L, loaded.threads.first().rootId)
            assertEquals(1_790_000_000_000, loaded.threads.first().lastActivityAt)
        }

    @Test
    fun `an unnamed sender on the create response is still attributed to the user`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) { path ->
                    if (path == "/messages/create.json") {
                        """{"message":${messageJson(id = 50, sender = null, recipient = null)}}"""
                    } else {
                        EMPTY_LIST_JSON
                    }
                },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendNewMessage("yarnbarn", "Hello", "Hi there")
            awaitChildren(coroutineContext[Job]!!)

            val sent = assertIs<SendMessageState.Sent>(vm.sendState.value).message
            assertEquals(ME, sent.sender?.username)
            assertEquals("yarnbarn", sent.recipient?.username)
            assertEquals(
                MessageDirection.OUTBOUND,
                messageDirection(sent, ME),
                "an unattributed send would render as someone else's message",
            )
        }

    // ---- replying -------------------------------------------------------------------

    @Test
    fun `replying posts to reply and never to create`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val vm = MessagesViewModel(
            composeApiClient(recorded) { path ->
                if (path == "/messages/1/reply.json") {
                    """{"message":${messageJson(id = 2, sender = ME, recipient = "friend", parentId = 1)}}"""
                } else {
                    inboxWithOneInboundMessage()
                }
            },
            this,
        )
        vm.load(ME)
        awaitChildren(coroutineContext[Job]!!)
        vm.openThread(1)
        awaitChildren(coroutineContext[Job]!!)

        vm.sendReply(rootId = 1, content = "Yes please")
        awaitChildren(coroutineContext[Job]!!)

        assertEquals(1, recorded.count { it.path == "/messages/1/reply.json" })
        assertTrue(
            recorded.none { it.path == "/messages/create.json" },
            "a reply sent through create.json fragments the conversation silently",
        )
        assertIs<SendMessageState.Sent>(vm.sendState.value)
    }

    @Test
    fun `a reply carries the root subject prefixed once`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val vm = MessagesViewModel(
            composeApiClient(recorded) { path ->
                if (path == "/messages/1/reply.json") {
                    """{"message":${messageJson(id = 2, sender = ME, recipient = "friend", parentId = 1)}}"""
                } else {
                    // The root already says "Re:" — a naive prefix would make it "Re: Re:".
                    listJson(messageJson(id = 1, subject = "Re: Yarn talk"))
                }
            },
            this,
        )
        vm.load(ME)
        awaitChildren(coroutineContext[Job]!!)
        vm.openThread(1)
        awaitChildren(coroutineContext[Job]!!)

        vm.sendReply(rootId = 1, content = "Yes please")
        awaitChildren(coroutineContext[Job]!!)

        val reply = recorded.single { it.path == "/messages/1/reply.json" }
        assertTrue(reply.body.contains("subject=Re%3A+Yarn+talk"), reply.body)
        assertFalse(reply.body.contains("Re%3A+Re%3A"), reply.body)
    }

    @Test
    fun `a reply appears in its open thread without a refetch`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) { path ->
                    if (path == "/messages/1/reply.json") {
                        """{"message":${messageJson(id = 2, sender = ME, recipient = "friend", parentId = 1)}}"""
                    } else {
                        inboxWithOneInboundMessage()
                    }
                },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)
            vm.openThread(1)
            awaitChildren(coroutineContext[Job]!!)
            val listCallsBefore = recorded.count { it.path == "/messages/list.json" }

            vm.sendReply(rootId = 1, content = "Yes please")
            awaitChildren(coroutineContext[Job]!!)

            val open = vm.openThread.first()
            assertEquals(listOf(1L, 2L), open?.thread?.messages?.map { it.id })
            // Still ONE conversation — the reply's parent pointer kept it together.
            val loaded = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(1, loaded.threads.size)
            assertEquals(listCallsBefore, recorded.count { it.path == "/messages/list.json" })
        }

    @Test
    fun `replying to a conversation with nothing inbound explains rather than using create`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                // Only an OUTBOUND message: we wrote first and nobody has answered, so
                // Ravelry has nothing we are allowed to reply to.
                composeApiClient(recorded) { listJson(messageJson(id = 1, sender = ME, recipient = "friend")) },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)
            vm.openThread(1)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendReply(rootId = 1, content = "Any thoughts?")
            awaitChildren(coroutineContext[Job]!!)

            val error = assertIs<SendMessageState.Error>(vm.sendState.value)
            assertTrue(error.message.contains("friend"), error.message)
            assertTrue(recorded.none { it.path == "/messages/create.json" })
            assertTrue(recorded.none { it.path.endsWith("/reply.json") })
        }

    @Test
    fun `replying to an unknown conversation reports it and sends nothing`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(composeApiClient(recorded) { EMPTY_LIST_JSON }, this)
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendReply(rootId = 999, content = "Hello")
            awaitChildren(coroutineContext[Job]!!)

            assertIs<SendMessageState.Error>(vm.sendState.value)
            assertTrue(recorded.none { it.path.endsWith("/reply.json") })
        }

    // ---- validation ------------------------------------------------------------------

    @Test
    fun `a blank subject blocks the send`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val vm = MessagesViewModel(composeApiClient(recorded) { EMPTY_LIST_JSON }, this)

        vm.sendNewMessage("yarnbarn", "   ", "A real body")
        awaitChildren(coroutineContext[Job]!!)

        assertIs<SendMessageState.Error>(vm.sendState.value)
        assertTrue(recorded.none { it.path == "/messages/create.json" })
    }

    @Test
    fun `a blank body blocks the send`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val vm = MessagesViewModel(composeApiClient(recorded) { EMPTY_LIST_JSON }, this)

        vm.sendNewMessage("yarnbarn", "A subject", "  \n ")
        awaitChildren(coroutineContext[Job]!!)

        assertIs<SendMessageState.Error>(vm.sendState.value)
        assertTrue(recorded.none { it.path == "/messages/create.json" })
    }

    @Test
    fun `a missing recipient blocks the send`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val vm = MessagesViewModel(composeApiClient(recorded) { EMPTY_LIST_JSON }, this)

        vm.sendNewMessage("", "A subject", "A real body")
        awaitChildren(coroutineContext[Job]!!)

        assertIs<SendMessageState.Error>(vm.sendState.value)
        assertTrue(recorded.none { it.path == "/messages/create.json" })
    }

    @Test
    fun `a blank reply body blocks the send`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val vm = MessagesViewModel(composeApiClient(recorded) { inboxWithOneInboundMessage() }, this)
        vm.load(ME)
        awaitChildren(coroutineContext[Job]!!)
        vm.openThread(1)
        awaitChildren(coroutineContext[Job]!!)

        vm.sendReply(rootId = 1, content = "   ")
        awaitChildren(coroutineContext[Job]!!)

        assertIs<SendMessageState.Error>(vm.sendState.value)
        assertTrue(recorded.none { it.path.endsWith("/reply.json") })
    }

    @Test
    fun `a second send is ignored while one is in flight`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val gate = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            recorded += Recorded(path, "")
            if (path == "/messages/create.json") gate.await()
            respond(
                content = if (path == "/messages/create.json") {
                    """{"message":${messageJson(id = 50, sender = ME, recipient = "yarnbarn")}}"""
                } else {
                    EMPTY_LIST_JSON
                },
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = MessagesViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)

        vm.sendNewMessage("yarnbarn", "Hello", "First")
        assertEquals(SendMessageState.Sending, vm.sendState.value)
        vm.sendNewMessage("yarnbarn", "Hello", "Second")
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)

        assertEquals(1, recorded.count { it.path == "/messages/create.json" })
    }

    // ---- error paths ------------------------------------------------------------------

    @Test
    fun `a messaging refusal surfaces its own copy and never signs the user out`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(
                    recorded,
                    status = { path ->
                        if (path == "/messages/create.json") HttpStatusCode.Forbidden else HttpStatusCode.OK
                    },
                ) { EMPTY_LIST_JSON },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendNewMessage("yarnbarn", "Hello", "Want to swap yarn?")
            awaitChildren(coroutineContext[Job]!!)

            val error = assertIs<SendMessageState.Error>(vm.sendState.value)
            assertTrue(error.messagingBlocked, "a 403 here is a permission problem, not a failure")
            assertTrue(error.message.contains("private messages"), error.message)
            assertTrue(error.message.contains("signing out"), error.message)
            // Issue #82: neither the digits that route to the session-expired UI, nor a
            // signal that would actually log the user out.
            assertFalse(error.message.contains("403"), error.message)
            assertFalse(error.message.contains("401"), error.message)
            assertNoSignOut(vm)
        }

    @Test
    fun `a refused reply is reported the same way as a refused new message`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(
                    recorded,
                    status = { path ->
                        if (path.endsWith("/reply.json")) HttpStatusCode.Forbidden else HttpStatusCode.OK
                    },
                ) { inboxWithOneInboundMessage() },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)
            vm.openThread(1)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendReply(rootId = 1, content = "Yes please")
            awaitChildren(coroutineContext[Job]!!)

            val error = assertIs<SendMessageState.Error>(vm.sendState.value)
            assertTrue(error.messagingBlocked)
            // The thread is untouched — nothing was appended for a message that never sent.
            assertEquals(listOf(1L), vm.openThread.value?.thread?.messages?.map { it.id })
        }

    @Test
    fun `a network failure while sending keeps the composer usable`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(
                    recorded,
                    status = { path ->
                        if (path == "/messages/create.json") {
                            HttpStatusCode.InternalServerError
                        } else {
                            HttpStatusCode.OK
                        }
                    },
                ) { EMPTY_LIST_JSON },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendNewMessage("yarnbarn", "Hello", "Want to swap yarn?")
            awaitChildren(coroutineContext[Job]!!)

            val error = assertIs<SendMessageState.Error>(vm.sendState.value)
            assertFalse(error.messagingBlocked)
            assertEquals(0, assertIs<MessagesState.Loaded>(vm.state.value).threads.size)
        }

    @Test
    fun `a session expiry while sending signals rather than erroring`() =
        runTest(UnconfinedTestDispatcher()) {
            val engine = MockEngine { throw SessionExpiredException("Token expired") }
            val client = HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
            val vm = MessagesViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)

            vm.sendNewMessage("yarnbarn", "Hello", "Want to swap yarn?")
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(Unit, vm.sessionExpired.first())
            assertEquals(SendMessageState.Idle, vm.sendState.value)
        }

    // ---- composer lifecycle -----------------------------------------------------------

    @Test
    fun `resetCompose clears a finished send and the picker`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(composeApiClient(recorded) { searchJson("yarnbarn") }, this)
            vm.searchRecipients("yarn")
            awaitChildren(coroutineContext[Job]!!)
            vm.sendNewMessage("", "", "")

            vm.resetCompose()

            assertEquals(SendMessageState.Idle, vm.sendState.value)
            assertEquals(RecipientSearchState.Idle, vm.recipientSearch.value)
        }

    @Test
    fun `acknowledgeSent clears only a completed send`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val vm = MessagesViewModel(
            composeApiClient(recorded) { path ->
                if (path == "/messages/create.json") {
                    """{"message":${messageJson(id = 50, sender = ME, recipient = "yarnbarn")}}"""
                } else {
                    EMPTY_LIST_JSON
                }
            },
            this,
        )
        vm.load(ME)
        awaitChildren(coroutineContext[Job]!!)
        vm.sendNewMessage("yarnbarn", "Hello", "Hi")
        awaitChildren(coroutineContext[Job]!!)
        assertIs<SendMessageState.Sent>(vm.sendState.value)

        vm.acknowledgeSent()
        assertEquals(SendMessageState.Idle, vm.sendState.value)
        // Idempotent: a second acknowledgement of nothing is a no-op.
        vm.acknowledgeSent()
        assertEquals(SendMessageState.Idle, vm.sendState.value)
    }

    // ---- edge cases the composer will actually meet ------------------------------------

    /**
     * Replying with the thread list open but the thread CLOSED. Reachable because
     * `closeThread()` does not cancel work in flight, and the reply lookup falls back to the
     * list — it must still find the conversation and still use `reply.json`.
     */
    @Test
    fun `a reply still finds its conversation after the thread is closed`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) { path ->
                    if (path == "/messages/1/reply.json") {
                        """{"message":${messageJson(id = 2, sender = ME, recipient = "friend", parentId = 1)}}"""
                    } else {
                        inboxWithOneInboundMessage()
                    }
                },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)
            vm.openThread(1)
            awaitChildren(coroutineContext[Job]!!)
            vm.closeThread()

            vm.sendReply(rootId = 1, content = "Yes please")
            awaitChildren(coroutineContext[Job]!!)

            assertEquals(1, recorded.count { it.path == "/messages/1/reply.json" })
            assertTrue(recorded.none { it.path == "/messages/create.json" })
        }

    /**
     * A conversation Ravelry named nobody in — a system notice or a deleted account — that
     * also has nothing inbound. The explanation must still read as a sentence rather than
     * interpolating a null.
     */
    @Test
    fun `an unrepliable conversation with no named counterpart still explains itself`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) {
                    listJson(messageJson(id = 1, sender = ME, recipient = null))
                },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)
            vm.openThread(1)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendReply(rootId = 1, content = "Any thoughts?")
            awaitChildren(coroutineContext[Job]!!)

            val error = assertIs<SendMessageState.Error>(vm.sendState.value)
            assertTrue(error.message.contains("the other person writes back"), error.message)
            assertFalse(error.message.contains("null"), error.message)
        }

    @Test
    fun `a reply on a subjectless conversation sends a subject anyway`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) { path ->
                    if (path == "/messages/1/reply.json") {
                        """{"message":${messageJson(id = 2, sender = ME, recipient = "friend", parentId = 1)}}"""
                    } else {
                        listJson(messageJson(id = 1, subject = ""))
                    }
                },
                this,
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)
            vm.openThread(1)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendReply(rootId = 1, content = "Yes please")
            awaitChildren(coroutineContext[Job]!!)

            // Ravelry would reject an empty subject, so the placeholder is what goes out.
            val reply = recorded.single { it.path == "/messages/1/reply.json" }
            assertTrue(reply.body.contains("subject=Re%3A+%28no+subject%29"), reply.body)
        }

    @Test
    fun `a second reply is ignored while one is in flight`() = runTest(UnconfinedTestDispatcher()) {
        val recorded = mutableListOf<Recorded>()
        val gate = CompletableDeferred<Unit>()
        val engine = MockEngine { request ->
            val path = request.url.encodedPath
            recorded += Recorded(path, "")
            if (path == "/messages/1/reply.json") gate.await()
            respond(
                content = if (path == "/messages/1/reply.json") {
                    """{"message":${messageJson(id = 2, sender = ME, recipient = "friend", parentId = 1)}}"""
                } else {
                    inboxWithOneInboundMessage()
                },
                status = HttpStatusCode.OK,
                headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
            )
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        val vm = MessagesViewModel(RavelryApiClient(client, FakeFeedTokenStorage()), this)
        vm.load(ME)
        awaitChildren(coroutineContext[Job]!!)
        vm.openThread(1)
        awaitChildren(coroutineContext[Job]!!)

        vm.sendReply(rootId = 1, content = "Yes please")
        assertEquals(SendMessageState.Sending, vm.sendState.value)
        vm.sendReply(rootId = 1, content = "Yes please again")
        // resetCompose must not clear an in-flight send either — the result still has to land.
        vm.resetCompose()
        assertEquals(SendMessageState.Sending, vm.sendState.value)
        gate.complete(Unit)
        awaitChildren(coroutineContext[Job]!!)

        assertEquals(1, recorded.count { it.path == "/messages/1/reply.json" })
        assertIs<SendMessageState.Sent>(vm.sendState.value)
    }

    /**
     * A `sent_at` in some shape the app's own parser doesn't read. Same consequence as an
     * absent one — the conversation would sort last — so it gets the same local stamp.
     */
    @Test
    fun `an unparseable timestamp on the create response is replaced`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                apiClient = composeApiClient(recorded) { path ->
                    if (path == "/messages/create.json") {
                        """{"message":{"id":50,"subject":"Hello","sent_at":"2026-05-28T20:26:40Z"}}"""
                    } else {
                        EMPTY_LIST_JSON
                    }
                },
                scope = this,
                now = { 1_790_000_000_000 },
            )
            vm.load(ME)
            awaitChildren(coroutineContext[Job]!!)

            vm.sendNewMessage("yarnbarn", "Hello", "Hi")
            awaitChildren(coroutineContext[Job]!!)

            val loaded = assertIs<MessagesState.Loaded>(vm.state.value)
            assertEquals(1_790_000_000_000, loaded.threads.single().lastActivityAt)
        }

    /**
     * Sending before the feed has resolved the signed-in user. Nothing may be INVENTED for
     * the sender — a fabricated username would be wrong about who wrote the message — so the
     * message stays unattributed until the next refresh brings Ravelry's own copy.
     */
    @Test
    fun `a send with no known username leaves the sender unattributed`() =
        runTest(UnconfinedTestDispatcher()) {
            val recorded = mutableListOf<Recorded>()
            val vm = MessagesViewModel(
                composeApiClient(recorded) { path ->
                    if (path == "/messages/create.json") {
                        """{"message":${messageJson(id = 50, sender = null, recipient = null)}}"""
                    } else {
                        EMPTY_LIST_JSON
                    }
                },
                this,
            )
            // No load(), so currentUsername is still blank.
            vm.sendNewMessage("yarnbarn", "Hello", "Hi")
            awaitChildren(coroutineContext[Job]!!)

            val sent = assertIs<SendMessageState.Sent>(vm.sendState.value).message
            assertNull(sent.sender)
            // The recipient is still filled in — that one we do know, the user picked it.
            assertEquals("yarnbarn", sent.recipient?.username)
        }

    // ---- pure helpers -------------------------------------------------------------------

    @Test
    fun `replySubject prefixes an unprefixed subject exactly once`() {
        assertEquals("Re: Yarn talk", replySubject("Yarn talk"))
        assertEquals("Re: Yarn talk", replySubject("Re: Yarn talk"))
        assertEquals("RE: Yarn talk", replySubject("RE: Yarn talk"))
        assertEquals("re: Yarn talk", replySubject("re: Yarn talk"))
        assertEquals("Re: (no subject)", replySubject("   "))
    }

    @Test
    fun `ravelryTimestamp writes the format the parser reads back`() {
        val formatted = ravelryTimestamp(1_780_000_000_000)
        assertEquals("2026/05/28 20:26:40 +0000", formatted)
        // The round trip is the real contract: a stamp the app's own parser rejects would
        // leave the message with no usable sent_at after all.
        assertEquals(1_780_000_000_000, parseRavelryTimestamp(formatted)?.toEpochMilliseconds())
    }

    @Test
    fun `ravelryTimestamp zero-pads single-digit fields`() {
        // 01:00:05 on January 1st — every field is the one that would lose its leading zero.
        assertEquals("2024/01/01 01:00:05 +0000", ravelryTimestamp(1_704_070_805_000))
    }
}
