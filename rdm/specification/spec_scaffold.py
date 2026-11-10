"""Lay down a spec skeleton the Löyly way (item-01-002, item-01-003).

``.pi/spec/core/`` holds one context: ``spec.feature`` (the accepted, stamped
scenarios: the requirements; empty at first), ``drafts.feature`` (not read),
``allium/`` (a surface per user need) and ``event-model/area.edn`` (a screen
per user need, linked to its surface); ``.pi/spec/event-storm.edn`` is the
board; ``.pi/plan/01-core`` links slice 01 to the context, as Löyly's plan
does. RDM reads it through its Löyly adapter.
"""

from __future__ import annotations

import os
import shutil
from importlib.resources import as_file, files
from pathlib import Path

PLAN_LINK = ".pi/plan/01-core"


def has_spec(root: Path) -> bool:
    """Whether ``root`` already holds a spec RDM would read: a plan, or
    Gherkin under ``spec/`` or ``features/``."""
    if (root / ".pi" / "plan").is_dir():
        return True
    return any((root / name).is_dir() and any((root / name).rglob("*.feature")) for name in ("spec", "features"))


def lay_spec(root: Path) -> tuple[list[str], list[str]]:
    """Lay the skeleton into ``root``, skipping (never overwriting) what
    exists; return (laid, skipped) as paths relative to ``root``."""
    laid: list[str] = []
    skipped: list[str] = []
    with as_file(files("rdm.specification") / "spec_files") as template:
        template = Path(template)
        for src in sorted(template.rglob("*")):
            if src.is_dir():
                continue
            rel = ".pi/" + src.relative_to(template).as_posix()
            dest = root / rel
            if dest.exists() or dest.is_symlink():
                skipped.append(rel)
                continue
            dest.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(src, dest)
            laid.append(rel)
    link = root / PLAN_LINK
    if link.exists() or link.is_symlink():
        skipped.append(PLAN_LINK)
    else:
        link.parent.mkdir(parents=True, exist_ok=True)
        os.symlink(Path("..") / "spec" / "core", link, target_is_directory=True)
        laid.append(PLAN_LINK)
    return laid, skipped
