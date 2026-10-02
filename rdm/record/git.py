"""
Git, as the record's controlled store: one way to ask it and to find it.
"""

from __future__ import annotations

import re
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


def web_url(remote: str | None) -> str | None:
    """A browsable https URL for a GitHub, GitLab or similar remote."""
    if not remote:
        return None
    match = re.match(r"^(?:git@|ssh://git@)([^:/]+)[:/](.+?)(?:\.git)?/?$", remote) or \
        re.match(r"^https?://(?:[^@/]+@)?([^/]+)/(.+?)(?:\.git)?/?$", remote)
    return f"https://{match.group(1)}/{match.group(2)}" if match else None
