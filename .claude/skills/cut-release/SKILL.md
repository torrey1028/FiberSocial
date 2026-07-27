---
name: cut-release
description: Cut a signed public FiberSocial release by tagging main, and understand the versionCode packing scheme plus its dev-build "downgrade" trap. Use when asked to "cut a release", "tag a release", "publish a new version", or when a dev build won't install over a tagged one.
---

# Cut a release

Releases are **manual per-version tags**, not per-commit. Pick a `MAJOR.MINOR.PATCH`, tag `main`, push the tag — CI takes over from there, but Android and iOS now behave differently:

- **Android** (`release.yml`) still builds the signed APK/AAB and publishes them **immediately** as the latest GitHub Release — the pre-#265 flow, unchanged for now. A follow-up PR is expected to bring Android onto the same RC/QA-gated flow iOS has below, once the Play Console service-account/track setup it needs is ready.
- **iOS** (`release-ios.yml`) builds a signed **release candidate**, runs the automated test suites against it, and pushes it to a TestFlight QA group. It does **not** go live yet — a human still has to run manual QA and approve a GitHub Environment gate before it's ready for App Store submission (which itself stays a manual App Store Connect step regardless).

See root `CLAUDE.md`'s "Versioning & cutting a release" for the full detail on both.

## Fast path

From the repo root (`/home/betorr/FiberSocial`), on a clean `main` up to date with `origin/main`:

```bash
scripts/release.sh 1.4.0      # the leading "v" is optional; "v1.4.0" also works
```

That tags the current commit `v1.4.0`, pushes the tag, and prints both workflows' URLs. Pushing a `v*.*.*` tag triggers `.github/workflows/release.yml` and `.github/workflows/release-ios.yml` independently — one platform's runner speed doesn't block the other. Android's GitHub Release is live as soon as that job finishes; iOS's release candidate needs a reviewer to run manual QA (`docs/ios-device-checklist.md`) and approve the `ios-release` GitHub Environment on that run's `promote` job before App Store submission is appropriate — and even then, that submission is still a manual App Store Connect step (Apple review isn't API-triggerable).

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

CI supplies all of this from repo secrets (`RELEASE_KEYSTORE_BASE64` + password/alias/key-password), so you rarely build a signed release by hand — the tag push does it. `release.yml` **hard-fails** if any required secret is missing (unlike `android-build.yml`, which soft-skips release signing).

## Public download link

```
https://github.com/torrey1028/FiberSocial/releases/latest/download/app-release.apk
```

GitHub resolves `releases/latest` to whichever release was most recently marked `--latest`. **This updates only on a release-tag push** — not on ordinary pushes to `main`. Whatever you most recently tagged and pushed becomes the "latest" download.

## After tagging

`scripts/release.sh` pushes the tag and then hands off to CI — it does **not** wait for either build. Hand the printed URLs to the user: Android's GitHub Release appears once `release.yml` finishes, with no further action needed. For iOS, add a reminder that `release-ios.yml` only builds a release candidate — someone still needs to run manual QA against the TestFlight build and approve the `ios-release` environment gate on that run's `promote` job before App Store submission is appropriate, and that submission itself stays a manual App Store Connect step. Do not merge, approve that environment gate, or manually publish anything yourself.
