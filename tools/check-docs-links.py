#!/usr/bin/env python3
"""Check that every relative link and image in the knowledge base resolves.

The knowledge base publishes to the repo wiki, where a relative link is rewritten
to either a wiki page or a `blob/HEAD` repo URL. Neither rewrite can tell that its
target has been renamed or removed: the published link 404s and GitHub does not
warn. Several documents deliberately link OUT to files that are never synced -
AGENTS.md, WORKFLOW.md, docs/README.md - so renaming one of those breaks the
published wiki without touching anything under docs/knowledge/.

Run with no arguments, or pass one or more roots to check instead.
"""

from __future__ import annotations

import re
import sys
from pathlib import Path

DEFAULT_ROOTS = ("docs/knowledge",)
SKIP_DIRS = {".gh-wiki-sync", ".yt-sync"}
EXTERNAL = ("http://", "https://", "mailto:", "tel:")

# [text](target) and ![alt](target), with an optional <> wrapper and #anchor / ?query.
INLINE = re.compile(r"!?\[(?P<text>[^\]]*)\]\(\s*(?P<target><[^>]*>|[^\s)]+)")
# [label]: target  - a reference definition at the start of a line.
REFDEF = re.compile(r"^\s{0,3}\[(?P<label>[^\]]+)\]:\s*(?P<target><[^>]*>|\S+)")
FENCE = re.compile(r"^\s{0,3}(```+|~~~+)")


def targets(path: Path) -> list[tuple[int, str, str]]:
    """Yield (line number, link text, target) for one file, skipping fenced code."""
    found: list[tuple[int, str, str]] = []
    fence: str | None = None
    for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
        marker = FENCE.match(line)
        if marker:
            token = marker.group(1)
            if fence is None:
                fence = token[0]
                continue
            if token[0] == fence:
                fence = None
            continue
        if fence is not None:
            continue
        line = re.sub(r"`[^`]*`", "", line)  # inline code is an example, not a link
        for match in INLINE.finditer(line):
            found.append((number, match.group("text"), match.group("target")))
        definition = REFDEF.match(line)
        if definition:
            found.append((number, definition.group("label"), definition.group("target")))
    return found


def check(roots: tuple[str, ...]) -> int:
    broken: list[str] = []
    checked = 0
    files = 0

    for name in roots:
        root = Path(name)
        if not root.is_dir():
            print(f"check-docs-links: no such directory: {root}", file=sys.stderr)
            return 1
        for path in sorted(root.rglob("*.md")):
            if SKIP_DIRS & set(path.parts):
                continue
            files += 1
            for number, text, raw in targets(path):
                target = raw.strip()
                if target.startswith("<") and target.endswith(">"):
                    target = target[1:-1].strip()
                if not target or target.startswith("#"):
                    continue
                if target.lower().startswith(EXTERNAL):
                    continue
                bare = target.split("#", 1)[0].split("?", 1)[0]
                if not bare:
                    continue
                checked += 1
                if not (path.parent / bare).resolve().exists():
                    broken.append(f"  {path}:{number}  [{text}]({target})")

    print(f"check-docs-links: {checked} relative links in {files} files")
    if broken:
        print(f"\n{len(broken)} link(s) resolve to nothing:\n")
        print("\n".join(broken))
        print(
            "\nThese publish to the wiki as links that 404 silently. Fix the path, or\n"
            "restore the target - a file outside docs/knowledge/ (AGENTS.md,\n"
            "WORKFLOW.md, docs/README.md) is still a link target even though it is\n"
            "never synced as a page."
        )
        return 1

    print("check-docs-links: all resolve")
    return 0


if __name__ == "__main__":
    sys.exit(check(tuple(sys.argv[1:]) or DEFAULT_ROOTS))
