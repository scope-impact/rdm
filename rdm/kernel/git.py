"""
Git, as the record's controlled store: where a repository is, and the
``git`` command as the record-state provider of the command line (DI-83).
Only this module runs ``git``.
"""

from __future__ import annotations

import os
import re
import subprocess
from functools import cached_property
from pathlib import Path

from rdm.kernel.record_state import Commit, FileState, HeadState, Reference, Tracking


def git(where: Path, *args: str, strip: bool = True) -> str | None:
    """``git -C where args``: its output (stripped unless told not to, for
    output whose leading spaces mean something), or None when git fails or
    is not installed."""
    try:
        out = subprocess.run(["git", "-C", str(where), *args], capture_output=True, text=True)
    except (OSError, ValueError):
        return None
    if out.returncode != 0:
        return None
    return out.stdout.strip() if strip else out.stdout


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


_LISTED = {"H": Tracking.TRACKED, "S": Tracking.SKIP_WORKTREE}  # ls-files -v; lowercase: assume-unchanged


class GitCommand:
    """The record-state provider that asks the ``git`` command."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def _git(self, *args: str) -> str | None:
        return git(self.root, *args)

    def _rel(self, path: Path) -> str:
        return Path(path).resolve().relative_to(self.root).as_posix()

    @cached_property
    def _head(self) -> HeadState | None:
        status = self._git("status", "--porcelain")
        if status is None:
            return None
        merging = bool(self._git("rev-parse", "-q", "--verify", "MERGE_HEAD")) or \
            os.environ.get("GIT_REFLOG_ACTION", "").startswith("merge")
        return HeadState(self._git("rev-parse", "HEAD"), self._git("remote", "get-url", "origin"),
                         bool(status), merging)

    def head(self) -> HeadState | None:
        return self._head

    def files(self, paths: list[Path]) -> list[FileState] | None:
        rel = [self._rel(p) for p in paths]
        status = git(self.root, "status", "--porcelain", "--ignored", "--", *rel, strip=False)
        listed = self._git("ls-files", "-v", "--", *rel)
        if status is None or listed is None:
            return None
        states: dict[str, FileState] = {}
        for line in listed.splitlines():
            tag, path = line[0], line[2:]
            tracking = _LISTED.get(tag, Tracking.ASSUME_UNCHANGED if tag.islower() else Tracking.TRACKED)
            states[path] = FileState(path, tracking, False, False)
        for line in status.splitlines():
            xy, path = line[:2], line[3:].split(" -> ")[-1]
            if xy == "??":
                states[path] = FileState(path, Tracking.UNTRACKED, False, False)
            elif xy == "!!":
                states[path] = FileState(path, Tracking.IGNORED, False, False)
            else:
                known = states.get(path)
                tracking = known.tracking if known else Tracking.TRACKED
                states[path] = FileState(path, tracking, xy[0] != " ", xy[1] != " ")
        return [states[p] for p in sorted(states)]

    def staged_in_history(self, path: Path) -> bool:
        rel = self._rel(path)
        blob = self._git("rev-parse", "-q", "--verify", f":{rel}")
        return bool(blob) and bool(self._git("log", "--all", "-n1", "--format=%H", f"--find-object={blob}", "--", rel))

    def latest_commits(self, paths: list[str]) -> dict[str, str]:
        latest: dict[str, str] = {}
        wanted, sha = set(paths), None
        for line in (self._git("log", "--format=%x00%H", "--name-only", "--", *paths) or "").splitlines():
            if line.startswith("\x00"):
                sha = line[1:]
            elif line in wanted and sha:
                latest.setdefault(line, sha)
        return latest

    def commit(self, revision: str) -> Commit | None:
        out = self._git("log", "-1", "--format=%H%x1f%an%x1f%aI%x1f%s", revision)
        if not out:
            return None
        sha, author, when, subject = out.split("\x1f", 3)
        # Recent git writes a UTC time as "Z", older git as "+00:00": one form, the offset, on every provider.
        return Commit(sha, author, when[:-1] + "+00:00" if when.endswith("Z") else when, subject)

    def resolve(self, name: str) -> Reference | None:
        target = self._git("rev-parse", "--verify", "--quiet", name)
        return Reference(target, self._git("symbolic-ref", "-q", name)) if target else None

    def branches(self) -> list[str]:
        return (self._git("for-each-ref", "--format=%(refname:short)", "refs/heads") or "").split()

    def config(self, key: str) -> str | None:
        return self._git("config", key)

    def first_parents(self, revision: str) -> list[str]:
        return (self._git("rev-list", "--first-parent", revision) or "").split()

    def ancestry_path(self, ancestor: str, revision: str) -> list[str]:
        return (self._git("rev-list", "--ancestry-path", f"{ancestor}..{revision}") or "").split()
