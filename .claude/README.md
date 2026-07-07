# FiberSocial agent skill library

Project-local skills and subagents that let any engineer — or a cheaper/smaller
model — build, debug, extend, test, and ship FiberSocial to the project's standard
without re-deriving the tribal knowledge each time. Every file here was authored
against the real code and adversarially verified; the guidance was A/B-validated
with Sonnet-class models (see the PR that introduced this directory).

## Start here

**`skills/fibersocial-orient`** is the entry point — the architecture map plus a
router to the right skill for a given task. When in doubt, read it first.

## Skills (`.claude/skills/`)

| Skill | Use it when you need to… |
|---|---|
| `fibersocial-orient` | get oriented: module layout, where things live, which skill to use |
| `build-and-run` | build/install/run on Android or iOS, set up a fresh worktree, debug via logcat |
| `test-and-coverage` | run the suites and pass the coverage gate (incl. its false positives) |
| `add-ravelry-endpoint` | add or change a Ravelry API call (JSON API or website scraping) |
| `feed-rendering` | work on post/topic parsing & rendering without breaking the preview invariant |
| `cut-release` | tag and ship a signed public release |
| `fibersocial-git-workflow` | the mandatory worktree + PR conventions (the source of truth) |

## Agents (`.claude/agents/`)

| Agent | Role |
|---|---|
| `kmp-builder` | builds/deploys/observes the app and reports evidence (build output, logcat) |
| `ravelry-api-dev` | implements Ravelry API calls end-to-end following the central-client pattern |
| `kmp-code-reviewer` | reviews a diff for this project's specific, high-cost failure modes |
| `kmp-test-author` | writes/extends unit tests to pass the coverage gate; diagnoses gate false positives |

These are living documents — when a convention changes, update the relevant skill so
the next session inherits the correction.
