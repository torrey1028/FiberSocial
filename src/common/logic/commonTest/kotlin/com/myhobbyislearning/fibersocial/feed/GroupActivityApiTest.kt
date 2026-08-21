package com.myhobbyislearning.fibersocial.feed

import com.myhobbyislearning.fibersocial.auth.ForbiddenException
import com.myhobbyislearning.fibersocial.auth.SessionExpiredException
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class GroupActivityApiTest {

    private fun htmlClient(engine: MockEngine) = RavelryApiClient(
        io.ktor.client.HttpClient(engine),
        FakeFeedTokenStorage(),
    )

    private fun io.ktor.client.engine.mock.MockRequestHandleScope.respondHtml(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf("Content-Type", ContentType.Text.Html.toString()))

    /** Captures the URL of the last request the client made. */
    private fun capturingClient(body: String, captured: MutableList<Url>) =
        htmlClient(
            MockEngine { request ->
                captured += request.url
                respondHtml(body, HttpStatusCode.OK)
            },
        )

    // --- the route -------------------------------------------------------------------

    @Test
    fun `requests the browse route rather than the sticky pretty url`() = runTest {
        // The pretty /groups/{permalink}/activity renders whatever type filter the user
        // last chose on the Ravelry WEBSITE — sticky server-side, invisible in the URL — so
        // requesting it would silently inherit a browser preference and could drop seven of
        // the eight activity types with nothing indicating anything was missing.
        val urls = mutableListOf<Url>()
        capturingClient(GROUP_ACTIVITY_PAGE_HTML, urls)
            .getGroupActivityPage("kirkland-fiber-arts-circle-2", page = 1)

        val url = urls.single()
        assertEquals("/groups/browse/activity/kirkland-fiber-arts-circle-2", url.encodedPath)
        assertEquals("www.ravelry.com", url.host)
    }

    @Test
    fun `asks for all eight activity types`() = runTest {
        // An omitted type is content silently gone, so this is not a preference — it is
        // the difference between showing a member's feed and showing part of it.
        val urls = mutableListOf<Url>()
        capturingClient(GROUP_ACTIVITY_PAGE_HTML, urls).getGroupActivityPage("g", page = 1)

        val params = urls.single().parameters
        (1..8).forEach { assertEquals("1", params["type_$it"], "missing type_$it") }
    }

    @Test
    fun `passes the page number through`() = runTest {
        val urls = mutableListOf<Url>()
        capturingClient(GROUP_ACTIVITY_PAGE_HTML, urls).getGroupActivityPage("g", page = 7)
        assertEquals("7", urls.single().parameters["page"])
    }

    // --- parsing through the client ---------------------------------------------------

    @Test
    fun `returns the parsed page`() = runTest {
        val page = htmlClient(MockEngine { respondHtml(GROUP_ACTIVITY_PAGE_HTML) })
            .getGroupActivityPage("kirkland-fiber-arts-circle-2", page = 1)

        assertEquals(8, page.items.size)
        assertTrue(page.hasMore)
        assertEquals(page.items.map { it.id }.sortedDescending(), page.items.map { it.id })
    }

    @Test
    fun `reports no more pages on the last page`() = runTest {
        val lastPage = """
            <html><body>
            <div class="page_links"><div class="next_page next_page--empty">&nbsp;</div></div>
            <div id="recent_activity"></div>
            </body></html>
        """.trimIndent()
        val page = htmlClient(MockEngine { respondHtml(lastPage) }).getGroupActivityPage("g", page = 3)
        assertFalse(page.hasMore)
    }

    // --- error taxonomy ---------------------------------------------------------------

    @Test
    fun `throws ForbiddenException on 403 rather than bouncing to login`() = runTest {
        // 403 means the cookie is fine but this group's activity is off-limits — a
        // permission problem, not an expired session, so it must not force a re-login.
        val client = htmlClient(MockEngine { respond("", HttpStatusCode.Forbidden) })
        val e = assertFailsWith<ForbiddenException> { client.getGroupActivityPage("g", 1) }
        // FeedErrorState pattern-matches "401"/"403" in error text to detect expiry, so the
        // message must not contain either or this routes back to the session-expired UI.
        val message = e.message ?: ""
        assertFalse(message.contains("403"))
        assertFalse(message.contains("401"))
    }

    @Test
    fun `throws SessionExpiredException on 401`() = runTest {
        val client = htmlClient(MockEngine { respond("", HttpStatusCode.Unauthorized) })
        assertFailsWith<SessionExpiredException> { client.getGroupActivityPage("g", 1) }
    }

    @Test
    fun `throws SessionExpiredException when redirected to the login page`() = runTest {
        // An expired cookie doesn't 401 on www.ravelry.com — it 302s to the login page,
        // which Ktor follows to a 200 with no activity markup. Parsing that as an empty
        // page would render an expired session as "this group is quiet".
        val client = htmlClient(
            MockEngine { request ->
                if (request.url.encodedPath.startsWith("/groups/")) {
                    respond(
                        "",
                        HttpStatusCode.Found,
                        headersOf(HttpHeaders.Location, "https://www.ravelry.com/account/login"),
                    )
                } else {
                    respondHtml("<html><body>please log in</body></html>", HttpStatusCode.OK)
                }
            },
        )
        assertFailsWith<SessionExpiredException> { client.getGroupActivityPage("g", 1) }
    }

    @Test
    fun `throws on any other non-2xx response`() = runTest {
        val client = htmlClient(MockEngine { respond("", HttpStatusCode.InternalServerError) })
        assertFailsWith<IllegalStateException> { client.getGroupActivityPage("g", 1) }
    }
}
