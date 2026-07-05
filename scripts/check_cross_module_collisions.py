#!/usr/bin/env python3
"""Fail if a top-level Kotlin type is declared in BOTH the shared `:common`
source sets and the consuming Android `:app` module under the same
fully-qualified name.

Why this exists (see issue #155/#156): because `:app` depends on `:common`,
a type declared with the identical package + name in both modules compiles to
one binary name and the two definitions silently fight over which copy the dex
merge keeps — no compile error, no `checkDebugDuplicateClasses` failure (that
task targets external dependency jars, not sibling Gradle modules), just a
runtime `NoSuchMethodError` when the "wrong" copy is loaded. #155 was exactly
this (`ParagraphSegment.Photo`). This guard catches the pattern before it ships.

It is a source-level heuristic, deliberately simple: it matches top-level type
declarations (anchored at column 0, so nested types are ignored) and the file's
`package` line. `internal` doesn't help — it mangles member names, not the class
name, so an `internal` type still collides.

Usage: check_cross_module_collisions.py [REPO_ROOT]
  REPO_ROOT defaults to the repository root inferred from this script's location.

Exit codes: 0 = no collisions, 1 = collision(s) found, 2 = misconfigured
(a scan root is missing — likely a moved directory this script needs updating for).
"""
import os
import re
import sys

# Shared source sets that `:app` links against (its androidTarget artifact =
# commonMain + androidMain). jvmMain is NOT consumed by `:app`, so it's excluded.
SHARED_ROOTS = (
    "src/common/src/commonMain/kotlin",
    "src/common/src/androidMain/kotlin",
)
# The consuming module. Only main sources merge into the app runtime; test
# sources don't ship, so they can't cause the runtime collision.
CONSUMER_ROOTS = ("src/platform/android/app/src/main/kotlin",)

PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)")
# A top-level type declaration: at column 0 (no leading whitespace, so nested
# declarations are skipped), optional modifiers, then a type keyword and name.
_MODIFIERS = (
    "public|private|internal|protected|expect|actual|sealed|abstract|open|"
    "final|data|enum|value|annotation|inline|external"
)
TYPE_RE = re.compile(
    r"^(?:(?:" + _MODIFIERS + r")\s+)*"
    r"(?:class|interface|object|typealias)\s+"
    r"([A-Za-z_][A-Za-z0-9_]*)"
)


def top_level_types(root):
    """Map fully-qualified type name -> "path:line" for every top-level type
    declared under `root`."""
    found = {}
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            package = ""
            with open(path, encoding="utf-8") as fh:
                for lineno, line in enumerate(fh, start=1):
                    pkg = PACKAGE_RE.match(line)
                    if pkg:
                        package = pkg.group(1)
                        continue
                    m = TYPE_RE.match(line)
                    if m:
                        fqn = f"{package}.{m.group(1)}" if package else m.group(1)
                        # First declaration wins for reporting; collisions across
                        # files in the same module aren't this guard's concern.
                        found.setdefault(fqn, f"{path}:{lineno}")
    return found


def main(argv):
    repo_root = argv[1] if len(argv) > 1 else os.path.abspath(
        os.path.join(os.path.dirname(__file__), "..")
    )

    def collect(roots, label):
        merged = {}
        scanned = 0
        for rel in roots:
            root = os.path.join(repo_root, rel)
            if not os.path.isdir(root):
                # A single absent source set (e.g. a module with no androidMain
                # Kotlin) is fine — it just contributes no types. Skip it.
                print(f"note: {label} source root absent, skipping: {rel}", file=sys.stderr)
                continue
            scanned += 1
            for fqn, loc in top_level_types(root).items():
                merged.setdefault(fqn, loc)
        if scanned == 0:
            # Every root for this side is gone — almost certainly a moved module,
            # which would make the guard silently pass. Fail loudly instead.
            print(f"ERROR: no {label} source roots found — did a module move? "
                  f"update SHARED_ROOTS/CONSUMER_ROOTS.", file=sys.stderr)
            sys.exit(2)
        return merged

    shared = collect(SHARED_ROOTS, "shared")
    consumer = collect(CONSUMER_ROOTS, "consumer")

    collisions = sorted(set(shared) & set(consumer))
    if not collisions:
        print(
            f"OK: no cross-module type collisions "
            f"({len(shared)} shared types checked against {len(consumer)} app types)."
        )
        return 0

    print("Cross-module class-name collision(s) found — these compile to one")
    print("binary name and collide at dex-merge (runtime NoSuchMethodError risk):")
    print()
    for fqn in collisions:
        print(f"  {fqn}")
        print(f"    shared:   {shared[fqn]}")
        print(f"    :app:     {consumer[fqn]}")
    print()
    print("Fix: keep a single declaration (usually in :common) and delete/rename the other.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
