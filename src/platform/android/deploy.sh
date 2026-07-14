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

PACKAGE="com.myhobbyislearning.fibersocial"

# A debug APK can't update an installed release build (and vice versa): the
# signatures differ, and on this setup the incompatible install doesn't fail
# fast — it hangs for minutes mid-stream. Detect the mismatch up front via the
# DEBUGGABLE flag and uninstall the old build first. Anchored to the pkgFlags
# line: a bare "pkgFlags" grep would also scan privatePkgFlags.
INSTALLED_FLAGS=$(adb shell dumpsys package "$PACKAGE" 2>/dev/null | grep -E '^[[:space:]]*pkgFlags=' || true)
if [ -n "$INSTALLED_FLAGS" ]; then
    MISMATCH=0
    if [ "$BUILD_TYPE" = "debug" ]; then
        echo "$INSTALLED_FLAGS" | grep -q "DEBUGGABLE" || MISMATCH=1
    else
        echo "$INSTALLED_FLAGS" | grep -q "DEBUGGABLE" && MISMATCH=1
    fi
    if [ "$MISMATCH" -eq 1 ]; then
        INSTALLED_TYPE=$([ "$BUILD_TYPE" = "debug" ] && echo release || echo debug)
        echo ""
        echo "==> Installed app is a ${INSTALLED_TYPE} build; a ${BUILD_TYPE} APK can't update it in place."
        echo "    Uninstalling it first. NOTE: this clears app data — login, drawer group order,"
        echo "    and notification state are all lost (expect a re-login and possibly a burst of"
        echo "    already-seen event notifications)."
        # Tolerated failure: dumpsys can keep a pkgFlags line for uninstalled-but-
        # record-kept packages, where adb uninstall reports Failure but a plain
        # install succeeds. Dead-ending here would help nobody.
        adb uninstall "$PACKAGE" || echo "    (uninstall reported failure; continuing — the install may still succeed)"
    fi
fi

echo "Found $DEVICES device(s). Installing..."
# -d (allow downgrade): builds on a release-tagged commit get a tag-derived
# versionCode in the millions while ordinary dev builds are versionCode 1, so
# the first dev install after building on a tagged commit is a "downgrade".
# Only works debug-over-debug; replacing an installed *release* build with a
# debug one needs an uninstall (different signing key), which the mismatch
# check above performs automatically.
#
# No post-install `pm clear` needed for the reinstall's stale-keyset crash any
# more: the app self-heals a corrupted EncryptedSharedPreferences keyset on
# launch (see AndroidKeyValueStore.encryptedKeyValueStore), so a restored stale
# keyset is wiped and recreated rather than crashing.
adb install -r -d "$APK_PATH"

echo ""
echo "Done! FiberSocial installed on device."
