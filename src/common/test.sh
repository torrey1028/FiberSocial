#!/bin/bash
set -e
COMMON_DIR="$(cd "$(dirname "$0")" && pwd)"

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

# Fetch the coverage baselines CI compares against (published by the latest
# successful main-branch run of tests.yml). Skipped when gh is unavailable,
# unauthenticated, or the artifacts don't exist yet.
BASELINE_DIR="$(mktemp -d)"
trap 'rm -rf "$BASELINE_DIR"' EXIT
JVM_BASELINE=""
ANDROID_BASELINE=""
if command -v gh >/dev/null 2>&1 && gh auth status >/dev/null 2>&1; then
    LATEST=$(gh run list --branch main --workflow tests.yml --status success \
        --limit 1 --json databaseId --jq '.[0].databaseId // empty' 2>/dev/null || true)
    if [ -n "$LATEST" ]; then
        if gh run download "$LATEST" --name jvm-coverage-baseline --dir "$BASELINE_DIR/jvm" 2>/dev/null; then
            JVM_BASELINE="$BASELINE_DIR/jvm/report.xml"
        fi
        if gh run download "$LATEST" --name android-coverage-baseline --dir "$BASELINE_DIR/android" 2>/dev/null; then
            ANDROID_BASELINE="$BASELINE_DIR/android/report.xml"
        fi
    fi
fi
if [ -z "$JVM_BASELINE" ] && [ -z "$ANDROID_BASELINE" ]; then
    echo "No coverage baseline available (gh missing/offline or no main run yet) — showing current coverage only."
fi

# Same totals + tolerance as the CI comparison in .github/workflows/tests.yml.
coverage_summary() {
    python3 - "$1" "$2" "$3" << 'PYEOF'
import os, sys, xml.etree.ElementTree as ET

label, report, baseline = sys.argv[1], sys.argv[2], sys.argv[3]
METRICS = ("INSTRUCTION", "BRANCH")

def total_coverage(path):
    # Top-level <counter> elements are the report-wide totals across all packages.
    root = ET.parse(path).getroot()
    return {
        c.get("type"): int(c.get("covered")) / (int(c.get("missed")) + int(c.get("covered")))
        for c in root.findall("counter")
        if c.get("type") in METRICS
    }

current = total_coverage(report)
if not baseline or not os.path.exists(baseline):
    for t in METRICS:
        print(f"{label} {t}: {current.get(t, 0):.1%}")
    sys.exit(0)

base = total_coverage(baseline)
failed = False
for t in METRICS:
    b, c = base.get(t, 0), current.get(t, 0)
    ok = c >= b - 0.001  # 0.1 % rounding tolerance
    print(f"{label} {t}: {c:.1%} vs baseline {b:.1%}  {'OK' if ok else 'REGRESSION'}")
    if not ok:
        failed = True
sys.exit(1 if failed else 0)
PYEOF
}

echo ""
echo "Coverage (common module):"
FAILED=0
coverage_summary "JVM" "$JVM_REPORT" "$JVM_BASELINE" || FAILED=1
coverage_summary "Android" "$ANDROID_REPORT" "$ANDROID_BASELINE" || FAILED=1

if [ "$FAILED" -ne 0 ]; then
    echo ""
    echo "Coverage regressed vs the main baseline — CI will fail this too." >&2
    exit 1
fi
