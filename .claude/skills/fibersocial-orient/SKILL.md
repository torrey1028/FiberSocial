---
name: fibersocial-orient
description: Entry-point map of the FiberSocial Kotlin Multiplatform (Android+iOS) Ravelry client — its layout, module wiring, and architecture — plus a router to the right sibling skill. Load this FIRST when starting any task here and you are unsure where code lives or which skill to use.
---

# FiberSocial — orientation & router

Kotlin Multiplatform (Android + iOS) Ravelry client. Package root everywhere:
`com.autom8ed.fibersocial`. App id: `com.autom8ed.fibersocial`. GitHub repo:
`torrey1028/FiberSocial`. No npm / Node / TypeScript. No version catalog.

## The one structural fact that trips everyone

**The Gradle root is NOT the repo root.** Run every `./gradlew`, `./deploy.sh`,
and test script from `src/platform/android/` (repo root has no `gradlew`).

`src/platform/android/settings.gradle.kts` declares 3 modules and repaths two of
them out of the android dir:

| Gradle module | Actual directory | What it is |
|---|---|---|
| `:app` | `src/platform/android/app/` | Android application shell (`MainActivity`) |
| `:common` | `src/common/logic/` (repathed) | Shared non-UI Kotlin (net, auth, feed API, models, html parsing) |
| `:composeApp` | `src/common/compose/` (repathed) | Shared Compose Multiplatform UI |

KMP targets & source sets (both shared modules):
- `:common`: `jvm()`, `androidTarget()`, `iosArm64()`, `iosSimulatorArm64()`;
  source sets `commonMain / androidMain / iosMain / jvmMain` (per-target test
  source sets exist in the KMP hierarchy, but only `commonTest` holds test code).
  Exports XCFramework `FiberSocialCommon`.
- `:composeApp`: `commonMain / androidMain / iosMain`; tests in `commonTest`,
  `androidUnitTest` (Robolectric Compose UI), `iosTest`.

**TRAP:** most `git worktree add` traps, secret handling, and background-build
rules live in the sibling skills below and in `CLAUDE.md` — read them, don't guess.

## Deliberately no-framework architecture

This project hand-rolls things a bigger app would pull a library for. Do not go
looking for the library — it isn't there.

- **No DI framework.** Manual factory functions in
  `src/common/.../net/RavelryClients.kt` (`ravelryAuthRepository(...)`,
  `ravelryApiClient(...)`), assembled per platform (Android `FeedAndroidViewModel`,
  iOS `app/IosModels.kt`).
- **No navigation library.** Navigation is hand-rolled `remember { mutableStateOf(...) }`
  flags inside `FeedScreen` (e.g. `selectedTopic`, `showSettings`, `composingTopic`,
  `eventsGroup`) switched with `when`. No NavHost, no routes.
- **No single `App()` composable.** The post-auth root is DUPLICATED in Android
  `MainActivity` (`setContent { ... }`) and iOS (the `IosApp` composable in
  `MainViewController.kt`). The
  shared post-login root both converge on is **`FeedScreen`**
  (`src/common/compose/commonMain/.../feed/FeedScreen.kt`). Change post-login UI
  wiring in one platform → check the other.

## Two headline API truths

1. **Half real JSON API, half HTML scraping.** The single central client
   `src/common/.../feed/RavelryApiClient.kt` talks to TWO hosts:
   `BASE_URL = https://api.ravelry.com` (JSON API, most reads) and
   `WWW_URL = https://www.ravelry.com` (website HTML scraped with Ksoup, plus
   Rails "web-protocol" form POSTs with `_method=put|delete` + scraped
   `authenticity_token` for actions the JSON API doesn't expose — joins, leaves,
   deletes, events).
2. **Every request carries BOTH a Bearer token AND a session cookie.** There is
   no Ktor Auth plugin — each call hand-attaches `Authorization: Bearer <token>`
   (OAuth, from `TokenStorage`) *and* a `Cookie:` header (session cookie captured
   at WebView login). The two are refreshed independently; **the cookie does NOT
   auto-refresh** (issue #61) — cookie expiry forces a full re-login.

## Key files (quick reference)

| Path (under repo root) | What |
|---|---|
| `src/platform/android/settings.gradle.kts` | module→dir repathing; start here to understand layout |
| `src/platform/android/{build.gradle.kts, app/build.gradle.kts}` | Android build + versionCode/versionName derivation |
| `src/common/logic/build.gradle.kts` | KMP targets, coverage report tasks |
| `src/common/compose/build.gradle.kts` | Compose MP source sets |
| `src/common/.../net/RavelryClients.kt` | manual DI factories |
| `src/common/.../feed/RavelryApiClient.kt` | THE central API client (~1100 lines; JSON + scraping) |
| `src/common/.../feed/models/` | `@Serializable` domain models (`Topic`, `Post`, `Group`, ...) |
| `src/common/.../feed/html/` | `PostDocument` + `HtmlPostParser` / `MarkdownPostParser` |
| `src/common/.../auth/` | OAuth PKCE, `AuthRepository`, token storage |
| `src/common/compose/.../feed/FeedScreen.kt` | shared post-login root + hand-rolled nav |
| `src/common/compose/.../feed/PostBody.kt` | rich renderer for card + full post |
| `src/platform/android/app/.../MainActivity.kt` | Android entry / post-auth root |
| `src/platform/ios/composeApp/iosMain/.../app/MainViewController.kt` | iOS entry / post-auth root |
| `src/platform/android/{deploy.sh, test.sh}` | device deploy / test entry points |
| `scripts/compare_coverage.py`, `scripts/release.sh` | coverage gate / release tagging |

## Router — pick your next skill

- **Build / install / run on device or simulator** (gradle from
  `src/platform/android`, `local.properties`, BuildConfig-inlining trap,
  background builds) → the **build-and-run** skill.
- **Run tests / satisfy the coverage gate** (from `src/`: `bash common/test.sh &&
  bash platform/android/test.sh`, mode-644 trap, Jacoco baseline, false
  positives) → the **test-and-coverage** skill.
- **Add a Ravelry endpoint or scraping action** (JSON recipe vs web-protocol
  pattern, models, `authenticatedRequest`, `MockEngine` tests) → the
  **add-ravelry-endpoint** skill.
- **Work on feed/post rendering** (`PostDocument`, the two-walker sync
  invariant, `PostBody`, which HTML/Markdown source wins) → the
  **feed-rendering** skill.
- **Cut a versioned release** (`scripts/release.sh`, tag→CI, versionCode rules)
  → the **cut-release** skill.
- **Any git action — branch, worktree, PR, update-a-PR** (never push to `main`,
  never merge, worktree-first) → the **fibersocial-git-workflow** skill.
- **Secrets, signing, deploy gotchas, logging conventions** not covered above →
  the project **`CLAUDE.md`** (source of truth for policy).

Keep this skill as the hub. Depth lives in the sibling skills — jump there rather
than expecting full detail here.
