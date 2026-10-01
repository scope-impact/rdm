"""
Git, as the record's controlled store: one way to ask it and to find it.
"""

from __future__ import annotations

import subprocess
from pathlib import Path


def git(where: Path, *args: str) -> str | None:
    """``git -C where args``: its stripped output, or None when git fails or
    is not installed."""
    try:
        out = subprocess.run(["git", "-C", str(where), *args], capture_output=True, text=True)
    except (OSError, ValueError):
        return None
    return out.stdout.strip() if out.returncode == 0 else None


def repo_root(path: Path) -> Path | None:
    """The git repository that contains ``path`` (``.git`` may be a directory
    or, in a worktree or submodule, a file), else None."""
    path = Path(path).resolve()
    for ancestor in [path, *path.parents]:
        if (ancestor / ".git").exists():
            return ancestor
    return None
