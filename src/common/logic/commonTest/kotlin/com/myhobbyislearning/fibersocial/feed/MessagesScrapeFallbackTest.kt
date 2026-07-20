package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.auth.ForbiddenException
import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import com.myhobbyislearning.fibersocial.messages.MessageFolder
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.HttpResponseData
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The messages read fallback (issue #396): the JSON API 403s every message read behind
 * the ungrantable `message-read` scope, so [RavelryApiClient] falls back to scraping the
 * website message box, latches, and stops retrying the doomed API route within the same
 * client. These tests drive the whole fallback through MockEngine: the API host answers
 * 403 exactly like the live server (same error body), the web host serves markup shaped
 * like the live capture of 2026-07-19.
 */
class MessagesScrapeFallbackTest {

    private val scopeError403 = """{"error":"message-read scope is required for this action"}"""

    private val inboxHtml = """
        <html><body>
        <div class="message_row message_row--unread resizable_table__row swipe_message" id="message_row_101">
          <div class="resizable_table__cell message_row__date rsp_only">Today 8:03 AM</div>
          <div class="resizable_table__cell message_row___avatar">
            <div class="message_row__username">WoolyWendy</div>
          </div>
          <div class="message_row__subject resizable_table__cell">
            <a href="/people/yarnie/messages?open=101">Yarn swap?</a>
          </div>
        </div>
        <div class="message_row message_row--read resizable_table__row-stripe swipe_message" id="message_row_100">
          <div class="resizable_table__cell message_row__date rsp_only">July 11, 2026 3:38 AM</div>
          <div class="resizable_table__cell message_row___avatar">
            <div class="message_row__username">LoopLise</div>
          </div>
          <div class="message_row__subject message_row__subject--read resizable_table__cell">
            <a href="/people/yarnie/messages?open=100">Pattern question</a>
          </div>
        </div>
        </body></html>
    """.trimIndent()

    private val fragmentRjs =
        "R.utils.positionContainer(\"message_container\", \"message_row_101\");\n" +
            "Element.update(\"message_container\", \"\\u003Cdiv class=\\\"message\\\" id=\\\"current_message\\\"\\u003E" +
            "\\u003Cdiv class=\\\"message_contents\\\"\\u003E" +
            "\\u003Cdiv class=\\\"summary\\\"\\u003E\\u003Cdiv class=\\\"from\\\"\\u003E" +
            "\\u003Ca href=\\\"https://www.ravelry.com/people/WoolyWendy\\\"\\u003EWoolyWendy\\u003C/a\\u003E\\u003C/div\\u003E\\u003C/div\\u003E" +
            "\\u003Cdiv class=\\\"body\\\"\\u003E\\u003Cdiv class=\\\"subject\\\"\\u003EYarn swap?\\u003C/div\\u003E" +
            "\\u003Cdiv class=\\\"sent\\\"\\u003ESent at 8:03 AM Today\\u003C/div\\u003E" +
            "\\u003Cdiv class=\\\"content markdown message__content\\\"\\u003E\\u003Cp\\u003EHi!\\u003C/p\\u003E\\u003C/div\\u003E" +
            "\\u003C/div\\u003E\\u003C/div\\u003E\\u003C/div\\u003E\");\n" +
            "R.messages.showMessageContainer();"

    /**
     * A client whose API host 403s message reads while everything else responds like the
     * live system. [apiListRequests] and [webRequests] expose what was actually fetched
     * so latch behavior is observable.
     */
    private class ScrapeWorld {
        val apiListRequests = mutableListOf<String>()
        val webRequests = mutableListOf<String>()
    }

    private fun scrapeFallbackClient(
        world: ScrapeWorld,
        unreadCountJson: String = """{"unread_messages_count":1}""",
        fragment: String? = null,
    ): RavelryApiClient {
        val fragmentBody = fragment
        val engine = MockEngine { request ->
            val host = request.url.host
            val path = request.url.encodedPath
            fun ok(body: String, type: ContentType = ContentType.Text.Html) =
                respond(body, HttpStatusCode.OK, headersOf("Content-Type", type.toString()))
            when {
                host == "api.ravelry.com" && path == "/current_user.json" ->
                    ok(CURRENT_USER_JSON, ContentType.Application.Json)
                host == "api.ravelry.com" && (path == "/messages/list.json" || path.startsWith("/messages/")) -> {
                    world.apiListRequests += path
                    respond(
                        scopeError403,
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                }
                host == "www.ravelry.com" -> {
                    world.webRequests += path
                    when {
                        path == "/people/message_count.json" -> ok(unreadCountJson, ContentType.Application.Json)
                        path.startsWith("/messages/") -> ok(checkNotNull(fragmentBody), ContentType.Text.JavaScript)
                        else -> ok(inboxHtml)
                    }
                }
                else -> error("Unexpected request to $host$path")
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(client, FakeFeedTokenStorage())
    }

    @Test
    fun `getMessages falls back to the website when the API demands the unobtainable scope`() = runTest {
        val world = ScrapeWorld()
        val client = scrapeFallbackClient(world)

        val page = client.getMessages(MessageFolder.INBOX)

        assertTrue(page.viaScrape)
        assertEquals(listOf(101L, 100L), page.messages.map { it.id })
        assertEquals("WoolyWendy", page.messages[0].sender?.username)
        // The web box renders the whole folder, so one page is everything.
        assertFalse(page.hasMore)
        assertTrue("/people/yarnie/messages" in world.webRequests)
    }

    @Test
    fun `the scope block latches so later reads skip the doomed API round trip`() = runTest {
        val world = ScrapeWorld()
        val client = scrapeFallbackClient(world, fragment = fragmentRjs)

        client.getMessages(MessageFolder.INBOX)
        client.getMessages(MessageFolder.SENT)
        val message = client.getMessage(101L)

        // Exactly ONE api attempt — the first read. The sent listing and the message
        // fetch went straight to the web.
        assertEquals(listOf("/messages/list.json"), world.apiListRequests)
        assertEquals("<p>Hi!</p>", message.contentHtml)
    }

    @Test
    fun `getMessages slices and filters the scraped folder client side`() = runTest {
        val world = ScrapeWorld()
        val client = scrapeFallbackClient(world)

        val first = client.getMessages(MessageFolder.INBOX, page = 1, pageSize = 1)
        assertEquals(listOf(101L), first.messages.map { it.id })
        assertTrue(first.hasMore)

        val second = client.getMessages(MessageFolder.INBOX, page = 2, pageSize = 1)
        assertEquals(listOf(100L), second.messages.map { it.id })
        assertFalse(second.hasMore)

        val unread = client.getMessages(MessageFolder.INBOX, unreadOnly = true)
        assertEquals(listOf(101L), unread.messages.map { it.id })
    }

    @Test
    fun `getMessage falls back to the website fragment and parses the body`() = runTest {
        val world = ScrapeWorld()
        val client = scrapeFallbackClient(world, fragment = fragmentRjs)

        val message = client.getMessage(101L)

        assertEquals(101L, message.id)
        assertEquals("Yarn swap?", message.subject)
        assertEquals("WoolyWendy", message.sender?.username)
        assertEquals("<p>Hi!</p>", message.contentHtml)
        assertTrue(message.readMessage)
        assertTrue("/messages/101" in world.webRequests)
    }

    @Test
    fun `hasUnreadMessages falls back to the website badge counter`() = runTest {
        val world = ScrapeWorld()
        val client = scrapeFallbackClient(world, unreadCountJson = """{"unread_messages_count":2}""")

        assertTrue(client.hasUnreadMessages())
        assertTrue("/people/message_count.json" in world.webRequests)

        val quietWorld = ScrapeWorld()
        val quiet = scrapeFallbackClient(quietWorld, unreadCountJson = """{"unread_messages_count":0}""")
        assertFalse(quiet.hasUnreadMessages())
    }

    /**
     * A client whose API host 403s message reads (latching the fallback) and whose web
     * host answers `GET /messages/{id}` — and anything else — via [web]. For the error
     * classification tests: what the WEB side does when it is not serving a fragment.
     */
    private fun webFragmentClient(
        web: MockRequestHandleScope.(path: String) -> HttpResponseData,
    ): RavelryApiClient {
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
                host == "api.ravelry.com" ->
                    respond(
                        scopeError403,
                        HttpStatusCode.Forbidden,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                else -> web(path)
            }
        }
        val client = HttpClient(engine) {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        }
        return RavelryApiClient(client, FakeFeedTokenStorage())
    }

    @Test
    fun `a forbidden web fragment surfaces as ForbiddenException not expiry`() = runTest {
        // 403 means the session is VALID but lacks permission — conflating it with expiry
        // would log the user out over a permissions problem (issue #82's distinction).
        val client = webFragmentClient { respond("", HttpStatusCode.Forbidden) }

        assertFailsWith<ForbiddenException> { client.getMessage(101L) }
    }

    @Test
    fun `a 401 on the web fragment surfaces as session expiry`() = runTest {
        val client = webFragmentClient { respond("", HttpStatusCode.Unauthorized) }

        assertFailsWith<SessionExpiredException> { client.getMessage(101L) }
    }

    @Test
    fun `a login redirect on the web fragment surfaces as session expiry`() = runTest {
        // A dead session doesn't 401 on the website — it 302s to the login page. Landing
        // anywhere off /messages/ means the fragment never came and the cookie is dead.
        val client = webFragmentClient { path ->
            if (path == "/messages/101") {
                respond(
                    "",
                    HttpStatusCode.Found,
                    headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"),
                )
            } else {
                respond("<html>please log in</html>", HttpStatusCode.OK)
            }
        }

        assertFailsWith<SessionExpiredException> { client.getMessage(101L) }
    }

    @Test
    fun `an unusable web fragment fails loud instead of inventing a message`() = runTest {
        // A server error...
        val erroring = webFragmentClient { respond("oops", HttpStatusCode.InternalServerError) }
        assertFailsWith<IllegalStateException> { erroring.getMessage(101L) }

        // ...and a 200 whose RJS carries no message both throw rather than returning a
        // fabricated empty Message the thread screen would happily render.
        val garbled = webFragmentClient { respond("alert('nope');", HttpStatusCode.OK) }
        assertFailsWith<IllegalStateException> { garbled.getMessage(101L) }
    }

    @Test
    fun `hasUnreadMessages fails loud when the counter serves something else`() = runTest {
        // A counter body that isn't the JSON badge shape must throw — silently answering
        // "no unread" would permanently darken the drawer dot.
        val client = webFragmentClient { respond("<html>login page</html>", HttpStatusCode.OK) }

        assertFailsWith<IllegalStateException> { client.hasUnreadMessages() }
    }

    @Test
    fun `sent folder scrapes the sent page and archived scrapes saved`() = runTest {
        val world = ScrapeWorld()
        val client = scrapeFallbackClient(world)

        client.getMessages(MessageFolder.SENT)
        client.getMessages(MessageFolder.ARCHIVED)

        assertTrue("/people/yarnie/messages/sent" in world.webRequests)
        // Ravelry's API calls this box "archived"; the website calls it "saved".
        assertTrue("/people/yarnie/messages/saved" in world.webRequests)
    }
}
