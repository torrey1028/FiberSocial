#!/usr/bin/env python3
"""Compare report-wide Jacoco coverage totals against a baseline report.

Single source of truth for the coverage gate: called by both CI
(.github/workflows/tests.yml) and the local runner (src/common/test.sh),
so the metrics and tolerance cannot drift between them.

Usage: compare_coverage.py LABEL REPORT_XML [BASELINE_XML]

Prints INSTRUCTION/BRANCH percentages; with a baseline, marks each metric
OK/REGRESSION using a 0.1% rounding tolerance.

Exit codes: 0 = ok (or no baseline to compare), 1 = regression,
2 = could not run (missing/corrupt report) — callers must not report 2 as
a coverage regression.
"""
import sys
import xml.etree.ElementTree as ET

METRICS = ("INSTRUCTION", "BRANCH")
TOLERANCE = 0.001  # 0.1 % rounding tolerance


def total_coverage(path):
    # Top-level <counter> elements are the report-wide totals across all packages.
    root = ET.parse(path).getroot()
    totals = {}
    for c in root.findall("counter"):
        if c.get("type") in METRICS:
            covered, missed = int(c.get("covered")), int(c.get("missed"))
            if covered + missed > 0:
                totals[c.get("type")] = covered / (missed + covered)
    return totals


def main():
    label, report = sys.argv[1], sys.argv[2]
    baseline = sys.argv[3] if len(sys.argv) > 3 else ""

    current = total_coverage(report)
    if not baseline:
        for t in METRICS:
            print(f"{label} {t}: {current.get(t, 0):.1%}  (no baseline)")
        return 0

    base = total_coverage(baseline)
    failed = False
    for t in METRICS:
        b, c = base.get(t, 0), current.get(t, 0)
        ok = c >= b - TOLERANCE
        print(f"{label} {t}: {c:.1%} vs baseline {b:.1%}  {'OK' if ok else 'REGRESSION'}")
        if not ok:
            failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # a missing/corrupt report must not read as a regression
        print(f"ERROR: coverage comparison could not run: {e}", file=sys.stderr)
        sys.exit(2)
