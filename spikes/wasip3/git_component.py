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


_COMMIT_FORMAT = "--format=%H%x1f%an%x1f%aI%x1f%s"


def _history(args: list[str]) -> tuple[bool, str | None]:
    """The graph's history questions (rdm.graph.project), answered from record-state as git writes them.
    The first value says whether the question was one of them."""
    if args[:3] == ["rev-parse", "--abbrev-ref", "origin/HEAD"]:
        ref = record_state.resolve("refs/remotes/origin/HEAD")
        if ref is None:
            return True, None
        return True, ref.symbolic.removeprefix("refs/remotes/") if ref.symbolic else "origin/HEAD"
    if args[:3] == ["rev-parse", "--verify", "--quiet"] and len(args) == 4:
        ref = record_state.resolve(args[3])
        return True, ref.target if ref else None
    if args[:1] == ["config"] and len(args) == 2:
        return True, record_state.config(args[1])
    if args == ["for-each-ref", "--format=%(refname:short)", "refs/heads"]:
        return True, "\n".join(record_state.branches())
    if args[:3] == ["log", "-1", _COMMIT_FORMAT] and len(args) == 4:
        commit = record_state.commit(args[3])
        return True, None if commit is None else "\x1f".join((commit.id, commit.author, commit.time, commit.subject))
    if args[:2] == ["rev-list", "--first-parent"] and len(args) == 3:
        return True, "\n".join(record_state.first_parents(args[2]))
    if args[:2] == ["rev-list", "--ancestry-path"] and len(args) == 3 and ".." in args[2]:
        ancestor, revision = args[2].split("..", 1)
        return True, "\n".join(record_state.ancestry_path(ancestor, revision))
    if args[:3] == ["log", "--format=%x00%H", "--name-only"] and "--" in args:
        paths = git_snapshot.pathspec(git_snapshot.root(), tuple(args))
        return True, "\n".join(f"\x00{sha}\n{path}" for path, sha in record_state.latest_commits(paths))
    return False, None


def install() -> None:
    heads = []  # asked once, on the first question: a command that asks none never reads the repository

    def answering(where, args):
        known, answer = _history(list(args))
        if known:
            return answer
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
