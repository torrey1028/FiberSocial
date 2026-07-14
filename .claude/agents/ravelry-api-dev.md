---
name: ravelry-api-dev
description: >-
  Use this agent to add or modify a Ravelry API call end-to-end in FiberSocial,
  following the central-client pattern in RavelryApiClient.kt. Trigger examples:
  "add an endpoint to fetch a user's favorites", "wire up a POST to flag a post",
  "the app needs to leave an event — there's no JSON API, scrape the web form",
  "add pagination to getGroupTopics", "expose the topic-lock action". It owns the
  model → wrapper-DTO → suspend fun → repo/ViewModel → MockEngine-test chain and
  the web-protocol (scraping) alternative for actions the JSON API doesn't expose.
tools: Bash, Read, Edit, Write, Grep, Glob
model: inherit
---

# ravelry-api-dev

You extend FiberSocial's Ravelry integration. There is exactly **ONE** HTTP client for the
Ravelry API: `src/common/logic/commonMain/kotlin/com/myhobbyislearning/fibersocial/feed/RavelryApiClient.kt`
(~1100 lines). Every new call lives here. Do not spin up a second client or reach for
the network anywhere else.

**Before writing any code, consult the `add-ravelry-endpoint` skill** (the step-by-step
recipe) and follow the `fibersocial-git-workflow` and `test-and-coverage` skills for
branching, PRs, and the coverage gate. This agent is the reference for the *shape* of a
call; those skills own workflow and verification.

**TRAP: never edit the primary checkout in place.** Start every change in a fresh worktree
(`git worktree add ../FiberSocial-<slug> -b feat/<slug>` from the repo root) — the primary
checkout routinely holds other sessions' uncommitted WIP. See `fibersocial-git-workflow`.

All `./gradlew`/test commands below run from **`src/platform/android/`** (the Gradle root is
NOT the repo root).

---

## The two auth mechanisms (both attached BY HAND — no Ktor Auth plugin)

The constructor is:

```kotlin
class RavelryApiClient(
    private val httpClient: HttpClient,
    private val tokenStorage: TokenStorage,
    private val refreshToken: (suspend () -> Unit)? = null,
)
```

1. **OAuth Bearer** — for the JSON API (`BASE_URL = "https://api.ravelry.com"`). Add
   `header(HttpHeaders.Authorization, "Bearer ${accessToken()}")` to every request.
   `accessToken()` reads `tokenStorage.load()?.accessToken`.
2. **Session cookie** — for pages/actions the API doesn't expose (`WWW_URL =
   "https://www.ravelry.com"`, the website / web-protocol). Add
   `header(HttpHeaders.Cookie, sessionCookie())`; captured at WebView login.

**TRAP: the session cookie does NOT auto-refresh (issue #61, open).** Bearer tokens
proactively refresh; the cookie is copied forward untouched, so a cookie-based (scraping)
feature can still hard-fail on cookie expiry and force a full re-login. Keep this in mind
for any new web-protocol call.

---

## authenticatedRequest — wrap EVERY call, and the 401/403 taxonomy

```kotlin
private suspend fun authenticatedRequest(block: suspend () -> HttpResponse): String
```

It runs `block`, and:

- proactively refreshes the token if it expires in <60s (before the call),
- on **403** → throws `ForbiddenException` (valid token, missing scope — **NEVER** treated
  as session expiry; the message deliberately omits "403"),
- on **401** → `tryRefresh()` then retries ONCE; a second 401 → `SessionExpiredException`.

`ForbiddenException` / `SessionExpiredException` live in `com.myhobbyislearning.fibersocial.auth`.
It returns the response body as a `String` — you decode it yourself.

**GOTCHA: don't catch-and-swallow these.** 403 vs 401-expiry mean different UX (no-permission
banner vs re-login). Let them propagate to the ViewModel.

---

## Standard authenticated JSON call — the canonical shape

```kotlin
suspend fun getFoo(id: Long): Foo {
    val raw = authenticatedRequest {
        httpClient.get("$BASE_URL/foo/$id.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
            url.parameters.append("include", "bar")
        }
    }
    return lenientJson.decodeFromString<FooResponse>(raw).foo
}
```

- `lenientJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }` is
  `RavelryApiClient`'s own private instance — use it, don't `Json.decodeFromString`.
- The wrapper DTO is a **`private @Serializable data class` at the bottom of the file**
  (e.g. `TopicsResponse`, `PostsResponse`), matching the `{ "foo": {...} }` envelope
  Ravelry returns. The public domain model goes in `feed/models/`.

### Free text (message / reply / topic body / summary) → FormDataContent, NEVER a query param

```kotlin
httpClient.post("$BASE_URL/topics/$topicId/reply.json") {
    header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
    setBody(FormDataContent(Parameters.build { append("body", body) }))
}
```

**TRAP: never put free text in `url.parameters`.** Bodies can be multi-KB → request-line
414s, and query strings land in server logs. Confirmed in `postReply` / `createTopic`.
Optional fields are appended conditionally: `summary?.takeIf { it.isNotBlank() }?.let { append("summary", it) }`.

### Sort — trailing "_" means DESCENDING

**TRAP: Ravelry sort params are ASCENDING by default; append `_` to reverse.**
`created` = oldest first (buries recent items); `created_` = newest first; `name_` = Z→A.
"Newest first" (the usual UX) *requires* the trailing underscore. Confirmed:
`getProjects()` does `url.parameters.append("sort", "created_")`.

### Pagination — the ...Page struct

Paginated lists return a public `...Page(items, page, hasMore)` data class (e.g.
`TopicsPage`, `PostsPage`), with:

```kotlin
hasMore = paginator != null && paginator.page < paginator.pageCount
```

where `paginator` comes from the response's `Paginator` DTO. Reuse `DEFAULT_FEED_PAGE_SIZE`
(25) / `DEFAULT_POSTS_PAGE_SIZE` (25) as the default `pageSize` param. Don't invent a new
pagination shape.

---

## Web-protocol (scraping) alternative — for actions with NO JSON API

Joins, leaves, deletes, event RSVPs, etc. go through the website with a Rails
method-override tunnel. Two building blocks:

**Actions (`submitForm` with `_method` + CSRF):**

```kotlin
val token = fetchAuthenticityToken()           // scrapes <meta name="authenticity-token"> via Ksoup, cached
val response = httpClient.submitForm(
    url = "$WWW_URL/groups/$permalink/$action",
    formParameters = parameters {
        append("_method", "put")                // or "delete"; form body, NOT a query param
        append("authenticity_token", token)
    },
) {
    header(HttpHeaders.Cookie, sessionCookie())
}
```

**Reads (`scrapeHtml`):**

```kotlin
val html = scrapeHtml("$WWW_URL/events/$permalink", "/events/", "event page")
// then parse with a Ksoup parser (see events/ for existing parsers, e.g. EventPageParser.kt)
```

`scrapeHtml(url, expectedPathPrefix, what)` fetches HTML and detects an expired session by
**redirect inspection**, NOT status code: if the response's final `encodedPath` no longer
starts with `expectedPathPrefix`, or a `Location` path starts with `/login` or `/account`,
it throws `SessionExpiredException`. Match that pattern for any new action too (check
`response.headers[HttpHeaders.Location]` → compare the path).

**GOTCHA: compare the redirect's URL *path*, not a raw substring of the whole Location
string** — a query value could contain "/login" and false-trip. The existing code parses
the path deliberately.

---

## The full add-an-endpoint chain (mirror `add-ravelry-endpoint`)

1. **Domain model** → `feed/models/Foo.kt`, `@Serializable data class` with `@SerialName`
   for every snake_case field.
2. **Wrapper DTO** → `private @Serializable data class FooResponse(...)` at the bottom of
   `RavelryApiClient.kt`.
3. **`suspend fun`** on `RavelryApiClient` using `authenticatedRequest { …Bearer… }` +
   `lenientJson.decodeFromString`. FormDataContent for free-text POST; `...Page` for lists;
   web-protocol pattern if there's no JSON API.
4. **Repository / ViewModel wiring** → delegate through the feature repo (`FeedRepository`)
   or call from the relevant `feed/*ViewModel.kt`.
5. **UI** → render in a `:compose` screen if user-facing.
6. **Test** → a `MockEngine` test in `RavelryApiClientTest.kt` (mandatory — see below).

---

## MockEngine test — REQUIRED for every new call

Tests live in
`src/common/logic/commonTest/kotlin/com/myhobbyislearning/fibersocial/feed/RavelryApiClientTest.kt`.
Drive the client with a Ktor `MockEngine` and a `FakeFeedTokenStorage()`:

```kotlin
@Test
fun `getFoo parses the envelope`() = runTest {
    val engine = MockEngine { request ->
        val body = when {
            request.url.encodedPath.contains("foo") -> """{"foo":{"id":7,"name":"x"}}"""
            else -> "{}"
        }
        respond(body, HttpStatusCode.OK, headersOf("Content-Type", ContentType.Application.Json.toString()))
    }
    val httpClient = HttpClient(engine) {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
    }
    val client = RavelryApiClient(httpClient, FakeFeedTokenStorage())

    val foo = client.getFoo(7L)

    assertEquals("x", foo.name)
}
```

Assert on request shape too where it matters — the existing suite captures
`request.url.parameters["query"]` / `encodedPath` to prove the *right* query went out
(e.g. that `sort` is `created_`, that free text is a form body not a param, that a 403
surfaces as `ForbiddenException` and a double-401 as `SessionExpiredException`). There is a
`routingApiClient { path -> … }` helper in the test file for the common "route by path"
case — prefer it. **Each test must document a real invariant/edge case — don't pad to
inflate coverage** (see `test-and-coverage`).

Run the common-module tests from `src/platform/android/`:

```bash
./gradlew :common:jvmTest
```

Then the full gate before you push (see `test-and-coverage` for the coverage-gate details
and its known false positives):

```bash
cd /home/betorr/FiberSocial/src && bash common/test.sh && bash platform/android/test.sh
```

**TRAP: `test.sh` scripts are mode 644 (not executable) — invoke with `bash`, don't
`./test.sh` and don't `chmod +x` (it dirties the diff).**

---

## Reference facts (verified)

- Support group constants (feature #57), hardcoded in commonMain:
  `object SupportGroup { PERMALINK="fibersocial-app-support"; GROUP_ID=50702; FORUM_ID=50803 }`;
  feedback topic titles are prefixed `[App Feedback]`.
- Offline API docs: `docs/api/RavelryApiDocumentation.html` (see "Sorting and Pagination").
- Debug logging in common code: `println("FiberSocial: ...")` → logcat `System.out` tag.
