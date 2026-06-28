#!/bin/bash
set -e
cd "$(dirname "$0")/../platform/android"
./gradlew :common:jvmTest :common:testDebugUnitTest
