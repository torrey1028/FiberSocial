---
name: fibersocial-git-workflow
description: The mandatory git, worktree, and PR conventions for every change in the FiberSocial repo. Use for any code, doc, or config change — starting new work, updating an open PR, reviewing a PR, deciding when a PR is done, writing commit/PR text, or picking the next backlog issue.
---

# FiberSocial git & PR workflow

This is the single source of truth for how changes ship here. Other skills defer to it. Read the rules; they are not optional even for a one-line fix.

## The four hard rules (never break)

1. **Never push to `main`.** Every change — even a one-line doc or config fix — goes through a feature branch + PR.
2. **Never edit the primary checkout in place.** Always cut a fresh worktree first (below).
3. **Never merge a PR yourself.** `gh pr merge` (and any equivalent) is forbidden. Hand the PR URL to the user and wait.
4. **Never commit `ravelry.client_secret`, and never `Read`/`cat`/`echo` `local.properties`** — it holds a real secret that would leak into the transcript. See the build-and-run skill for the redacted-inspection pattern.

## Start every change in a fresh worktree

From the repo root (`/home/betorr/FiberSocial`):

```bash
git worktree add ../FiberSocial-<slug> -b <type>/<slug>
```

- Branch prefix = commit type: `feat/`, `fix/`, `chore/`, `docs/`.
- **TRAP: the primary checkout routinely holds other sessions' uncommitted WIP.** Editing in place entangles their changes into your diff — you cannot cut a clean single-purpose PR, and you may revert their work by accident. Always work in the new worktree.
- **TRAP: a fresh worktree does NOT get `local.properties` or the release keystore** (both gitignored). Copy them in before building or OAuth fails `invalid_client`. See the build-and-run skill for exactly what to copy and from where.

## When the user says to move on to a new task

Don't leave the current worktree stranded while spinning up yet another one next to
it — worktrees accumulate fast and are easy to lose track of. Before starting the
next task, either:

- **Remove it**, once its PR is opened and there's no reason to keep it around:
  confirm it's clean (`git status --short`), `cd` back to the primary checkout, then
  `git worktree remove ../FiberSocial-<slug>`. Confirm with `git worktree list`.
- **Repurpose it**, if the new task is a direct continuation of the same branch/PR
  (addressing review comments, a same-PR follow-up) — reuse the existing worktree
  instead of adding another.

Run `git worktree list` periodically to catch orphans. It will also show worktrees
from other sessions — leave those alone, but flag anything that looks like a
duplicate of work you're about to do (e.g. a branch name close to yours) before it
turns into a merge conflict with your own PR.

## Update an open PR after `main` moved

```bash
# from inside the PR's worktree
git fetch origin
git merge origin/main   # resolve any conflicts
git push                # plain push, NO --force
```

- **TRAP: do NOT rebase + force-push.** The user squash-merges every PR, so linear history is irrelevant, and a merge never rewrites history. A force-push once clobbered a just-merged sibling PR. Only rebase if the user explicitly asks.

## Reviewing a PR

**TRAP: `git merge origin/main` into the PR's worktree FIRST, before you diff or run any finders.** Otherwise commits that already landed on `main` show up in the diff as phantom "reverts" and you will chase ghosts.

For the full review loop (reviewer comments, multi-angle review, applying fixes) see the `review-all-prs` skill.

## When is a PR "done enough to move on"?

Done = the **fast** checks are green **and** the PR is mergeable. The fast checks (all on Ubuntu):

- **Common module JVM tests + coverage** (`common-jvm-tests`, `tests.yml`)
- **Common module Android unit tests (Robolectric) + coverage** (`common-android-unit-tests`, `tests.yml`)
- **build** — the `Android Build` workflow's `build` job (`android-build.yml`): `assembleDebug` + app unit tests

Do **not** sit and wait on the slow macOS job **Common module iOS (Kotlin/Native) tests** (`common-ios-tests`, ~6 min).

- **CORRECTION to older memory:** that Kotlin/Native job DOES run on PRs and CAN fail the PR — it is not skipped. You just don't *block* on it before moving to the next task; if it later goes red the user pings you.
- The truly PR-skipped job is the main-only **iOS app build + hosted XCTests** (`ios-app-build`, gated `if: github.event_name == 'push' && github.ref == 'refs/heads/main'`). It never runs on a PR.
- **EXCEPTION:** if the PR touches `iosMain` or any Swift, the iOS jobs ARE the gate — wait for `common-ios-tests` (and expect `ios-app-build` to be your real signal only after merge to main).

For diagnosing a red check, see the `fix-ci` skill.

## Push early, verify in parallel

After a clean merge/rebase (no conflicts), push immediately and let CI run **alongside** your local verification — don't serialize them.

## Commit and PR text

End every **commit message** with these two trailer lines:

```
Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_<id>
```

End every **PR body** with:

```
🤖 Generated with [Claude Code](https://claude.com/claude-code)

https://claude.ai/code/session_<id>
```

(The concrete session id/URL is supplied in your environment's git-instructions — use those exact strings.)

## `gh` in scripts

Always pass the repo explicitly — a multi-remote clone otherwise infers the wrong repo:

```bash
gh <cmd> --repo torrey1028/FiberSocial ...
```

## "keep going" / autonomous mode

"keep going" means: pick the next backlog issue and take it end-to-end (worktree → build → deploy → tests → PR) **without asking which one**. Prefer user-facing bugs, then polish. Re-scan the tracker newest-first between features:

```bash
gh issue list --repo torrey1028/FiberSocial
```

For batch issue-fixing see the `fix-issues` skill; for shipping a single change end-to-end see the `write-pr` skill.
