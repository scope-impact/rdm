"""
The record-state port (DI-83): every question RDM asks about the state of the
record in version control, in the record's terms. One provider answers it:
the ``git`` command (``rdm.kernel.git``), or the record-state provider
composed into the component. The provider is chosen once, when RDM starts,
never under a running command.

A question no provider can answer is unknown (``None``): the caller says so
and never treats it as approved.
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
from pathlib import Path
from typing import Callable, Protocol


class Tracking(Enum):
    """How version control holds a file."""

    TRACKED = "tracked"
    UNTRACKED = "untracked"
    IGNORED = "ignored"
    SKIP_WORKTREE = "skip-worktree"  # edits hidden from status by the user
    ASSUME_UNCHANGED = "assume-unchanged"


@dataclass(frozen=True)
class FileState:
    path: str  # relative to the repository root, POSIX
    tracking: Tracking
    staged: bool  # the index differs from the last commit
    modified: bool  # the working tree differs from the index

    @property
    def changed(self) -> bool:
        return self.staged or self.modified or self.tracking is not Tracking.TRACKED


@dataclass(frozen=True)
class HeadState:
    commit: str | None  # None before the first commit
    origin: str | None  # the remote ``origin``'s URL as configured
    dirty: bool  # the working tree has uncommitted changes
    merging: bool  # a merge is being made


@dataclass(frozen=True)
class Commit:
    id: str
    author: str
    time: str  # strict ISO 8601 with the offset, as git's %aI
    subject: str


@dataclass(frozen=True)
class Reference:
    target: str
    symbolic: str | None  # the reference it names, for a symbolic one


class RecordState(Protocol):
    """What one provider answers about one repository."""

    root: Path

    def head(self) -> HeadState | None:
        """The checked-out state, or None when it cannot be known."""

    def files(self, paths: list[Path]) -> list[FileState] | None:
        """The state of each file named, or under each directory named; None
        when it cannot be known."""

    def staged_in_history(self, path: Path) -> bool:
        """Whether some commit holds, at this path, exactly what is staged."""

    def latest_commits(self, paths: list[str]) -> dict[str, str]:
        """Each path's latest commit; a path no commit touched is left out."""

    def commit(self, revision: str) -> Commit | None: ...

    def resolve(self, name: str) -> Reference | None:
        """A full reference name (refs/heads/main), resolved."""

    def branches(self) -> list[str]:
        """The local branches, by short name."""

    def config(self, key: str) -> str | None: ...

    def first_parents(self, revision: str) -> list[str]:
        """The first-parent line of ``revision``, newest first."""

    def ancestry_path(self, ancestor: str, revision: str) -> list[str]:
        """The commits on a path from ``ancestor`` to ``revision``, newest first."""


Provider = Callable[[Path], RecordState]
_provider: Provider | None = None


def use(provider: Provider) -> None:
    """Choose the provider, once, when RDM starts."""
    global _provider
    _provider = provider


def record_state(path: Path) -> RecordState | None:
    """The provider for the repository that contains ``path``, or None
    outside any repository: unknown, never approved."""
    from rdm.kernel.git import GitCommand, repo_root

    root = repo_root(path)
    if root is None:
        return None
    return (_provider or GitCommand)(root)
