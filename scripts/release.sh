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
LOCAL="$(git rev-parse main)"
REMOTE="$(git rev-parse origin/main)"
if [ "$LOCAL" != "$REMOTE" ]; then
    echo "ERROR: local main ($LOCAL) doesn't match origin/main ($REMOTE)." >&2
    echo "  Pull the latest main before tagging a release." >&2
    exit 1
fi

if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "ERROR: tag $TAG already exists." >&2
    exit 1
fi

echo "Tagging $(git rev-parse --short HEAD) as $TAG and pushing..."
git tag -a "$TAG" -m "Release $TAG"
git push origin "$TAG"

echo ""
echo "Pushed $TAG. This triggers the Release workflow, which builds the signed"
echo "APK and publishes it at:"
echo "  https://github.com/torrey1028/FiberSocial/releases/tag/$TAG"
echo "  https://github.com/torrey1028/FiberSocial/releases/latest/download/app-release.apk"
