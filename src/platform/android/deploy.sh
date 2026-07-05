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

echo "Found $DEVICES device(s). Installing..."
adb install -r "$APK_PATH"

echo ""
echo "Done! FiberSocial installed on device."
