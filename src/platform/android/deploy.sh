#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$SCRIPT_DIR"

BUILD_TYPE="debug"
if [ "$1" = "--release" ]; then
    BUILD_TYPE="release"
fi

echo "==> Building ${BUILD_TYPE} APK..."
if [ "$BUILD_TYPE" = "release" ]; then
    ./gradlew assembleRelease
else
    ./gradlew assembleDebug
fi

APK_PATH="app/build/outputs/apk/${BUILD_TYPE}/app-${BUILD_TYPE}.apk"
if [ "$BUILD_TYPE" = "release" ] && [ ! -f "$APK_PATH" ]; then
    echo "ERROR: $APK_PATH wasn't produced — the release build has no signing config, so"
    echo "  the Android Gradle Plugin named it app-release-unsigned.apk instead."
    echo "  Add release.store.file/release.store.password/release.key.alias/release.key.password"
    echo "  to local.properties (see CLAUDE.md's Release builds section)."
    exit 1
fi

echo ""
echo "==> Checking for connected devices..."
DEVICES=$(adb devices | grep -v "List of devices" | grep "device$" | wc -l)

if [ "$DEVICES" -eq 0 ]; then
    echo "ERROR: No Android device detected."
    echo "  - Ensure USB debugging is enabled on your phone"
    echo "  - Try: adb kill-server && adb start-server"
    exit 1
fi

# The debug build carries applicationIdSuffix ".debug" (app/build.gradle.kts),
# so it installs alongside a release/Play build with fully separate data rather
# than replacing it. That also means the two build types can never collide on
# the same package any more — but a build predating the suffix left a debug APK
# installed under the *release* package, and that leftover still can, which is
# what the check below is now for.
RELEASE_PACKAGE="com.myhobbyislearning.fibersocial"
PACKAGE="$RELEASE_PACKAGE"
[ "$BUILD_TYPE" = "debug" ] && PACKAGE="${RELEASE_PACKAGE}.debug"

# A debug APK can't update an installed release build (and vice versa): the
# signatures differ, and on this setup the incompatible install doesn't fail
# fast — it hangs for minutes mid-stream. Detect the mismatch up front via the
# DEBUGGABLE flag. Anchored to the pkgFlags line: a bare "pkgFlags" grep would
# also scan privatePkgFlags.
INSTALLED_FLAGS=$(adb shell dumpsys package "$RELEASE_PACKAGE" 2>/dev/null | grep -E '^[[:space:]]*pkgFlags=' || true)
if [ -n "$INSTALLED_FLAGS" ] && echo "$INSTALLED_FLAGS" | grep -q "DEBUGGABLE"; then
    echo ""
    echo "==> $RELEASE_PACKAGE holds a DEBUGGABLE build — a pre-suffix debug install."
    if [ "$BUILD_TYPE" = "release" ]; then
        echo "    A release APK can't update it in place (different signing key), so it has to go."
    else
        echo "    Debug builds now install as $PACKAGE instead, so this one is a stale duplicate"
        echo "    that would otherwise sit on the home screen forever and block a Play install."
    fi
    echo "    Uninstalling it. NOTE: this clears that install's app data — login, drawer group"
    echo "    order, and notification state are all lost (expect a re-login and possibly a burst"
    echo "    of already-seen event notifications)."
    # Tolerated failure: dumpsys can keep a pkgFlags line for uninstalled-but-
    # record-kept packages, where adb uninstall reports Failure but a plain
    # install succeeds. Dead-ending here would help nobody.
    adb uninstall "$RELEASE_PACKAGE" || echo "    (uninstall reported failure; continuing — the install may still succeed)"
fi

echo "Found $DEVICES device(s). Installing..."
# -d (allow downgrade): builds on a release-tagged commit get a tag-derived
# versionCode in the millions while ordinary dev builds are versionCode 1, so
# the first dev install after building on a tagged commit is a "downgrade".
# Only works within one package/signing key, which is all this ever does now
# that the two build types have their own applicationIds.
#
# No post-install `pm clear` needed for the reinstall's stale-keyset crash any
# more: the app self-heals a corrupted EncryptedSharedPreferences keyset on
# launch (see AndroidKeyValueStore.encryptedKeyValueStore), so a restored stale
# keyset is wiped and recreated rather than crashing.
adb install -r -d "$APK_PATH"

echo ""
echo "Done! ${PACKAGE} installed on device."
