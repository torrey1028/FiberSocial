#!/bin/bash
set -e
cd "$(dirname "$0")"
./gradlew :composeApp:testDebugUnitTest :app:testDebugUnitTest
