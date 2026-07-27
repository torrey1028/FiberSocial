---
name: cut-release
description: Cut a signed public FiberSocial release by tagging main, and understand the versionCode packing scheme plus its dev-build "downgrade" trap. Use when asked to "cut a release", "tag a release", "publish a new version", or when a dev build won't install over a tagged one.
---

# Cut a release

Releases are **manual per-version tags**, not per-commit. Pick a `MAJOR.MINOR.PATCH`, tag `main`, push the tag — CI builds a signed **release candidate** on each platform, runs the automated test suites against it, and pushes it to a QA testing channel. Neither goes live yet (issue #265) — a human still has to run manual QA and approve a GitHub Environment gate before it's actually released. See root `CLAUDE.md`'s "Versioning & cutting a release" for the full RC → QA → promote flow.

## Fast path

From the repo root (`/home/betorr/FiberSocial`), on a clean `main` up to date with `origin/main`:

```bash
scripts/release.sh 1.4.0      # the leading "v" is optional; "v1.4.0" also works
```

That tags the current commit `v1.4.0`, pushes the tag, and prints the run URLs. Pushing a `v*.*.*` tag triggers `.github/workflows/release-android.yml` and `.github/workflows/release-ios.yml` independently. Each runs its automated tests, builds the signed release candidate, and publishes it somewhere installable for QA (Android: a `--prerelease` GitHub Release + the Play Console `PLAY_TESTING_TRACK` track; iOS: App Store Connect/TestFlight, auto-added to the `IOS_TESTFLIGHT_QA_GROUP_NAME` external group). Going fully live needs a reviewer to run manual QA (`docs/android-release-qa-checklist.md` / `docs/ios-device-checklist.md`) and then approve the `android-release` / `ios-release` GitHub Environment on that run's `promote` job — only then does Android's AAB get pushed to the Play `production` track and the GitHub Release flip from prerelease to `--latest`. iOS's `promote` job still can't submit to the App Store automatically (Apple review isn't API-triggerable); that step stays manual in App Store Connect even after approval.

### Preconditions `scripts/release.sh` enforces (it aborts otherwise)

- Current branch **is** `main` (not a worktree branch).
- Working tree is **clean** (`git status --porcelain` empty).
- Local `HEAD` **equals** `origin/main` (it runs `git fetch origin main` first).
- Version fits the packing scheme: `major <= 2146`, `minor <= 999`, `patch <= 999`, and above `0.0.0`.
- Tag `v<version>` does **not** already exist locally **or** on `origin` (checks `refs/tags/` on both).

**GOTCHA:** the release tag is cut from `main` directly — this is the one sanctioned exception to "never push to main" (you push a *tag*, not a commit). All *code* still lands on main via PRs first. See the **fibersocial-git-workflow** skill.

## versionCode packing

`src/platform/android/app/build.gradle.kts` derives the version **only when HEAD sits exactly on a `v*` tag** (`git describe --tags --exact-match --match 'v[0-9]*.[0-9]*.[0-9]*'`):

```
versionCode = MAJOR * 1_000_000 + MINOR * 1_000 + PATCH
versionName = "MAJOR.MINOR.PATCH"
```

Any non-tagged (ordinary dev) build gets `versionCode = 1` and the git-hash `versionName`.

**TRAP — versionCode must strictly increase release-over-release.** Android's in-place upgrade install refuses to install a lower `versionCode` over a higher one. **Never tag a lower version after a higher one has shipped** (e.g. don't tag `v1.3.0` after `v1.4.0` — `1_003_000 < 1_004_000`, so the "upgrade" is a rejected downgrade). The three-digit minor/patch fields also mean out-of-range components silently collide (`v1.2.1000` packs identical to `v1.3.0`); the script's range check exists to catch exactly this.

**TRAP — the dev-build downgrade.** The derivation keys off the **commit, not the build type**. If you run a debug `./deploy.sh` build while HEAD happens to sit on a release tag, that debug build inherits the big tag-derived `versionCode` (e.g. `1_004_000`). The **next** ordinary dev build drops back to `versionCode = 1`, so installing it looks like a downgrade to Android. This is why `deploy.sh` installs with `adb install -r -d` (allow version downgrade). If a plain `adb install` ever fails with an `INSTALL_FAILED_VERSION_DOWNGRADE`, this is why.

## Release signing

The signed APK needs a keystore plus the `release.*` keys in `src/platform/android/local.properties`:

- `release.store.file` (path **relative to `src/platform/android/`**)
- `release.store.password`
- `release.key.alias`
- `release.key.password`

**GOTCHA — no keys means UNSIGNED, not a failure.** `app/build.gradle.kts` only creates the `release` signing config when `release.store.file` is set and non-blank; otherwise `assembleRelease` still succeeds but emits `app-release-unsigned.apk` (unsigned) instead of `app-release.apk`. A missing signing config produces a silently-unsigned artifact, not an error.

**GOTCHA — the keystore file does NOT travel with a worktree.** Both `local.properties` and the `*.keystore`/`*.jks` file are gitignored, so a fresh `git worktree add` checkout has neither. **Copy** the keystore in from an existing checkout — **do not regenerate it.** A new keystore has a different signature and cannot upgrade-install over an app signed by the old key. To inspect `local.properties` for the `release.*` keys without dumping the secret into the transcript:

```bash
sed -n 's/=.*/=<redacted>/p' src/platform/android/local.properties
```

Never `cat`/`Read`/`echo` `local.properties` — it holds `ravelry.client_secret`. See the **build-and-run** skill for the full `local.properties`/keystore setup.

CI supplies all of this from repo secrets (`RELEASE_KEYSTORE_BASE64` + password/alias/key-password), so you rarely build a signed release by hand — the tag push does it. `release-android.yml` **hard-fails** if any required secret is missing (unlike `android-build.yml`, which soft-skips release signing).

## Public download link

```
https://github.com/torrey1028/FiberSocial/releases/latest/download/app-release.apk
```

GitHub resolves `releases/latest` to whichever release was most recently marked `--latest`. **This updates only when the Android `promote` job runs** — not on the initial tag push (which publishes a `--prerelease`, not `--latest`) and not on ordinary pushes to `main`. Whatever tag was most recently tagged, QA'd, and approved becomes the "latest" download.

## After tagging

`scripts/release.sh` pushes the tag and then hands off to CI — it does **not** wait for the build, and the build does **not** go live on its own. Hand the printed run URLs to the user, along with a reminder that this only builds a release candidate: someone still needs to run manual QA against it and approve the `android-release`/`ios-release` environment gate on each run's `promote` job before it's actually released. Do not merge, approve those environment gates, or manually publish anything yourself.
