#!/usr/bin/env python3
"""Fail the build on a cross-module top-level type-name collision in the Android APK.

Root cause of the bug this guards against (issue #155): a top-level type declared
with the *same* fully-qualified name in both the `:common` module and the `:app`
module compiles cleanly — each Gradle module is compiled in isolation, and Kotlin's
`internal` is a frontend-only rule that doesn't namespace the emitted `.class`. But
because `:app` depends on `:common`, both copies land in one APK, the dex merge
trusts each producer to be internally non-duplicate, and Android's classloader
resolves the name to whichever dex shard happens to come first. When the copies have
diverged (e.g. one has an extra constructor arg), the "wrong" one wins and the app
throws `NoSuchMethodError`/`ClassCastException` at runtime — with no compile error and
no `checkDebugDuplicateClasses` failure (that check targets external dependency jars,
not sibling source modules). See #156.

This script catches it before merge: it enumerates every top-level type
(`class`/`interface`/`object`/`enum class`/`typealias`) declared in each module's
Android-merged source and fails if any fully-qualified name is declared in
more than one module (`:common`, `:composeApp`, `:app`).

Only cross-*module* collisions are flagged, so a KMP `expect`/`actual` pair (which
lives entirely inside `:common`, in `commonMain` + `androidMain`) is never a false
positive. Nested types don't need their own scan: a nested type can only collide if
its enclosing top-level type does, which this already catches.

Usage: check_module_collisions.py [REPO_ROOT]   (defaults to this file's repo root)

Exit codes: 0 = no collisions, 1 = collision(s) found, 2 = could not run
(a configured source root is missing — a repo-layout change this script must be
updated for, not silently passed).
"""
import os
import re
import sys
from collections import defaultdict

# Source roots that all compile into the one debug APK, grouped by Gradle module. A
# top-level type FQN appearing in two *different* modules here is a dex collision.
# `:common`'s jvmMain (the jvm() target) and any *Test / future ios/native sets don't
# ship in the APK, so they're intentionally excluded.
MODULE_ROOTS = {
    ":common": [
        "src/common/src/commonMain/kotlin",
        "src/common/src/androidMain/kotlin",
    ],
    ":composeApp": [
        "src/compose/src/commonMain/kotlin",
        "src/compose/src/androidMain/kotlin",
    ],
    ":app": [
        "src/platform/android/app/src/main/kotlin",
    ],
}

PACKAGE_RE = re.compile(r"^\s*package\s+([A-Za-z0-9_.]+)")
# A top-level type declaration. Leading soft/hard modifiers are optional and unordered;
# we only need to capture the declared name. `fun`/`val`/`const` are deliberately not
# matched: top-level callables compile into a file-named `…Kt` facade class, so they
# can only collide when two files share a name — not the failure mode here.
TYPE_RE = re.compile(
    r"^\s*(?:(?:public|internal|private|protected|abstract|sealed|open|final|data|"
    r"value|enum|annotation|inner|expect|actual|external)\s+)*"
    r"(?:class|interface|object|typealias)\s+([A-Za-z_][A-Za-z0-9_]*)"
)

# Stripped from file content before brace-depth tracking below: an unbalanced brace
# inside a block comment or raw string (e.g. a KDoc example showing a JSON literal
# split across lines, which this codebase's docs and fixtures use heavily) would
# otherwise push `depth` permanently above 0, silently hiding every top-level type
# declared later in that same file — the opposite of what this guard is for.
BLOCK_COMMENT_RE = re.compile(r"/\*.*?\*/", re.DOTALL)
TRIPLE_QUOTED_STRING_RE = re.compile(r'""".*?"""', re.DOTALL)
LINE_COMMENT_RE = re.compile(r"//.*$")


def declared_types(root):
    """Yield (fqn, relpath) for every top-level type declared under `root`."""
    for dirpath, _dirs, files in os.walk(root):
        for name in files:
            if not name.endswith(".kt"):
                continue
            path = os.path.join(dirpath, name)
            with open(path, encoding="utf-8") as handle:
                content = handle.read()
            content = BLOCK_COMMENT_RE.sub("", content)
            content = TRIPLE_QUOTED_STRING_RE.sub("", content)
            package = ""
            depth = 0  # brace depth; only depth 0 is a top-level declaration
            for line in content.splitlines():
                line = LINE_COMMENT_RE.sub("", line)
                pkg = PACKAGE_RE.match(line)
                if pkg:
                    package = pkg.group(1)
                if depth == 0:
                    type_match = TYPE_RE.match(line)
                    if type_match:
                        fqn = f"{package}.{type_match.group(1)}" if package else type_match.group(1)
                        yield fqn, path
                depth += line.count("{") - line.count("}")


def main(argv):
    repo_root = argv[1] if len(argv) > 1 else os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

    # fqn -> {module -> [paths]}
    seen = defaultdict(lambda: defaultdict(list))
    for module, roots in MODULE_ROOTS.items():
        for rel_root in roots:
            root = os.path.join(repo_root, rel_root)
            if not os.path.isdir(root):
                print(f"ERROR: source root not found: {rel_root}\n"
                      f"  The repo layout changed; update MODULE_ROOTS in {os.path.basename(__file__)}.",
                      file=sys.stderr)
                return 2
            for fqn, path in declared_types(root):
                seen[fqn][module].append(os.path.relpath(path, repo_root))

    collisions = {fqn: mods for fqn, mods in seen.items() if len(mods) > 1}

    total = len(seen)
    if not collisions:
        print(f"OK: {total} top-level type name(s) scanned across "
              f"{', '.join(MODULE_ROOTS)} — no cross-module collisions.")
        return 0

    print(f"FAIL: {len(collisions)} cross-module type-name collision(s) found "
          f"(same fully-qualified name declared in more than one module).\n")
    for fqn, mods in sorted(collisions.items()):
        print(f"  {fqn}")
        for module, paths in sorted(mods.items()):
            for path in paths:
                print(f"      [{module}] {path}")
    print("\nEach collision ships two copies of one class in the APK; the dex merge keeps\n"
          "an arbitrary one, risking a runtime NoSuchMethodError (see #155/#156). Remove\n"
          "the duplicate — keep a single declaration and have the other module depend on it.")
    return 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
