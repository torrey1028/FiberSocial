# Group Feed — Implementation Plan

## Context

The Group Feed is the social heart of FiberSocial. Users belong to one or more Ravelry groups
(the local crafting community being the primary one). The feed surfaces recent forum topics and
posts from those groups so the community can see what's happening without opening a browser.

Goals:
- Show recent activity from the user's Ravelry groups in a scrollable feed
- Each item links to a topic, shows the author, post count, and last-activity time
- Pull-to-refresh to get new items
- Tap a topic to read the thread (Phase 2)
- Respect Ravelry's API rate limits — cache aggressively, don't poll

---

## Ravelry API

Base URL: `https://api.ravelry.com`  
Auth: `Authorization: Bearer {access_token}` on every request

| Endpoint | What it returns |
|---|---|
| `GET /current_user.json` | Logged-in user's username, avatar URL |
| `GET /people/{username}/groups.json` | Groups the user has joined |
| `GET /groups/{permalink}/topics.json` | Paginated forum topics for a group |
| `GET /topics/{id}/posts.json` | Posts within a single topic |

> **Note:** Ravelry's API documentation is sparse. Exact response field names will need
> to be confirmed by inspecting live responses during Phase 1 implementation.
> Fields marked with `?` are assumed but unverified.

### Key response shapes (assumed)

**Group**
```json
{
  "id": 12345,
  "name": "My Knitting Circle",
  "permalink": "my-knitting-circle",
  "members_count": 42,
  "avatar_image_path": "..."
}
```

**Topic (feed item)**
```json
{
  "id": 98765,
  "title": "What are you working on this week?",
  "last_post_at": "2026-06-27T17:00:00Z",
  "posts_count": 12,
  "first_poster": { "username": "...", "avatar_image_path": "..." },
  "last_poster":  { "username": "...", "avatar_image_path": "..." }
}
```

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                  Platform UI Layer                    │
│   Android: Compose FeedScreen + TopicDetailScreen    │
│   iOS (future): SwiftUI FeedView                     │
└───────────────────┬──────────────────────────────────┘
                    │ observes
┌───────────────────▼──────────────────────────────────┐
│            src/common — Shared KMP Module             │
│                                                      │
│  FeedViewModel                                       │
│    └─ FeedRepository                                 │
│         └─ RavelryApiClient (Ktor)                   │
│              authenticated GET calls                 │
│                                                      │
│  Data models: Group, Topic, Post, RavelryUser        │
└──────────────────────────────────────────────────────┘
```

`RavelryApiClient` is a new Ktor client separate from `RavelryOAuthClient` — it handles
authenticated data calls, not auth. It receives the access token from `TokenStorage` on
each request (so it always uses the current token without needing to know about login flow).

---

## Folder Structure

```
src/
  common/
    src/
      commonMain/kotlin/com/autom8ed/
        feed/
          FeedRepository.kt        ← fetch groups + topics
          FeedViewModel.kt         ← FeedState: Loading / Loaded / Error
          RavelryApiClient.kt      ← authenticated Ktor GET calls
          models/
            Group.kt
            Topic.kt
            Post.kt
            RavelryUser.kt
  platform/android/app/src/main/kotlin/com/autom8ed/
    feed/
      FeedScreen.kt                ← scrollable topic list
      FeedAndroidViewModel.kt      ← Android ViewModel wrapper
      TopicDetailScreen.kt         ← thread reader (Phase 2)
```

---

## Feed State Machine

```
App opens (authenticated)
        │
        ▼
  FeedState.Loading
        │
        ├─ success ──▶ FeedState.Loaded(groups, topics)
        │                    │
        │              pull-to-refresh ──▶ FeedState.Refreshing(stale data)
        │                                        │
        │                                   success ──▶ FeedState.Loaded(fresh)
        │                                   failure ──▶ FeedState.Loaded(stale) + snackbar
        │
        └─ failure ──▶ FeedState.Error(message)
                             │
                         retry ──▶ FeedState.Loading
```

---

## Implementation Phases

### Phase 1 — Data models + API client (common)
- Define `Group`, `Topic`, `Post`, `RavelryUser` data classes (`@Serializable`)
- Create `RavelryApiClient` with Bearer token injection from `TokenStorage`
- Create `FeedRepository` (fetches current user → their groups → recent topics per group)
- Create `FeedViewModel` with `FeedState` sealed class
- Verify actual Ravelry API response shapes against assumed fields above

### Phase 2 — Feed UI (Android)
- `FeedAndroidViewModel` wiring `FeedViewModel` into `viewModelScope`
- `FeedScreen`: lazy column of topic cards, pull-to-refresh, empty state, error state
- `TopicCard`: author avatar, topic title, reply count, last-active time
- Wire into `MainActivity` navigation: `Authenticated` state → `FeedScreen`
- Respect the visual mockup once it's ready

### Phase 3 — Topic detail (Android)
- `TopicDetailScreen`: paginated posts in a thread, author avatars, post body text
- Back navigation to feed
- Deep link support (`ravelry://topic/{id}`) for future share-to-app flows

### Phase 4 — Group picker (Android)
- Allow user to select which of their groups to show in the feed (not all at once)
- Persist selection in `SharedPreferences`
- Settings screen or sheet accessible from the feed toolbar

### Phase 5 — iOS (future)
- SwiftUI `FeedView` consuming `FeedViewModel` from shared KMP module
- Same `FeedRepository` / `RavelryApiClient` — no iOS-specific business logic needed

---

## Open Questions Before Starting

1. **Which groups?** Does the user want to show all their Ravelry groups, or scope it to a
   specific one? If the community has a known group permalink, we could hard-code it as the
   default and add group picker later (Phase 4).

2. **API rate limits** — Ravelry's limits aren't publicly documented. We should cache the
   topic list and only re-fetch on explicit pull-to-refresh (not on every app foreground).

3. **Images** — Topic avatars and user avatars are hosted on Ravelry's CDN. We'll need an
   image loading library (Coil on Android, planned for Phase 2).

4. **Pagination** — `topics.json` is paginated. Phase 2 should implement infinite scroll
   or at minimum a "load more" button.

5. **Visual mockup** — Feed card layout (avatar placement, typography, density) to be
   confirmed against the mockup before Phase 2 begins.
