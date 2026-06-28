# FiberSocial — Project Instructions

## Git Workflow

- **Never push directly to `main`.** All changes — including documentation, formatting, or one-line fixes — must go through a feature branch and a pull request.
- **Never merge a PR yourself.** After creating or updating a PR, hand the URL to the user and wait. Never run `gh pr merge` or any equivalent.
- Branch naming: `feat/`, `fix/`, `chore/`, `docs/` prefixes matching the commit type.

## Secrets

- Never commit `ravelry.client_secret`. It lives only in `local.properties` (gitignored) as `ravelry.client_secret`.

## Logging

- Use `println("FiberSocial: ...")` for debug logging in common module code (visible in logcat as `System.out` tag).
- Rely on logcat for debugging — do not use screenshots as a substitute.
