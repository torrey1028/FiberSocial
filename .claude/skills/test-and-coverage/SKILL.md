---
name: test-and-coverage
description: Run the FiberSocial test suites and pass the coverage gate, including recognizing the two known false-positive "regressions" and knowing when to skip tests. Use before pushing a PR, when CI's coverage gate fails, or when deciding whether a change needs tests at all.
---

# Test & coverage (FiberSocial)

Gradle root is `src/platform/android/` (NOT the repo root). All `./gradlew` runs from there.
For branch/PR mechanics see the fibersocial-git-workflow skill.

## Fast path

TRAP: **the three `test.sh` are mode 644 (not executable).** `./test.sh` fails
`Permission denied`, and `src/test.sh` internally runs `./common/test.sh` +
`./platform/android/test.sh` (same failure). Invoke them with `bash`, and do NOT
`chmod +x` (dirties the diff under `core.filemode=true`).

```bash
# from src/  — full suite (unit tests + coverage gate)
cd src
bash common/test.sh && bash platform/android/test.sh
```

Run this in the background (see fibersocial-build skill); a silent long foreground
run looks stuck. Do not poll it with `until`/`while` — wait for the completion
notification, then tail the log once.

## What each script runs

| Script | Runs | Working dir it cd's into |
|---|---|---|
| `platform/android/test.sh` | `./gradlew :composeApp:testDebugUnitTest :app:testDebugUnitTest` (Robolectric) | `src/platform/android/` |
| `common/test.sh` | `./gradlew :common:jvmCoverageReport :common:createDebugUnitTestCoverageReport` then the Python coverage gate | `src/platform/android/` |

`src/test.sh` just chains both (via `./`, so it hits the permission trap — use the
two-command form above instead).

### Targeted commands (from `src/platform/android/`)

```bash
./gradlew :common:jvmTest                                              # JVM unit tests only
./gradlew :common:iosSimulatorArm64Test :composeApp:iosSimulatorArm64Test   # iOS K/N sim tests
```

The report tasks depend on `jvmTest` / `testDebugUnitTest`, so running the coverage
scripts re-runs those tests and additionally emits Jacoco XML.

## The coverage gate

TWO independently-gated Jacoco reports — compare BOTH:

- JVM: `src/common/build/reports/jacoco/jvmCoverageReport/report.xml`
- Android/Robolectric: `src/common/build/reports/coverage/test/debug/report.xml`

`common/test.sh` invokes the same script CI uses:

```bash
# from repo root
python3 scripts/compare_coverage.py <LABEL> <report.xml> [baseline.xml]
```

- Metrics: `INSTRUCTION` + `BRANCH`, summed per-method. Methods named `write$Self*`
  are excluded (kotlinx.serialization K2 encoder codegen — dead for deserialize-only DTOs).
- Threshold `HIGH_COVERAGE_THRESHOLD = 0.85`: **below** it only a `0.001` rounding
  tolerance is allowed; **at/above** it a PR may regress by up to (not including)
  `0.01` (1 point).
- **Baseline = the artifact from the latest *successful* `main` run of `tests.yml`**
  (downloaded via `gh`), NOT current main's source. `common/test.sh` fetches it into
  `src/common/build/coverage-baseline/`; if `gh` is offline/unauthenticated it runs
  with no baseline (prints percentages, exit 0).
- **Pass/fail is decided on the report-wide AGGREGATE** `INSTRUCTION`/`BRANCH` ratio
  (total covered ÷ total, per metric) vs the baseline, within `allowed_drop`. The
  per-method `(class, method, line)` breakdown only feeds the *diagnostic printout*
  that fires **after** an aggregate regression is already detected — it never decides
  the gate. (This distinction is the key to false-positive #1 below.)

TRAP: **exit codes — `0` = ok / no baseline, `1` = regression, `2` = could-not-run
(missing/corrupt report). NEVER treat `2` as a coverage regression.** `common/test.sh`
already separates these (`SCRIPT_ERROR` vs `REGRESSED`); a `2` means fix the report,
not the tests.

## The two false-positive "regressions" (NOT real)

Before writing any test to "fix" a reported regression, rule out these two. #2 is
confirmed by checking the **baseline** (if the method misses coverage there too, it is
pre-existing debt, not your PR). #1 usually means your branch is **stale**, not that
you regressed anything.

1. **Line-number drift does NOT fail the gate — it only makes the detail printout
   lie.** A comment / KDoc-only diff changes zero bytecode, so it cannot move the
   aggregate `INSTRUCTION`/`BRANCH` totals and therefore **cannot fail the coverage
   gate on its own.** What line drift *does* do: the after-the-fact detail printout
   keys methods by `(class, method, declaration line)`, so a KDoc edit shifts a
   method's line, it stops key-matching its baseline entry, and it gets printed as a
   fresh 100%-regressed method — noise in the *explanation*, not the cause. So if a
   genuinely comment-only branch fails the gate, don't chase the printed method: the
   real cause is almost always that the branch is **behind the main-derived baseline**
   (it pulls in other uncovered code the baseline lacks), or the diff isn't actually
   comment-only. Fix by merging `origin/main` in (see fibersocial-git-workflow), not by
   adding a test.

2. **Dead synthetic Kotlin bridge constructors.** A class/data-class with a
   default-valued ctor param compiles a second `<init>` defaults-bridge that's only
   hit when a call site *omits* the arg. The kotlinx.serialization JSON path always
   passes every arg, so if nothing else omits it the bridge is genuinely dead and
   uncovered in **every** report. Confirm: it misses in the baseline too → pre-existing,
   not caused by your PR. Don't chase it.

## Policy — when (not) to run tests

- **Comment / KDoc-only change → skip local build/test entirely.** No compiled logic
  changed; commit, push, let CI check. Any actual-code change (even one line) gets a
  normal run.
- **Interactive on-device co-dev loop → do NOT run the full suite each iteration.**
  It blocks the loop while the user waits with the device. Just compile between
  iterations (`assembleDebug` or a targeted `compileDebugKotlin`); run the suite once
  at the end.
- **Deploy after: it compiles + `platform/android/test.sh` passes.** Run
  `common/test.sh` (the coverage gate) **before pushing the PR**, not before the
  walkthrough.
- **Don't pad tests to inflate a percentage.** Each gate-satisfying test must document
  a real invariant / default / edge case.

## Module-collision guard

CI runs this before JDK setup; run it locally if you added/moved a top-level type:

```bash
# from repo root
python3 scripts/check_module_collisions.py
```

Fails (exit 1) if one top-level type FQN is declared in more than one Gradle module
(`:common`, `:composeApp`, `:app`) — such duplicates compile cleanly but ship two
copies into the one APK and the dex merge keeps an arbitrary one (runtime
`NoSuchMethodError`). Exit `2` = a configured source root moved (update the script).
KMP `expect`/`actual` pairs live entirely in `:common`, so they are never flagged.
