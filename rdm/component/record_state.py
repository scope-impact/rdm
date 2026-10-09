"""The record-state port answered by the ``rdm:component/record-state`` import
(the gitoxide provider composed into the component)."""

from __future__ import annotations

import os
from pathlib import Path

from wit_world.imports import record_state as wit

from rdm.kernel.record_state import Commit, FileState, HeadState, Reference, Tracking

_TRACKING = {
    wit.Tracking.TRACKED: Tracking.TRACKED,
    wit.Tracking.UNTRACKED: Tracking.UNTRACKED,
    wit.Tracking.IGNORED: Tracking.IGNORED,
    wit.Tracking.SKIP_WORKTREE: Tracking.SKIP_WORKTREE,
    wit.Tracking.ASSUME_UNCHANGED: Tracking.ASSUME_UNCHANGED,
}


class WitRecordState:
    """One repository: the one the host mounted, which the provider reads."""

    def __init__(self, root: Path) -> None:
        self.root = root

    def _rel(self, path: Path) -> str:
        return os.path.relpath(os.path.abspath(path), self.root).replace(os.sep, "/")

    def head(self) -> HeadState | None:
        head = wit.head()
        return None if head is None else HeadState(head.commit, head.origin, head.dirty, head.merging)

    def files(self, paths: list[Path]) -> list[FileState] | None:
        states = wit.files([self._rel(p) for p in paths])
        if states is None:
            return None
        return [FileState(s.path, _TRACKING[s.tracking], s.staged, s.modified) for s in states]

    def staged_in_history(self, path: Path) -> bool:
        return wit.staged_in_history(self._rel(path))

    def latest_commits(self, paths: list[str]) -> dict[str, str]:
        return dict(wit.latest_commits(paths))

    def commit(self, revision: str) -> Commit | None:
        found = wit.commit(revision)
        return None if found is None else Commit(found.id, found.author, found.time, found.subject)

    def resolve(self, name: str) -> Reference | None:
        found = wit.resolve(name)
        return None if found is None else Reference(found.target, found.symbolic)

    def branches(self) -> list[str]:
        return list(wit.branches())

    def config(self, key: str) -> str | None:
        return wit.config(key)

    def first_parents(self, revision: str) -> list[str]:
        return list(wit.first_parents(revision))

    def ancestry_path(self, ancestor: str, revision: str) -> list[str]:
        return list(wit.ancestry_path(ancestor, revision))
