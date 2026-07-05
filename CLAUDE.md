# FiberSocial — Project Instructions

## Git Workflow

- **Never push directly to `main`.** All changes — including documentation, formatting, or one-line fixes — must go through a feature branch and a pull request.
- **Never merge a PR yourself.** After creating or updating a PR, hand the URL to the user and wait. Never run `gh pr merge` or any equivalent.
- Branch naming: `feat/`, `fix/`, `chore/`, `docs/` prefixes matching the commit type.

## Secrets

- Never commit `ravelry.client_secret`. It lives in the gitignored `local.properties` as `ravelry.client_secret` for local builds, and in the `RAVELRY_CLIENT_SECRET` GitHub Actions repo secret for CI builds (`android-build.yml` materializes it into CI's own `local.properties` — that injection is sanctioned, not a leak).

## Building & running the Android app

- `local.properties` is gitignored, so a fresh `git worktree add` checkout won't have it. Copy it from an existing checkout (`src/platform/android/local.properties`) before building, or OAuth login will fail with `invalid_client` (empty `ravelry.client_id`/`ravelry.client_secret`).
- `RAVELRY_CLIENT_ID`/`RAVELRY_CLIENT_SECRET` are `BuildConfig` fields generated from `local.properties`. Because they're compile-time-constant `String`s, Kotlin inlines their value into every call site at compile time — if you add/fix `local.properties` *after* an earlier build already ran without it, a plain `./gradlew assembleDebug` can report everything as `UP-TO-DATE` and skip recompiling those call sites, silently keeping the stale (empty) inlined value even though the generated `BuildConfig.java` itself looks correct. If auth fails with an empty `client_id` despite correct `local.properties`, run `./gradlew clean` before rebuilding.
- If the app crashes on launch with `AndroidKeysetManager`/Tink errors (`InvalidProtocolBufferException: Protocol message contained an invalid tag`), the on-device `EncryptedSharedPreferences` keyset is corrupted (can happen after an uninstall/reinstall cycle). Fix with `adb shell pm clear com.autom8ed.fibersocial` — this only clears the locally-stored OAuth token, so the user just logs in again.

## Logging

- Use `println("FiberSocial: ...")` for debug logging in common module code (visible in logcat as `System.out` tag).
- Rely on logcat for debugging — do not use screenshots as a substitute.
