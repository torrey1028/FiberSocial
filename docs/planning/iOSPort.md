# iOS Port Plan

Status: planning (2026-07-03). Audit run against main @ `cb5b352`.

FiberSocial is already a Kotlin Multiplatform project split into a shared `common` module
and an Android app module. This document records (1) a file-level audit of what is and
isn't portable today, (2) the abstractions to introduce *before* writing any Swift so the
port lands on a stable seam, and (3) a phased implementation plan for the iOS app.

---

## 1. Where the code stands today

| Module | Files | Lines | Role |
|---|---|---|---|
| `src/common/src/commonMain` | 32 | ~2,880 | models, scrapers/parsers, API client, ViewModels, notification planner/runner |
| `src/platform/android/app` | 22 | ~2,840 | Compose UI, platform glue (WorkManager/AlarmManager/notifications/WebView), DI wiring |

The architecture has held up well: **every ViewModel, parser, scraper, and the entire
notification decision layer already lives in commonMain**, takes injected
`CoroutineScope`/client/store dependencies, and is exercised by commonTest. A grep of
commonMain for `java.*`, `android.*`, JVM-only date/crypto APIs found **zero hits** — the
only `System.` references are `kotlinx.datetime.Clock.System`, which is multiplatform.
commonMain compiles for Kotlin/Native as-is, pending build-file changes (§3.1).

All commonMain dependencies publish iOS artifacts at their pinned versions (verified
against Maven Central): Ksoup 0.2.4 (`ksoup-iosarm64`/`-iossimulatorarm64`), Ktor 2.3.12
(`ktor-client-darwin`), kotlinx coroutines/serialization/datetime. No dependency swap is
required — only the addition of the Darwin Ktor engine for the iOS source set.

---

## 2. Audit: portable code that lives in the Android module today

Classification of all 22 Android-module files: 9 are pure Compose UI, 11 are genuine
platform glue, and the following pure-logic pieces have slipped in and should move to
commonMain. Each move is mechanical and immediately gains commonTest coverage.

| What | Where today | Issue / destination |
|---|---|---|
| `relativeTime(dateString)` — "5m/3h/2d ago" formatting | `feed/DiscussionTopicCard.kt:23-33` (also used by `TopicDetailScreen.kt:195`) | **The one real JVM leak**: built on `java.time` (`OffsetDateTime`, `DateTimeFormatter`). Rewrite on `kotlinx.datetime` in commonMain; both platforms need identical output. |
| `resolveRavelryHref` + `ALLOWED_LINK_SCHEMES` + `URL_SCHEME` | `feed/PostBody.kt:345-365` | Pure link-target resolution/sanitizing (`javascript:`/`intent:` rejection). Security-relevant — must be one implementation. → `common/.../feed/html/`. |
| `splitOnImages` + `ParagraphSegment` | `feed/PostBody.kt:317-340` | Pure transform over commonMain `Inline` types. → same package as `PostDocument`. |
| Notification ID math (`NEW_EVENT_ID_MASK`, reminder `and mask.inv()`, new-event `or mask`) and notification copy ("Tomorrow", "Starting in 15 minutes", title — when joins) | `notifications/EventNotifier.kt:51-76,125` | iOS must derive *identical* IDs/copy or the replace/stack semantics and wording drift. → a common `ReminderPresentation`/`NotificationIds` helper. |
| JSON encode/decode + `runCatching` fallback shape in stores | `notifications/AndroidNotificationStores.kt:7,20-45` | The codec is pure kotlinx.serialization; only the SharedPreferences line is Android. Absorbed by the `KeyValueStore` abstraction (§3.3). |
| `intervalLabel(hours)` | `settings/SettingsScreen.kt:130` | Trivial; move alongside `NotificationSettings` so both platforms label choices identically. |
| `VOTE_TYPE_EMOJI` map | `feed/TopicDetailScreen.kt:141` | Pure data over the commonMain `VoteType` enum. → next to `VoteType`. |
| `MONTH_ABBREVIATIONS` (DateChip) | `events/EventsScreen.kt:40` | Pure data table; share so date chips match across platforms. |
| PKCE/OAuth helpers (`buildAuthUrl`, `generateCodeVerifier/Challenge/State`, `REDIRECT_URI`) | `login/RavelryAuthManager.kt` | Conceptually platform-agnostic but implemented on `android.net.Uri`, `android.util.Base64`, `java.security.*`. Movable **with an expect/actual shim** for SHA-256 + secure random + base64url (§3.4) — not a drop-in. |

Everything else in the Android module is legitimately platform-specific and stays.

---

## 3. Abstractions to build before the port (the "seam" work)

These are Android-refactoring PRs that make the iOS app thin. All are verifiable with the
existing test suites; none change behavior.

### 3.1 iOS targets in the common module
- Add `iosArm64()` + `iosSimulatorArm64()` to `src/common/build.gradle.kts`; export as an
  XCFramework (`XCFramework` DSL) the Xcode project consumes.
- The module also applies `com.android.library` + an `android {}` block — harmless for
  Native targets but the jacoco/coverage tasks are JVM-only; CI gains an
  `iosSimulatorArm64Test` job (macOS runner) so commonTest runs on Native too.
- Kotlin 2.0.0 supports these targets; the Ksoup 0.2.4 pin (Kotlin 2.2-metadata issue in
  newer versions) is compatible and unchanged.

### 3.2 `expect`/`actual` HttpClient factory
`HttpClient(Android)` with identical JSON + timeout config is built in **three places**
(`FeedAndroidViewModel.kt:24-33`, `EventSyncWorker.kt:42-49`, `AuthAndroidViewModel.kt:19-23`).
The engine import is the single most iOS-hostile line in the codebase.
- commonMain: `expect fun ravelryHttpClient(): HttpClient` carrying the shared
  ContentNegotiation + HttpTimeout config; `actual` = `Android` engine (androidMain),
  `Darwin` engine (iosMain).
- Collapses the triplication on Android as a side effect.

### 3.3 `KeyValueStore` abstraction for the three stores
`TokenStorage`, `NotificationStateStore`, `NotificationSettingsStore` interfaces are
already in commonMain; only their implementations are Android-bound, and all three are
"one prefs file, string keys, JSON blobs, decode-fallback-to-default":
- commonMain: `interface KeyValueStore { get(key): String?; put(key, value); clear() }`
  plus common implementations of the three stores over it (owning all serialization and
  fallback logic — currently duplicated per store).
- androidMain/app: `SharedPreferencesKeyValueStore` (plain) and the encrypted variant for
  tokens. iosMain/app: `NSUserDefaults`-backed store, Keychain-backed for tokens.
- Result: the iOS app writes ~2 tiny classes instead of re-implementing 3 stores.

### 3.4 PKCE crypto shim
- commonMain: move `RavelryAuthManager` logic; `expect fun sha256(bytes)`,
  `expect fun secureRandomBytes(n)`, base64url on top (pure Kotlin).
- actuals: `java.security` on Android/JVM; CommonCrypto/`SecRandomCopyBytes` on iOS.
- URL building drops `android.net.Uri` for Ktor's multiplatform `URLBuilder` (already a
  dependency).

### 3.5 Client-graph factory
The `tokenStorage → RavelryOAuthClient → AuthRepository → RavelryApiClient` graph is
assembled three times (`FeedAndroidViewModel.kt:35-46`, `EventSyncWorker.kt:51-68`,
`AuthAndroidViewModel.kt:26-32`). One commonMain factory
(`RavelryClients(credentials, tokenStorage, httpClient)`) collapses them and is exactly
what the iOS app calls.

---

## 4. UI strategy: Compose Multiplatform vs SwiftUI

The single biggest decision. Both are viable; the codebase is unusually well-positioned
for **Compose Multiplatform (CMP)** and that is the recommendation.

**Why CMP fits this codebase specifically:**
- There is exactly **one** Android view-interop usage in all UI code
  (`WebViewLoginScreen.kt` — `AndroidView` + `android.webkit`). Everything else,
  including the rich forum-post renderer (`PostBody.kt` — custom table `Layout`,
  scrolling code blocks, `AnnotatedString` links), is pure Compose and ports as-is. That
  renderer was the hardest UI in the app; with SwiftUI it would need a full second
  implementation (and its subtle behaviors re-verified), with CMP it is free.
- ~2,800 lines of working, reviewed UI carry over vs. rewriting them in Swift and then
  maintaining two UIs for every future feature (events, notifications settings, post
  management all evolve regularly here).

**CMP costs, honestly:**
- UI moves out of the Android app into a shared UI module (`composeApp`), imports change
  `androidx.compose.*` → the JetBrains artifacts. Mostly mechanical, but it touches every
  UI file, so it should be its own PR with no behavior changes.
- Coil 2 → **Coil 3** (KMP-capable) for `AsyncImage`.
- `BackHandler`/`rememberSaveable`-adjacent APIs and `LocalContext` uses
  (`FeedScreen` settings wiring, debug panel) need small abstractions — inject stores and
  a `runEventSyncNow()` action instead of grabbing `LocalContext`.
- The login screen becomes `expect`/`actual`: Compose `AndroidView`+WebView on Android,
  `UIKitView`+`WKWebView` on iOS.
- iOS look-and-feel is Material, not Cupertino. For this app (a hobby client, one user
  today) that trade is acceptable; a SwiftUI shell can host CMP screens later if native
  chrome ever matters.

**The SwiftUI alternative** keeps each platform native at the cost of a permanent second
UI codebase (~3k lines to write, then double-maintenance forever). Choose it only if
native-feel becomes a hard requirement.

---

## 5. Platform capability mapping

| Capability | Android today | iOS equivalent | Notes |
|---|---|---|---|
| OAuth login + session cookie | WebView; captures `code` + `_ravelry_session` cookie at redirect (`WebViewLoginScreen.kt:38-47`) | `WKWebView` + `WKHTTPCookieStore` | Must read cookies for `www.ravelry.com` **and** `ravelry.com` (same fallback as Android). `ASWebAuthenticationSession` is *not* suitable — it hides its cookie jar; the scraping design needs that cookie. Downstream (`AuthViewModel.onAuthCodeReceived`) is already common. |
| Token storage | EncryptedSharedPreferences | Keychain | Behind `KeyValueStore` (§3.3). |
| Reminders (T-24h/T-15m) | `AlarmManager` exact alarms + `ReminderReceiver` + boot/`MY_PACKAGE_REPLACED` re-registration (#90) | `UNCalendarNotificationTrigger` local notifications | **iOS is simpler here**: local notifications are scheduled with the OS and fire without the app running — no receiver, no boot rescheduling, no exact-alarm permission. The planner's `SyncPlan` maps 1:1 (schedule → `add(request)`, cancel → `removePendingNotificationRequests(ids)`). Constraint: 64 pending-notification cap per app (2/event → fine; enforce nearest-32-events if ever needed). |
| New-event polling | WorkManager periodic (user-set 1–24h, network-constrained) | `BGAppRefreshTask` | **The big behavioral gap.** iOS background refresh is opportunistic: the OS decides cadence from usage patterns; `earliestBeginDate` is a floor, not a schedule, and force-quit apps don't refresh. Mitigate: qualitative cadence labels (phase 0f) so the setting promises nothing iOS can't keep, plus a sync on every foreground activation (cheap, already idempotent). |
| Notification channels/permission | Two channels; `POST_NOTIFICATIONS` runtime prompt | `UNUserNotificationCenter.requestAuthorization`; category per kind | ID math + copy shared via §2 so replace/stack semantics match. |
| Deep link (notification tap → event) | Intent extra → `singleTop`/`onNewIntent` → `FeedScreen` | `UNNotificationResponse` handler → same shared navigation state | Navigation is manual Compose state — works unchanged under CMP. |
| Debug panel "Run sync now" | WorkManager one-shot | direct `EventSyncRunner` invocation in a Task | Runner is common; trivial. |
| Secrets (`RAVELRY_CLIENT_ID/SECRET`) | `local.properties` → `BuildConfig` | `Config.xcconfig` (gitignored) → Info.plist read, or generated Kotlin | Keep out of the repo either way; document the clean-build inlining gotcha's iOS analog. |

**No iOS blocker exists in the scraping design** — Ktor Darwin sends the same
session-cookie header; Ksoup parses the same HTML; the CSRF-token flow is HTTP-only.

---

## 6. Tooling constraint: macOS is required

Kotlin/Native iOS targets, the Xcode project, and the simulator **cannot build on
Windows/WSL** (current dev environment). Options, cheapest first:
1. **CI-first**: GitHub Actions supplies hosted macOS runners — `macos-14`/`macos-15`
   (Apple Silicon) with Xcode preinstalled — and this repo is public, so they are
   **free** (private repos pay a 10× minute multiplier; not our case). CI builds the
   XCFramework + app and runs the Native test suite; local work stays on Windows for
   common/Android. Sufficient for phases 0–2 (§7), which are pure Gradle.
2. A Mac (or MacinCloud-style remote) becomes necessary at phase 3+ for the simulator,
   Xcode signing, and on-device verification. **Decision needed before phase 3.**

---

## 7. Phased implementation plan

Each phase = one or more normal PRs with tests; phases 0–2 run entirely on the current
Windows setup and improve the Android app on their own merit.

- **Phase 0 — seam refactors on Android (no behavior change)**
  0a. Move the §2 pure-logic items to commonMain (incl. `relativeTime` rewrite on
      kotlinx.datetime, with tests pinning today's output).
  0b. `KeyValueStore` + common store implementations (§3.3).
  0c. `expect/actual` HttpClient factory + client-graph factory (§3.2, §3.5).
  0d. PKCE move with crypto expect/actuals (§3.4; JVM actuals only for now).
  0e. Cleanup: rename the stuttery `src/common/src/commonMain` directory to
      `src/common/src/main` (explicit `sourceSets["commonMain"].kotlin.srcDirs`
      config — the *source set* keeps its KMP name, only the folder changes; same
      for `commonTest` → `test`).
  0f. Replace the precise poll-cadence choices (1/3/6/12/24h) with qualitative
      labels — "Hourly", "A few times a day", "Once a day" — mapped to platform
      schedules underneath. Motivated by iOS's opportunistic refresh (§5, §8.1):
      qualitative wording stays truthful on both platforms. Lands on Android first.
  0g. Toolchain bump: Kotlin 2.0 → 2.1+ (pulled forward per review — Coil 3 and
      current CMP want it, and it unpins Ksoup from 0.2.4). Do this before any
      CMP work so phase 2 starts on the final toolchain.
- **Phase 1 — common module compiles for iOS**
  Add iOS targets + XCFramework export; iOS crypto/engine actuals; CI macOS job running
  commonTest on `iosSimulatorArm64`. Exit criterion: green Native test run.
- **Phase 2 — UI migration to Compose Multiplatform (still Android-only)**
  Extract Compose UI into a shared `composeApp` module on JetBrains Compose + Coil 3;
  `expect`/`actual` for the login WebView and the two `LocalContext` seams. Exit
  criterion: Android app pixel-identical, all tests green.
- **Phase 3 — iOS app shell** *(needs macOS)*
  Xcode project + SwiftUI host embedding the CMP `ComposeUIViewController`; login via
  `WKWebView` actual (code + cookie capture); Keychain/NSUserDefaults stores; secrets
  via xcconfig. Exit criterion: log in, browse feed/topics/events on simulator.
- **Phase 4 — iOS notifications**
  `UNUserNotificationCenter` reminders driven by the shared `SyncPlan`;
  `BGAppRefreshTask` + on-foreground sync for new events; notification-tap deep link.
  Exit criterion: the same live test matrix the Android build passed (new-event
  notification with app backgrounded, RSVP → immediate reminder scheduling, tap →
  event detail).
- **Phase 5 — polish & parity audit**
  Settings copy for iOS cadence semantics, app icon/launch screen, TestFlight,
  re-run the full events + notifications verification checklist on a device.

## 8. Risks & open questions

1. **BGAppRefreshTask cadence** is the one user-visible regression risk: "new event"
   notifications on iOS may lag hours behind the configured cadence. Addressed by the
   qualitative setting labels (phase 0f) plus foreground-activation sync; residual risk
   is only for users who force-quit the app (iOS then never refreshes it).
2. **CMP maturity surface**: `ModalNavigationDrawer`/`ModalBottomSheet`/`AlertDialog` are
   available in CMP material3, but phase 2 should smoke-test the drawer + dialogs early;
   any gap has a plain-composable fallback.
3. **Kotlin toolchain bump** (phase 0g) is pulled forward deliberately; it is the change
   most likely to surface incidental breakage (compiler warnings→errors, Ksoup unpin),
   so it gets its own PR with the full suite as the gate.
4. **Test parity on macOS/iOS**: commonTest — the suite guarding every parser,
   ViewModel, and the notification planner — runs on `iosSimulatorArm64` in the free
   macOS CI job from phase 1 onward, so shared-logic regressions surface on the Native
   target exactly as they do on JVM today. Compose UI tests (drawer/back-handling/
   settings) run against the CMP module after phase 2; Robolectric-based platform-glue
   tests (stores, alarms, receivers) stay Android-only by nature, and their iOS
   counterparts (Keychain store, notification scheduling) get XCTest coverage in
   phases 3–4. The jacoco coverage gate reads JVM only — confirm it isn't distorted
   when UI moves modules.
5. **Mac access** (§6) gates phases 3–5 — decide hardware before starting phase 3.
