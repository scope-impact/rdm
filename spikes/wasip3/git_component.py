"""
Spike: RDM's git questions answered through the ``rdm:component/record-state``
import (rdm-git, gitoxide), so the host needs no git at all.

Each question is answered as ``git_snapshot.py`` answers it, from a snapshot
of just the paths it names, built from what record-state says of them: one
way to turn the record's state into git's words.
"""

from wit_world.imports import record_state

import git_snapshot

_STATUS = {  # what git status --porcelain --ignored writes for a file's state
    record_state.Tracking.UNTRACKED: "??",
    record_state.Tracking.IGNORED: "!!",
}
_LISTED = {  # git ls-files -v's tag; untracked and ignored files are not listed
    record_state.Tracking.TRACKED: "H",
    record_state.Tracking.SKIP_WORKTREE: "S",
    record_state.Tracking.ASSUME_UNCHANGED: "h",
}


def install() -> None:
    heads = []  # asked once, on the first question: a command that asks none never reads the repository

    def answering(where, args):
        head = heads[0] if heads else heads.append(record_state.head()) or heads[0]
        snapshot = {"head": head.commit, "origin": head.origin, "merging": head.merging, "status": [], "files": []}
        paths = git_snapshot.pathspec(where, args)
        if paths:
            for state in record_state.files(paths):
                if state.changed:
                    snapshot["status"].append(f"{_STATUS.get(state.tracking, ' M')} {state.path}")
                if state.tracking in _LISTED:
                    snapshot["files"].append(f"{_LISTED[state.tracking]} {state.path}")
        elif head.dirty:
            snapshot["status"].append("?? .")  # the tree has changes; which ones is not asked
        return git_snapshot.answer(snapshot, where, args)

    git_snapshot.install(answering)
