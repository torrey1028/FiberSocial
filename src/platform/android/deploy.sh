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

PACKAGE="com.autom8ed.fibersocial"

# A debug APK can't update an installed release build (and vice versa): the
# signatures differ, and on this setup the incompatible `adb install -r`
# doesn't fail fast — it hangs for minutes mid-stream. Detect the mismatch
# up front via the DEBUGGABLE flag and uninstall the old build first.
INSTALLED_FLAGS=$(adb shell dumpsys package "$PACKAGE" 2>/dev/null | grep pkgFlags || true)
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
        echo "    Uninstalling it first. NOTE: this clears app data — you'll need to log in again."
        adb uninstall "$PACKAGE"
    fi
fi

echo "Found $DEVICES device(s). Installing..."
adb install -r "$APK_PATH"

echo ""
echo "Done! FiberSocial installed on device."
