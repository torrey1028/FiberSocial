#!/usr/bin/env python3
"""Compare report-wide Jacoco coverage totals against a baseline report.

Single source of truth for the coverage gate: called by both CI
(.github/workflows/tests.yml) and the local runner (src/common/test.sh),
so the metrics and tolerance cannot drift between them.

Usage: compare_coverage.py LABEL REPORT_XML [BASELINE_XML]

Prints INSTRUCTION/BRANCH percentages; with a baseline, marks each metric
OK/REGRESSION. Below HIGH_COVERAGE_THRESHOLD, only a 0.1% rounding
tolerance is allowed. Once baseline coverage is above that threshold,
PRs may regress it by up to (but not including) 1 point — past that
threshold we're into diminishing returns on squeezing out more coverage,
so a small regression there isn't worth blocking a PR over. On regression
it also prints exactly what to fix: how many more units must be covered
to pass, and a method-level diff of where coverage got worse relative to
the baseline — so the gap can be closed without re-running coverage
locally.

Exit codes: 0 = ok (or no baseline to compare), 1 = regression,
2 = could not run (missing/corrupt report) — callers must not report 2 as
a coverage regression.
"""
import math
import sys
import xml.etree.ElementTree as ET

METRICS = ("INSTRUCTION", "BRANCH")
ROUNDING_TOLERANCE = 0.001  # 0.1 % rounding tolerance, always applied
HIGH_COVERAGE_THRESHOLD = 0.85
HIGH_COVERAGE_REGRESSION_ALLOWANCE = 0.01  # < 1 point regression allowed above the threshold
MAX_DIFF_ROWS = 25


def allowed_drop(baseline):
    if baseline > HIGH_COVERAGE_THRESHOLD:
        return HIGH_COVERAGE_REGRESSION_ALLOWANCE
    return ROUNDING_TOLERANCE


def total_counts(path):
    # Top-level <counter> elements are the report-wide totals across all packages.
    root = ET.parse(path).getroot()
    totals = {}
    for c in root.findall("counter"):
        if c.get("type") in METRICS:
            covered, missed = int(c.get("covered")), int(c.get("missed"))
            if covered + missed > 0:
                totals[c.get("type")] = (covered, missed)
    return totals


def method_misses(path):
    """(class, method, line) -> {metric: missed} for methods missing anything."""
    root = ET.parse(path).getroot()
    out = {}
    for pkg in root.iter("package"):
        for cls in pkg.iter("class"):
            cname = cls.get("name", "?").split("/")[-1]
            for meth in cls.iter("method"):
                key = (cname, meth.get("name", "?"), meth.get("line", "?"))
                missed = {}
                for c in meth.iter("counter"):
                    if c.get("type") in METRICS and int(c.get("missed")) > 0:
                        missed[c.get("type")] = int(c.get("missed"))
                if missed:
                    out[key] = missed
    return out


def print_regression_detail(report, baseline):
    """Method-level diff: where the PR is less covered than the baseline was.

    Keys are (class, method, declaration line); a method that merely moved
    shows as removed+added, which is noisy but still points at the right
    code. Sorted worst-first so the top row is the first thing to test.
    """
    current, base = method_misses(report), method_misses(baseline)
    worse = []
    for key, missed in current.items():
        base_missed = base.get(key, {})
        delta = {t: n - base_missed.get(t, 0) for t, n in missed.items() if n > base_missed.get(t, 0)}
        if delta:
            worse.append((key, missed, delta))
    if not worse:
        print(
            "  (no single method got worse — the drop comes from covered code shrinking\n"
            "   relative to new untested lines; see the uploaded coverage-report artifact)"
        )
        return
    worse.sort(key=lambda w: (-max(w[2].values()), w[0]))
    print(f"  Methods less covered than baseline (worst first, max {MAX_DIFF_ROWS}):")
    for (cls, meth, line), missed, delta in worse[:MAX_DIFF_ROWS]:
        parts = ", ".join(
            f"{t.lower()} missed {missed[t]} (+{delta[t]})" for t in METRICS if t in delta
        )
        print(f"    {cls}.{meth} (line {line}): {parts}")
    if len(worse) > MAX_DIFF_ROWS:
        print(f"    … and {len(worse) - MAX_DIFF_ROWS} more")


def main():
    label, report = sys.argv[1], sys.argv[2]
    baseline = sys.argv[3] if len(sys.argv) > 3 else ""

    current = total_counts(report)
    if not baseline:
        for t in METRICS:
            cov, mis = current.get(t, (0, 0))
            pct = cov / (cov + mis) if cov + mis else 0
            print(f"{label} {t}: {pct:.3%} ({cov}/{cov + mis})  (no baseline)")
        return 0

    base = total_counts(baseline)
    failed = []
    for t in METRICS:
        b_cov, b_mis = base.get(t, (0, 0))
        c_cov, c_mis = current.get(t, (0, 0))
        b = b_cov / (b_cov + b_mis) if b_cov + b_mis else 0
        c = c_cov / (c_cov + c_mis) if c_cov + c_mis else 0
        drop = allowed_drop(b)
        ok = c > b - drop if b > HIGH_COVERAGE_THRESHOLD else c >= b - drop
        print(
            f"{label} {t}: {c:.3%} ({c_cov}/{c_cov + c_mis}) vs baseline "
            f"{b:.3%} ({b_cov}/{b_cov + b_mis})  {'OK' if ok else 'REGRESSION'}"
        )
        if not ok:
            # Smallest n with (c_cov + n) / total >= floor.
            total = c_cov + c_mis
            floor = b - drop
            need = max(1, math.ceil(floor * total) - c_cov)
            unit = "branches" if t == "BRANCH" else "instructions"
            print(f"  -> cover at least {need} more {unit} to pass")
            failed.append(t)

    if failed:
        print_regression_detail(report, baseline)
    return 1 if failed else 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as e:  # a missing/corrupt report must not read as a regression
        print(f"ERROR: coverage comparison could not run: {e}", file=sys.stderr)
        sys.exit(2)
