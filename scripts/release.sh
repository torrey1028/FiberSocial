#!/bin/bash
# Tags and pushes a release version, triggering .github/workflows/release.yml
# to build the signed APK and publish it as a GitHub Release.
#
# Usage: scripts/release.sh 1.2.0   (or v1.2.0 - the "v" prefix is optional)
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR/.."

if [ -z "$1" ]; then
    echo "Usage: $0 <version>   e.g. $0 1.2.0" >&2
    exit 1
fi

VERSION="${1#v}"
if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: '$1' isn't a MAJOR.MINOR.PATCH version (e.g. 1.2.0)" >&2
    exit 1
fi
TAG="v$VERSION"

# app/build.gradle.kts packs versionCode = major*1_000_000 + minor*1_000 + patch,
# so minor/patch only get three digits — out-of-range components silently collide
# with a neighboring version's code (v1.2.1000 == v1.3.0), major >= 2147 overflows
# Int, and v0.0.0 packs to versionCode 0 which Android rejects.
IFS=. read -r MAJOR MINOR PATCH <<< "$VERSION"
if [ "$MAJOR" -gt 2146 ] || [ "$MINOR" -gt 999 ] || [ "$PATCH" -gt 999 ] || [ "$VERSION" = "0.0.0" ]; then
    echo "ERROR: $VERSION doesn't fit the versionCode scheme (major <= 2146, minor/patch <= 999, above 0.0.0)." >&2
    exit 1
fi

BRANCH="$(git rev-parse --abbrev-ref HEAD)"
if [ "$BRANCH" != "main" ]; then
    echo "ERROR: you're on '$BRANCH', not main. Switch to main before tagging a release." >&2
    exit 1
fi

if [ -n "$(git status --porcelain)" ]; then
    echo "ERROR: working tree isn't clean. Commit, stash, or discard changes first." >&2
    exit 1
fi

git fetch origin main
LOCAL="$(git rev-parse HEAD)"
REMOTE="$(git rev-parse origin/main)"
if [ "$LOCAL" != "$REMOTE" ]; then
    echo "ERROR: local main ($LOCAL) doesn't match origin/main ($REMOTE)." >&2
    echo "  Pull the latest main before tagging a release." >&2
    exit 1
fi

# refs/tags/ explicitly: a plain `git rev-parse vX.Y.Z` also resolves branches
# named like the tag. `git fetch origin main` above does NOT fetch tags created
# from another machine (tag auto-following only covers newly downloaded
# objects), so ask the remote directly too.
if git rev-parse -q --verify "refs/tags/$TAG" >/dev/null; then
    echo "ERROR: tag $TAG already exists locally." >&2
    exit 1
fi
if git ls-remote --exit-code --tags origin "refs/tags/$TAG" >/dev/null; then
    echo "ERROR: tag $TAG already exists on origin." >&2
    exit 1
fi

echo "Tagging $(git rev-parse --short HEAD) as $TAG and pushing..."
git tag -a "$TAG" -m "Release $TAG"
if ! git push origin "$TAG"; then
    # Don't leave a stale local tag behind: it would make every retry of this
    # script fail at the exists-locally check above until deleted by hand.
    git tag -d "$TAG" >/dev/null
    echo "ERROR: pushing $TAG failed; removed the local tag so a retry starts clean." >&2
    exit 1
fi

echo ""
echo "Pushed $TAG. This triggers the Release workflow, which builds the signed"
echo "APK and publishes it at:"
echo "  https://github.com/torrey1028/FiberSocial/releases/tag/$TAG"
echo "  https://github.com/torrey1028/FiberSocial/releases/latest/download/app-release.apk"
