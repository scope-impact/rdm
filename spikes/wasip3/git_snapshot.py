"""
Spike: RDM's git questions answered from one snapshot the host takes with git
alone (``rdm-wasm`` writes it; no RDM on the host).

The snapshot is the whole repository's state, taken from its root, one fact
per line so that the host needs nothing but git and a shell to write it:

    head <git rev-parse HEAD>
    origin <git remote get-url origin>
    merging                     (present only while MERGE_HEAD exists)
    S <a line of git status --porcelain --ignored --untracked-files=all>
    F <a line of git ls-files -v>

Each question RDM asks (``rdm.kernel.git.git``) is answered from it as git
would answer it. A question the snapshot cannot answer gets None, as when git
is missing: the gates then say approval could not be verified, never that it
was. The one such question is where a merge commit holds a document's staged
content (``log --find-object``), asked only while a merge is being made.
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


def answer(snapshot: dict, where, args: tuple) -> str | None:
    args = list(args)
    pathspec = [_path(os.path.join(str(where), a)) for a in args[args.index("--") + 1:]] if "--" in args else []
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
        if pathspec:
            lines = [line for line in lines if any(_under(line[3:], scope) for scope in pathspec)]
        return "\n".join(lines)
    if options == ["ls-files", "-v"] and snapshot["head"] is not None:
        return "\n".join(line for line in snapshot["files"] if any(_under(line[2:], scope) for scope in pathspec))
    return None  # not in the snapshot: as if git could not say


def replay(path: str) -> None:
    """Answer RDM's git questions from the snapshot at ``path``."""
    snapshot = {"head": None, "origin": None, "merging": False, "status": [], "files": []}
    with open(path, encoding="utf-8") as stream:
        for line in stream.read().splitlines():
            kind, _, rest = line.partition(" ")
            if kind in ("head", "origin"):
                snapshot[kind] = rest or None
            elif kind == "merging":
                snapshot["merging"] = True
            elif kind in ("S", "F"):
                snapshot["status" if kind == "S" else "files"].append(rest)
    trace = bool(os.environ.get("RDM_GIT_SNAPSHOT_TRACE"))

    def git(where, *args):
        result = answer(snapshot, where, args)
        if trace:  # each question and its answer, to stderr
            print(f"git -C {where} {' '.join(args)} -> {result!r}", file=sys.stderr)
        return result

    for name in _USERS:
        module = sys.modules.get(name) or __import__(name, fromlist=["git"])
        module.git = git
