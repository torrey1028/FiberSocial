---
name: build-and-run
description: Build, install, run, and observe the FiberSocial app on Android and iOS, including fresh-worktree first-run setup (local.properties/secrets), the background-build + logcat debug loop, and keyset-corruption recovery. Use when asked to build, deploy, run, launch, or debug the app on a device or simulator.
---

# build-and-run

Build, install, run, and observe FiberSocial (Kotlin Multiplatform: Android + iOS).

**Working directory for all `./gradlew` / `./deploy.sh`:** `src/platform/android/` (the Gradle root is NOT the repo root). App id: `com.autom8ed.fibersocial`.

For starting new work in an isolated worktree, cutting PRs, and never editing the primary checkout, see the **fibersocial-git-workflow** skill. For the test suite + coverage gate, see the **fibersocial-testing** skill.

---

## A. Android — build, deploy, run

Fast path (from `src/platform/android/`):

```bash
# build only
./gradlew assembleDebug
# → app/build/outputs/apk/debug/app-debug.apk

# build + install to a connected device (relaunch manually after)
./deploy.sh            # debug
./deploy.sh --release  # release (needs signing keystore in local.properties)
```

`deploy.sh` builds, checks for a connected device, auto-uninstalls on a debug↔release signature mismatch, then `adb install -r -d`.

**Confirm the device is free before any `adb` command** — another agent may be mid-session on the same device:

```bash
adb devices
```

**TRAP — never chain a gradle build into `adb install` with `&&`.** Run the build as its own command in the **background**; install as a separate step after it finishes. A silent long foreground build reads as "stuck," and the user has interrupted such builds.

```bash
# run the build in the background (separate command), then install separately
./gradlew assembleDebug   # ← run_in_background: true
```

**TRAP — after firing a background build, do NOT poll it with an `until`/`while` loop — those loops hang.** The harness re-invokes you with a `<task-notification>` (including the exit code) when the build exits. Only then, read the log with ONE non-blocking command:

```bash
tail -n 40 <build-log>
grep -iE 'error|failed|BUILD' <build-log> | tail -n 20
```

---

## B. local.properties + secrets (fresh-worktree first run)

`local.properties` is **gitignored**, so a fresh `git worktree add` checkout does NOT have it. Without it, `RAVELRY_CLIENT_ID`/`RAVELRY_CLIENT_SECRET` are empty and OAuth login fails with **`invalid_client`**.

Copy the real working credentials in before the first build:

```bash
cp /mnt/c/Users/torre/FiberSocial/src/platform/android/local.properties \
   src/platform/android/local.properties
```

Then fix `sdk.dir` for this WSL box (the source file's `sdk.dir` may be a Windows path that won't resolve here):

```bash
# from src/platform/android/
sed -i 's|^sdk.dir=.*|sdk.dir=/home/betorr/android-sdk|' local.properties
```

**NEVER `Read`/`cat`/`echo` `local.properties`** — it holds a real client secret and would dump it into the transcript. To inspect it, redact values via `sed`; to edit it, use `sed`/`cat >>` (never Read+Edit):

```bash
# redacted inspection — shows which keys are present, hides secrets
sed -n 's/=.*/=<redacted>/p' src/platform/android/local.properties
```

Required keys for a working debug login: `sdk.dir`, `ravelry.client_id`, `ravelry.client_secret`. Release signing adds `release.store.file` / `release.store.password` / `release.key.alias` / `release.key.password`. **Never commit `ravelry.client_secret`** — CI injects it from the `RAVELRY_CLIENT_SECRET` Actions secret.

**TRAP — BuildConfig inlining leaves stale empty creds.** `RAVELRY_CLIENT_ID`/`RAVELRY_CLIENT_SECRET` are compile-time-constant `String` `buildConfigField`s read from `local.properties` (`app/build.gradle.kts` lines 60–61). Kotlin inlines their value into every call site at compile time. If you add or fix `local.properties` *after* a build already ran without it, a plain `./gradlew assembleDebug` can report `UP-TO-DATE` and keep the stale **empty** `client_id` — even though the generated `BuildConfig.java` looks correct — and login still fails `invalid_client`. Fix: force a clean rebuild.

```bash
# from src/platform/android/, after adding/fixing creds
./gradlew clean
./gradlew assembleDebug
```

iOS mirror of this file: `src/platform/ios/Config.local.xcconfig` (gitignored) — see section D.

---

## C. Keyset corruption on launch (recovery)

Symptom on launch: Tink `AndroidKeysetManager` errors, e.g. `InvalidProtocolBufferException: Protocol message contained an invalid tag` — typically after an uninstall/reinstall cycle.

This is now **self-healed**: `encryptedKeyValueStore` wipes the unreadable `EncryptedSharedPreferences` file and recreates it, so the app just asks the user to log in again instead of crashing.

**GOTCHA — a build predating that fix still crashes this way.** Manual recovery:

```bash
adb shell pm clear com.autom8ed.fibersocial
```

Root cause (issue #188): auth prefs (`fibersocial_auth`) were in Android cloud auto-backup, but the Tink master key is device-bound, so every restore re-seeds an undecryptable keyset. `deploy.sh` no longer needs a post-install `pm clear` — the self-heal handles the reinstall case.

---

## D. iOS — build, run, test

Xcode project `src/platform/ios/FiberSocial.xcodeproj`, scheme `FiberSocial`. Build/test on the simulator from `src/platform/ios/`:

```bash
# from src/platform/ios/
xcodebuild -project FiberSocial.xcodeproj -scheme FiberSocial \
  -destination 'platform=iOS Simulator,name=iPhone 17' build
# ...or 'test' instead of 'build'
```

**Kotlin changes rebuild automatically:** the project's "Build ComposeApp framework" phase runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode` in `src/platform/android`, so a Kotlin edit is picked up by an Xcode/`xcodebuild` build with no manual gradle step. (That phase validates the JDK by major version — `$JAVA_HOME`, then `java_home -v 17`, then homebrew `openjdk@17` — because an inherited `JAVA_HOME` may point at an old JDK.)

**Secrets mirror `local.properties`:** put `RAVELRY_CLIENT_ID`/`RAVELRY_CLIENT_SECRET` in the gitignored `src/platform/ios/Config.local.xcconfig`. The committed `Config.xcconfig` has empty defaults plus `#include? "Config.local.xcconfig"`. Missing → app runs but login fails `invalid_client`.

**GOTCHA — keychain tests self-skip on the bare Kotlin/Native runner.** The hosted XCTests (in `xcodebuild test`) cover the Keychain/NSUserDefaults stores; the bare K/N test runner (`:composeApp:iosSimulatorArm64Test`) has no keychain daemon, so its keychain write-tests skip themselves there by design — not a failure.

**GOTCHA — `BGAppRefreshTask` needs a real device.** The simulator rejects background-task submission (logged, harmless); background new-event polling can only be observed on a physical device.

---

## E. Logging & observing behavior

Debug logging in common (shared Kotlin) code:

```kotlin
println("FiberSocial: ...")   // appears in logcat under the System.out tag
```

**Rely on logcat, not screenshots**, for debugging:

```bash
adb logcat -s System.out          # follow FiberSocial: lines
adb logcat -d | grep FiberSocial  # one-shot dump
```
