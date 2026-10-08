"""
Spike: RDM's git questions answered as git would answer them, from a snapshot
of the record's state: what git's status and index say of some paths.

    head <commit>, origin <url>, merging
    status: lines of git status --porcelain --ignored
    files:  lines of git ls-files -v

``git_component.py`` builds such a snapshot for each question from the
``record-state`` import (rdm-git). A question a snapshot cannot answer gets
None, as when git is missing: the gates then say approval could not be
verified, never that it was. The one such question is where a merge commit
holds a document's staged content (``log --find-object``), asked only while a
merge is being made.
"""

import os
import sys

_USERS = ("rdm.kernel.git", "rdm.specification.design_gate", "rdm.publishing.report")


def _path(text: str) -> str:
    """A path as the repository names it: relative to its root, which the component mounts at /."""
    return os.path.relpath(os.path.abspath(text), "/")


def _under(path: str, scope: str) -> bool:
    return scope in ("", ".") or path == scope or path.startswith(scope.rstrip("/") + "/") or \
        (scope + "/").startswith(path) and path.endswith("/")  # an untracked or ignored directory


def pathspec(where, args: tuple) -> list[str]:
    """The paths a git question names (after ``--``), as the repository names them."""
    args = list(args)
    return [_path(os.path.join(str(where), a)) for a in args[args.index("--") + 1:]] if "--" in args else []


def answer(snapshot: dict, where, args: tuple) -> str | None:
    args = list(args)
    pathspec_ = pathspec(where, args)
    options = args[:args.index("--")] if "--" in args else args
    if options == ["rev-parse", "HEAD"]:
        return snapshot["head"]
    if options == ["remote", "get-url", "origin"]:
        return snapshot["origin"]
    if options == ["rev-parse", "-q", "--verify", "MERGE_HEAD"]:
        return "MERGE_HEAD" if snapshot["merging"] else None
    if options and options[0] == "status" and snapshot["head"] is not None:
        ignored = "--ignored" in options
        lines = [line for line in snapshot["status"] if ignored or not line.startswith("!!")]
        if pathspec_:
            lines = [line for line in lines if any(_under(line[3:], scope) for scope in pathspec_)]
        return "\n".join(lines)
    if options == ["ls-files", "-v"] and snapshot["head"] is not None:
        return "\n".join(line for line in snapshot["files"] if any(_under(line[2:], scope) for scope in pathspec_))
    return None  # not in the snapshot: as if git could not say


def install(answering) -> None:
    """Make ``answering(where, args)`` the git RDM asks."""
    trace = bool(os.environ.get("RDM_GIT_TRACE"))

    def git(where, *args):
        result = answering(where, args)
        if trace:  # each question and its answer, to stderr
            print(f"git -C {where} {' '.join(args)} -> {result!r}", file=sys.stderr)
        return result

    for name in _USERS:
        module = sys.modules.get(name) or __import__(name, fromlist=["git"])
        module.git = git
