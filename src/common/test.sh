#!/bin/bash
set -e
COMMON_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$COMMON_DIR/../.." && pwd)"
# Explicit --repo target: without it, gh infers the repo from git remotes and
# errors out (or picks a fork) in multi-remote clones — silently, since the
# baseline fetch below deliberately swallows stderr.
REPO="torrey1028/FiberSocial"

# The report tasks depend on :common:jvmTest / :common:testDebugUnitTest, so
# this runs the same tests as before and additionally produces Jacoco reports.
cd "$COMMON_DIR/../platform/android"
./gradlew :common:jvmCoverageReport :common:createDebugUnitTestCoverageReport

JVM_REPORT="$COMMON_DIR/build/reports/jacoco/jvmCoverageReport/report.xml"
ANDROID_REPORT="$COMMON_DIR/build/reports/coverage/test/debug/report.xml"

if ! command -v python3 >/dev/null 2>&1; then
    echo "WARNING: python3 not found — skipping coverage summary." >&2
    exit 0
fi

for report in "$JVM_REPORT" "$ANDROID_REPORT"; do
    if [ ! -f "$report" ]; then
        echo "ERROR: expected coverage report not found: $report" >&2
        echo "       (did a Gradle/AGP upgrade move the report output?)" >&2
        exit 1
    fi
done

# Fetch the coverage baselines CI compares against (published by the latest
# successful main-branch run of tests.yml). Downloads are cached per run ID —
# the baseline only changes when a new main run succeeds. Skipped when gh is
# unavailable/unauthenticated/offline or the artifacts don't exist yet.
BASELINE_DIR="$COMMON_DIR/build/coverage-baseline"
JVM_BASELINE=""
ANDROID_BASELINE=""
if command -v gh >/dev/null 2>&1; then
    LATEST=$(gh run list --repo "$REPO" --branch main --workflow tests.yml --status success \
        --limit 1 --json databaseId --jq '.[0].databaseId // empty' 2>/dev/null || true)
    if [ -n "$LATEST" ]; then
        if [ "$(cat "$BASELINE_DIR/run-id" 2>/dev/null)" != "$LATEST" ]; then
            rm -rf "$BASELINE_DIR"
            mkdir -p "$BASELINE_DIR"
            gh run download "$LATEST" --repo "$REPO" --name jvm-coverage-baseline \
                --dir "$BASELINE_DIR/jvm" 2>/dev/null || true
            gh run download "$LATEST" --repo "$REPO" --name android-coverage-baseline \
                --dir "$BASELINE_DIR/android" 2>/dev/null || true
            echo "$LATEST" > "$BASELINE_DIR/run-id"
        fi
        if [ -f "$BASELINE_DIR/jvm/report.xml" ]; then
            JVM_BASELINE="$BASELINE_DIR/jvm/report.xml"
        fi
        if [ -f "$BASELINE_DIR/android/report.xml" ]; then
            ANDROID_BASELINE="$BASELINE_DIR/android/report.xml"
        fi
    fi
fi
if [ -z "$JVM_BASELINE" ]; then
    echo "No JVM coverage baseline available (gh missing/offline or no main run yet)."
fi
if [ -z "$ANDROID_BASELINE" ]; then
    echo "No Android coverage baseline available (gh missing/offline or no main run yet)."
fi

echo ""
echo "Coverage (common module):"
# Same comparison script CI runs (.github/workflows/tests.yml), so the
# metrics and tolerance cannot drift between the local gate and CI.
COMPARE="$REPO_ROOT/scripts/compare_coverage.py"
REGRESSED=0
SCRIPT_ERROR=0
run_compare() {
    local rc=0
    python3 "$COMPARE" "$@" || rc=$?
    if [ "$rc" -eq 1 ]; then
        REGRESSED=1
    elif [ "$rc" -ne 0 ]; then
        SCRIPT_ERROR=1
    fi
}
run_compare "JVM" "$JVM_REPORT" "$JVM_BASELINE"
run_compare "Android" "$ANDROID_REPORT" "$ANDROID_BASELINE"

if [ "$SCRIPT_ERROR" -ne 0 ]; then
    echo ""
    echo "Coverage comparison could not run (see ERROR above) — this is NOT a coverage regression." >&2
    exit 1
fi
if [ "$REGRESSED" -ne 0 ]; then
    echo ""
    echo "Coverage regressed vs the main baseline — CI will fail this too." >&2
    exit 1
fi
