---
name: add-ravelry-endpoint
description: Add or modify a Ravelry API call end-to-end following FiberSocial's central-client pattern — covers both JSON-API endpoints and website web-protocol (scraping) actions. Use when wiring up a new Ravelry read/write (topics, posts, votes, projects, groups, events) or when a feature needs data the app doesn't fetch yet.
---

# Add a Ravelry endpoint

There is ONE central client. Every Ravelry call lives on it — do not spin up a new Ktor client or scatter `httpClient.get` around feature code.

- Client: `src/common/src/commonMain/kotlin/com/autom8ed/fibersocial/feed/RavelryApiClient.kt` (~1100 lines).
- Domain models: `src/common/src/commonMain/kotlin/com/autom8ed/fibersocial/feed/models/*.kt`.
- Tests: `src/common/src/commonTest/kotlin/com/autom8ed/fibersocial/feed/RavelryApiClientTest.kt` (+ shared fakes in `Fakes.kt`).
- All gradle/test commands run from `src/platform/android/`. See the fibersocial-build-and-test skill.

## Architecture (read before touching anything)

Two base URLs, both `private const val` at the top of the client:
- `BASE_URL = "https://api.ravelry.com"` — the JSON API.
- `WWW_URL = "https://www.ravelry.com"` — the website, for capabilities the JSON API doesn't expose (join/leave group, delete post, events, RSVP, group memberships).

**Two auth mechanisms, both attached BY HAND — there is no Ktor Auth plugin:**
1. OAuth Bearer, on every JSON call: `header(HttpHeaders.Authorization, "Bearer ${accessToken()}")`. `accessToken()` reads `tokenStorage.load()?.accessToken`.
2. Session cookie, for `WWW_URL` scraping/actions: `header(HttpHeaders.Cookie, sessionCookie())`. The cookie was captured at WebView login.

You must add the right header yourself in each method. Forgetting the Bearer header = a 401 that "mysteriously" only fails your endpoint.

### `authenticatedRequest { }` wraps every JSON call

`private suspend fun authenticatedRequest(block: suspend () -> HttpResponse): String` does the token lifecycle. Wrap your `httpClient.get/post { }` in it and it returns the response body text:

- Proactive refresh: if the stored token expires in `< 60_000L` ms, it best-effort refreshes first.
- **`403` → `ForbiddenException`. TRAP: a 403 is NEVER session expiry** — it's a valid token missing a scope or permission (issue #82). Do not "fix" it with a refresh or a re-login prompt. The message from `forbiddenMessage()` deliberately OMITS the literal string "403", because `FeedErrorState` pattern-matches "401"/"403" in error text to detect expired sessions — leaking "403" into the message would misroute a permission error into the session-expired UI.
- `401` → `tryRefresh()` then retry the block ONCE. Second 401 → `SessionExpiredException`. (A 403 on the retry still → `ForbiddenException`.)

Web-protocol (`WWW_URL`) methods do NOT go through `authenticatedRequest` — they use `scrapeHtml(...)` or handle status by hand (see below), because those flows key off redirects and the session cookie, not the Bearer token.

## The method shape (normal JSON endpoint)

Decode into a `private @Serializable` wrapper DTO declared at the BOTTOM of the client (inside the class), using the client's own `lenientJson`:

```kotlin
suspend fun getTopicDetail(topicId: Long): Topic {
    val raw = authenticatedRequest {
        httpClient.get("$BASE_URL/topics/$topicId.json") {
            header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
        }
    }
    return lenientJson.decodeFromString<TopicDetailResponse>(raw).topic
}
// ...at the bottom of the class:
@Serializable private data class TopicDetailResponse(val topic: Topic)
```

- `lenientJson = Json { ignoreUnknownKeys = true; coerceInputValues = true }` is the client's private instance. `coerceInputValues` maps an explicit JSON `null` back to a field default (kotlinx only applies a default when the key is ABSENT). Use it, don't roll your own `Json`.
- Query params go through `url.parameters.append("key", "value")` (or `url.parameters.apply { ... }`) inside the request block.

### TRAP: free text MUST be a form body, not a query param

A message / reply / topic body / comment is free text — it can be multi-KB and it lands in server access logs. Send it as a `FormDataContent` **body**, never a query parameter (URL request-line length → 414, plus logging leakage):

```kotlin
httpClient.post("$BASE_URL/topics/$topicId/reply.json") {
    header(HttpHeaders.Authorization, "Bearer ${accessToken()}")
    setBody(FormDataContent(Parameters.build { append("body", body) }))
}
```

Optional form fields: only append when present, e.g. `summary?.takeIf { it.isNotBlank() }?.let { append("summary", it) }` (see `createTopic`).

### Pagination

Return a small `data class ...Page(items, page, hasMore)` (top-level, at the bottom of the file — see `TopicsPage`, `PostsPage`). Compute `hasMore` from the response `Paginator`:

```kotlin
val paginator = response.paginator            // private data class Paginator(page, page_count, results)
return TopicsPage(
    topics = response.topics,
    page = paginator?.page ?: page,
    hasMore = paginator != null && paginator.page < paginator.pageCount,
)
```

Page-size defaults are named consts: `DEFAULT_FEED_PAGE_SIZE = 25`, `DEFAULT_POSTS_PAGE_SIZE = 25`. Reuse them; don't hardcode 25 at a new call site.

### THE SORT TRAP (gets people every time)

Ravelry sort params are **ascending by default. A trailing `_` reverses to descending.**

- `sort=created` = OLDEST first → silently buries the recent items the user came to see.
- `sort=created_` = NEWEST first ← what "most recent" UX almost always wants.
- Same for `name` (A→Z) vs `name_` (Z→A).

Confirmed live in `getProjects()`: `url.parameters.append("sort", "created_")`. When you add any sorted list, decide the direction explicitly and append the `_` if you want descending. (See also the reference_ravelry_sort_convention memory note.)

## The 6-step recipe (normal authenticated JSON endpoint)

1. **Model** — a public `@Serializable data class` in `feed/models/` (or the relevant feature package). Use `@SerialName("snake_case")` for every wire field that isn't already camelCase-identical. Give defaults so absent keys don't throw.
2. **Wrapper DTO** — a `private @Serializable data class FooResponse(...)` at the bottom of `RavelryApiClient`, matching the JSON envelope (Ravelry wraps payloads, e.g. `{"topic": {...}}`, `{"topics": [...], "paginator": {...}}`).
3. **`suspend fun`** on `RavelryApiClient` — `authenticatedRequest { httpClient.get/post(...) { Bearer header; params or FormDataContent } }` then `lenientJson.decodeFromString<FooResponse>(raw).foo`. Free-text writes → `FormDataContent`. Lists → a `...Page` struct.
4. **Repository / ViewModel** — delegate from the feature repo (e.g. `FeedRepository`) or call from a `feed/*ViewModel.kt`. No DI framework: clients are assembled by hand in `net/RavelryClients.kt` and the platform models.
5. **Compose screen** — render in a `:compose` screen (`src/compose/.../feed/`). No navigation library; screens toggle via `mutableStateOf` flags inside `FeedScreen`.
6. **Test** — a `MockEngine` test in `RavelryApiClientTest.kt`. See the testing section.

## Web-protocol pattern (capabilities with NO JSON API)

Use this ONLY when Ravelry exposes no API endpoint: join/leave group, delete a forum post, events, RSVP, listing a user's group memberships. Two sub-shapes:

### Reads → `scrapeHtml` + a Ksoup parser

```kotlin
val html = scrapeHtml("$WWW_URL/groups/$permalink", "/groups/", "Group page for $permalink")
val events = GroupEventsParser.parse(html)     // Ksoup parser in feed/.../events/
```

`private suspend fun scrapeHtml(url, expectedPathPrefix, what)` sends the session cookie + `Accept: text/html` and fails LOUDLY so an auth failure can't masquerade as "page just lacks the markup":
- `403` → `ForbiddenException` (permission, not expiry — same reasoning as above).
- `401` → `SessionExpiredException`.
- **Session-expiry detected by redirect inspection:** Ktor follows the redirect; if the landed `encodedPath` no longer starts with `expectedPathPrefix`, that's an expired-cookie bounce to the login page → `SessionExpiredException`. Redirects that STAY inside the prefix (permalink canonicalization) are fine. Pick `expectedPathPrefix` tight enough to catch a login bounce but loose enough to allow canonicalization (compare `getGroupEvents` using `/groups/` vs `getSavedEvents` using the exact `/events/saved`).

### Writes → `submitForm` with a Rails method override + scraped CSRF token

Ravelry's website buttons fire Prototype.js `Ajax.Request(url, {method:'put'|'delete'})`, which tunnels as a POST carrying `_method=put|delete`:

```kotlin
val token = fetchAuthenticityToken()           // cached; scrapes <meta name="authenticity-token">
httpClient.submitForm(
    url = "$WWW_URL/groups/$permalink/$action", // e.g. join / leave
    formParameters = parameters {
        append("_method", "put")               // or "delete"
        append("authenticity_token", token)
    },
) { header(HttpHeaders.Cookie, cookie) }
```

- `fetchAuthenticityToken()` scrapes `<meta name="authenticity-token">` (selector also accepts `meta#authenticity-token` / `meta[name=csrf-token]`) via Ksoup and **caches it in `cachedAuthenticityToken`** (session-stable, so repeated writes don't re-fetch the homepage).
- **Ktor does NOT follow redirects for POST**, so the 3xx surfaces directly. Both success and expired-session look like a redirect — the `Location` path is the only discriminator. **TRAP:** match on the redirect's URL `encodedPath` (`Url(location).encodedPath` starting with `/login` or `/account`), NOT a raw substring of the whole Location string — else a real permalink containing "login"/"account" (a group literally named `login-fanatics`) false-positives as expiry. On a rejection, null out `cachedAuthenticityToken` so a retry re-scrapes a fresh one.

See `joinGroup`/`leaveGroup` (`membershipAction`), `deletePost`, and `setEventAttendance` for the three worked examples.

### GOTCHA: the session cookie does NOT auto-refresh (issue #61, open)

`authenticatedRequest` transparently refreshes the OAuth Bearer token, but the **session cookie never refreshes** — `refreshAccessToken()` doesn't carry it and `AuthRepository.refreshToken()` just copies the old cookie forward. So any cookie-based (scraping/web-protocol) feature you add will hard-fail with `SessionExpiredException` once the cookie expires, forcing a full re-login — there is no silent recovery path like the Bearer token has. Factor that into any new scraping capability's error UX; don't assume a refresh will save it.

## Testing (`RavelryApiClientTest.kt` with `MockEngine`)

The client's only dependency is a Ktor `HttpClient`, so tests inject a `MockEngine`. The shared helpers `routingApiClient`, `routingApiClientCapturing`, and `FakeFeedTokenStorage` live in `Fakes.kt`:

- `routingApiClient { path -> jsonString }` — routes by `request.url.encodedPath`, always 200 JSON. The default token is `AuthToken("test-token", "test-refresh", Long.MAX_VALUE, "sess=test")` via `FakeFeedTokenStorage`.
- `routingApiClientCapturing(onRequest = { url -> ... }) { path -> ... }` — also captures the request URL, to assert the path/params you built (e.g. `assertEquals("/people/yarnie.json", captured?.encodedPath)`).
- `htmlApiClient(engine)` — for hand-built `MockEngine`s (HTML scrapes, status codes, capturing bodies/cookies). This one is NOT in `Fakes.kt`: it's a `private fun` local to `RavelryApiClientTest.kt` (near the bottom of the file).

Patterns to copy:
- **Happy path:** `routingApiClient { CURRENT_USER_JSON }` → call → assert the decoded model.
- **Missing envelope fails loudly:** `routingApiClient { "{}" }` → `assertFailsWith<MissingFieldException>`.
- **Auth lifecycle** (hand-rolled `MockEngine` counting calls): 401-then-refresh-then-retry asserts `refreshCalled` + `callCount == 2`; 403 asserts `ForbiddenException` with NO refresh and `callCount == 1`; second-401 asserts `SessionExpiredException`.
- **Form body assertion:** read the sent body with `(request.body as FormDataContent).formData.formUrlEncode()` and assert the url-encoded string (see `setEventAttendance` test — e.g. `authenticity_token=tok%2Ben%3D`).
- **Cookie assertion:** `request.headers[HttpHeaders.Cookie]` should equal `"sess=test"`.

Every gate-satisfying test must document a real invariant (a param you build, a default, an error path) — don't pad to hit coverage. Run tests per the fibersocial-build-and-test skill.

## Do / Don't

- DO add the method to `RavelryApiClient`; DON'T create a second HTTP client or call Ravelry from feature code.
- DO wrap JSON calls in `authenticatedRequest` and add the Bearer header yourself.
- DO send free text as `FormDataContent`; DON'T put it in the query string.
- DO append `_` to a sort param when you want newest/descending first.
- DON'T treat a 403 as expiry, and DON'T let "403"/"401" leak into a user-facing error string.
- DON'T reach for the web-protocol pattern unless the JSON API genuinely lacks the endpoint.
