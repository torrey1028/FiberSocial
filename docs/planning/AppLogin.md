# App Login — Implementation Plan

## Context

Users authenticate via Ravelry's API using OAuth 2.0. The app must:
- Never store the user's password
- Keep users logged in across sessions (via a stored refresh token)
- Support biometric unlock so users don't retype credentials on reopen
- Share all auth business logic between Android and future iOS

---

## Ravelry Auth: What the API Supports

Ravelry uses **OAuth 2.0 Authorization Code** flow. Relevant endpoints:

| | |
|---|---|
| Authorization | `https://www.ravelry.com/oauth2/auth` |
| Token exchange | `https://www.ravelry.com/oauth2/token` |
| App registration | `https://www.ravelry.com/pro/developer` |

For a mobile app, the **PKCE extension** (Proof Key for Code Exchange) is the right variant — it avoids storing a client secret in the app binary (which would be extractable). PKCE is the OAuth 2.0 standard for native/mobile apps.

**How persistent login works (answering the Features.md open):** The token endpoint returns both an `access_token` (short-lived, ~1hr) and a `refresh_token` (long-lived). Storing the refresh token in the OS secure enclave (Android Keystore / iOS Keychain) lets the app silently get new access tokens indefinitely without the user re-entering their password. Biometric unlock gates access to the stored refresh token.

---

## Framework Recommendations by Layer

| Layer | Recommendation | Rationale |
|---|---|---|
| **Shared business logic** | [Kotlin Multiplatform (KMP)](https://kotlinlang.org/docs/multiplatform.html) | Already using Kotlin on Android; shared code compiles to both Android JVM and iOS native. UI stays platform-native. |
| **HTTP / API calls** | [Ktor](https://ktor.io/docs/client-overview.html) | KMP-native HTTP client — same code on both platforms |
| **OAuth 2.0 / PKCE flow** | [AppAuth-Android](https://github.com/openid/AppAuth-Android) / [AppAuth-iOS](https://github.com/openid/AppAuth-iOS) | Industry-standard OpenID library for OAuth PKCE on mobile; handles redirect URI interception and the browser handoff correctly |
| **Shared ViewModel / state** | [MOKO MVVM](https://github.com/icerockdev/moko-mvvm) | KMP-compatible ViewModel with observable state; mirrors Jetpack ViewModel API so Android feels native |
| **Android UI** | Jetpack Compose (existing) | Already in place |
| **iOS UI** (future) | SwiftUI | Works naturally with MOKO MVVM observables via Swift bindings |
| **Android token storage** | `EncryptedSharedPreferences` (Jetpack Security) | AES-256 encrypted, backed by Android Keystore hardware |
| **iOS token storage** (future) | Keychain Services | Platform standard; hardware-backed on modern devices |
| **Android biometric** | `BiometricPrompt` (androidx.biometric) | Standard AndroidX; covers fingerprint + face + device PIN fallback |
| **iOS biometric** (future) | `LocalAuthentication` framework | Face ID / Touch ID; policy-based, no custom UI needed |

---

## Architecture

```
┌──────────────────────────────────────────────────────┐
│                  Platform UI Layer                    │
│   Android: Compose LoginScreen / BiometricPrompt     │
│   iOS (future): SwiftUI LoginView / LocalAuth        │
└───────────────────┬──────────────────────────────────┘
                    │ observes
┌───────────────────▼──────────────────────────────────┐
│            src/common — Shared KMP Module             │
│                                                      │
│  AuthViewModel (MOKO MVVM)                           │
│    └─ AuthRepository                                 │
│         ├─ RavelryOAuthClient (Ktor)                 │
│         │    OAuth2 PKCE flow, token refresh         │
│         └─ TokenStorage (interface)                  │
│              ├─ Android impl: EncryptedSharedPrefs   │
│              └─ iOS impl: Keychain (future)          │
└──────────────────────────────────────────────────────┘
```

The `TokenStorage` interface is defined in common; each platform provides its own implementation wired in at app startup (via `expect`/`actual` in KMP).

AppAuth (platform-specific) handles the browser redirect handoff — it can't be in common because it needs platform APIs. It calls into common once it has the auth code to exchange for tokens.

---

## Folder Structure

```
src/
  common/
    build.gradle.kts          ← new KMP shared module
    src/
      commonMain/kotlin/com/autom8ed/
        auth/
          AuthRepository.kt
          AuthViewModel.kt
          RavelryOAuthClient.kt   (Ktor)
          TokenStorage.kt         (interface)
          AuthToken.kt            (data model)
      androidMain/kotlin/com/autom8ed/
        auth/
          AndroidTokenStorage.kt  (EncryptedSharedPrefs)
      iosMain/kotlin/com/autom8ed/
        auth/
          IosTokenStorage.kt      (Keychain — future)
  android/
    app/src/main/kotlin/com/autom8ed/
      login/
        LoginScreen.kt        ← Compose UI
        BiometricHelper.kt    ← wraps BiometricPrompt
      MainActivity.kt         ← updated: navigate to login or home
```

---

## OAuth 2.0 PKCE Login Flow

```
1. User taps "Log in with Ravelry"
2. App generates code_verifier + code_challenge (SHA-256)
3. AppAuth opens system browser to:
     https://www.ravelry.com/oauth2/auth
       ?response_type=code
       &client_id=<id>
       &redirect_uri=fibersocial://auth/callback
       &code_challenge=<challenge>
       &code_challenge_method=S256
4. User logs in on Ravelry's website (we never see the password)
5. Ravelry redirects to fibersocial://auth/callback?code=<auth_code>
6. AppAuth intercepts the redirect, passes auth_code to app
7. App (Ktor) POSTs to /oauth2/token with auth_code + code_verifier
8. Ravelry returns { access_token, refresh_token, expires_in }
9. Tokens saved to EncryptedSharedPreferences (Android Keystore-backed)
10. App navigates to home screen
```

**Subsequent app opens (biometric unlock):**
```
1. App detects stored refresh_token exists
2. Show biometric prompt (fingerprint / face)
3. On success: use refresh_token to get new access_token silently
4. Navigate straight to home — no browser, no password
```

---

## Implementation Phases

### Phase 1 — KMP shared module scaffolding
- Add `src/common/build.gradle.kts` as a KMP library module
- Wire it into the Android app's `settings.gradle.kts` and `build.gradle.kts`
- Define `AuthToken`, `TokenStorage` interface, `AuthRepository`, `AuthViewModel` stubs
- Add Ktor and MOKO MVVM dependencies

### Phase 2 — OAuth login (Android)
- Register app at `ravelry.com/pro/developer`, get client ID
- Add AppAuth-Android dependency
- Implement `RavelryOAuthClient` (PKCE auth code flow + token exchange via Ktor)
- Implement `AndroidTokenStorage` (EncryptedSharedPreferences)
- Build `LoginScreen.kt` (single "Log in with Ravelry" button)
- Update `MainActivity` to route: if token exists → home, else → login
- Register `fibersocial://auth/callback` URI scheme in `AndroidManifest.xml`

### Phase 3 — Biometric unlock (Android)
- On login success, set a `biometric_enabled` flag in preferences
- On next app open: if flag set, show `BiometricPrompt` instead of login screen
- On biometric success: call `AuthRepository.refreshTokens()`
- On biometric failure / cancel: fall back to full OAuth login

### Phase 4 — iOS (future)
- Implement `IosTokenStorage` (Keychain) in `iosMain`
- Add AppAuth-iOS, wire up `fibersocial://auth/callback` URL scheme in Info.plist
- SwiftUI `LoginView` calls `AuthViewModel` from shared KMP module
- `LocalAuthentication` for biometric

---

## Open Questions Before Starting

1. **Client ID** — need to register at `ravelry.com/pro/developer`. What redirect URI and app description to use?
2. **Scopes** — Ravelry's scopes aren't publicly documented; need to check after registering. Likely need at minimum read access to groups/forums.
3. **Refresh token lifetime** — not documented publicly. Need to confirm whether refresh tokens expire and if so, on what cadence (forces re-login).
4. **KMP on Windows/WSL** — KMP compiling the `iosMain` source set requires the Kotlin/Native toolchain and ideally a Mac for iOS builds. On WSL, we'll need to ensure the Android build doesn't try to compile iOS targets.
