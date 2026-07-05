# FiberSocial — Project Instructions

## Git Workflow

- **Never push directly to `main`.** All changes — including documentation, formatting, or one-line fixes — must go through a feature branch and a pull request.
- **Never edit files in the primary checkout to start new work — always create an isolated worktree first.** Before touching any file, run `git worktree add ../FiberSocial-<slug> -b <type>/<slug>` and do the work there. This isn't optional even for "just config" changes: the primary checkout routinely has in-progress uncommitted edits sitting in it, and editing in place mixes unrelated changes into the same diff, making it impossible to cut a clean single-purpose PR without dragging along someone else's WIP (or reverting it by accident).
- **Never merge a PR yourself.** After creating or updating a PR, hand the URL to the user and wait. Never run `gh pr merge` or any equivalent.
- Branch naming: `feat/`, `fix/`, `chore/`, `docs/` prefixes matching the commit type.

## Secrets

- Never commit `ravelry.client_secret`. It lives in the gitignored `local.properties` as `ravelry.client_secret` for local builds, and in the `RAVELRY_CLIENT_SECRET` GitHub Actions repo secret for CI builds (`android-build.yml` materializes it into CI's own `local.properties` — that injection is sanctioned, not a leak).
- Same pattern for release signing: the keystore and its passwords live in `local.properties`/a gitignored keystore file locally, and in the `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`, `RELEASE_KEY_ALIAS`, `RELEASE_KEY_PASSWORD` GitHub Actions repo secrets for CI (`android-build.yml` decodes the base64 secret back into a keystore file and writes the rest into CI's `local.properties`). Unlike the Ravelry secrets, these are optional — CI skips release signing/build entirely (not a failure) when `RELEASE_KEYSTORE_BASE64` isn't set.

## Building & running the Android app

- `local.properties` is gitignored, so a fresh `git worktree add` checkout won't have it. Copy it from an existing checkout (`src/platform/android/local.properties`) before building, or OAuth login will fail with `invalid_client` (empty `ravelry.client_id`/`ravelry.client_secret`).
- `RAVELRY_CLIENT_ID`/`RAVELRY_CLIENT_SECRET` are `BuildConfig` fields generated from `local.properties`. Because they're compile-time-constant `String`s, Kotlin inlines their value into every call site at compile time — if you add/fix `local.properties` *after* an earlier build already ran without it, a plain `./gradlew assembleDebug` can report everything as `UP-TO-DATE` and skip recompiling those call sites, silently keeping the stale (empty) inlined value even though the generated `BuildConfig.java` itself looks correct. If auth fails with an empty `client_id` despite correct `local.properties`, run `./gradlew clean` before rebuilding.
- If the app crashes on launch with `AndroidKeysetManager`/Tink errors (`InvalidProtocolBufferException: Protocol message contained an invalid tag`), the on-device `EncryptedSharedPreferences` keyset is corrupted (can happen after an uninstall/reinstall cycle). Fix with `adb shell pm clear com.autom8ed.fibersocial` — this only clears the locally-stored OAuth token, so the user just logs in again.

## Release builds

- `./gradlew assembleRelease` (or `./deploy.sh --release`) needs a signing keystore, configured the same way as the Ravelry secrets: gitignored, read from `local.properties` at build time. `app/build.gradle.kts` only creates the `release` signing config if `release.store.file` is set, so builds without it stay unsigned rather than failing.
- Required `local.properties` keys: `release.store.file` (path relative to `src/platform/android/`), `release.store.password`, `release.key.alias`, `release.key.password`.
- The actual keystore file (`*.keystore`/`*.jks`, gitignored) doesn't come along with `git worktree add` any more than `local.properties` does — copy it into a fresh worktree from an existing checkout alongside `local.properties`, don't regenerate it (a new keystore produces a different signature and can't upgrade-install over an app signed by the old one).
- CI (`android-build.yml`) builds and uploads a signed `app-release` artifact too, once the `RELEASE_KEYSTORE_BASE64` repo secret (and its companion password/alias secrets) are set — see the Secrets section above.
- Every push to `main` also republishes a rolling `latest` GitHub Release with the signed APK attached, giving a permanent, unauthenticated download link that always points at the newest build: `https://github.com/torrey1028/FiberSocial/releases/latest/download/app-release.apk`. The workflow deletes and recreates the `latest` tag/release each run (`gh release delete latest --cleanup-tag` then `gh release create latest --target "$GITHUB_SHA"`) so it always points at the commit that was actually built, rather than editing an older release in place.

## Logging

- Use `println("FiberSocial: ...")` for debug logging in common module code (visible in logcat as `System.out` tag).
- Rely on logcat for debugging — do not use screenshots as a substitute.
