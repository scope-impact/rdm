"""
Spike: git answers recorded on the host and replayed in the component.

Every git question RDM's core asks goes through ``rdm.kernel.git.git``. On
the host, ``record`` runs an RDM command natively and saves each question and
its answer; in the component, ``replay`` answers from that file. Paths are
stored relative to the record's root, so the host's checkout and the
component's mounted ``/`` match.

    python git_facts.py record FACTS.json -- story design-gate --dhf dhf
"""

import json
import os
import sys

import rdm.kernel.git

# The modules that imported ``git`` by name; patch their copies too.
_USERS = ("rdm.kernel.git", "rdm.specification.design_gate", "rdm.publishing.report")


def _relative(value) -> str:
    text = str(value)
    return os.path.relpath(os.path.abspath(text)) if os.path.isabs(text) else text


def _key(where, args) -> str:
    return json.dumps([_relative(os.path.abspath(where)), *(_relative(a) for a in args)])


def _patch(fake) -> None:
    for name in _USERS:
        module = sys.modules.get(name) or __import__(name, fromlist=["git"])
        module.git = fake


def replay(path: str) -> None:
    """Answer git from the facts file at ``path``; an unknown question gets None."""
    with open(path, encoding="utf-8") as stream:
        facts = json.load(stream)
    _patch(lambda where, *args: facts.get(_key(where, args)))


def record(path: str, argv: list[str]) -> int:
    """Run ``rdm argv`` natively, saving every git question and answer to ``path``."""
    from rdm.main import cli

    real, facts = rdm.kernel.git.git, {}

    def recording(where, *args):
        facts[_key(where, args)] = answer = real(where, *args)
        return answer

    _patch(recording)
    try:
        code = cli(argv)
    finally:
        with open(path, "w", encoding="utf-8") as stream:
            json.dump(facts, stream, indent=1, sort_keys=True)
    return code


if __name__ == "__main__":
    if sys.argv[1:2] != ["record"] or sys.argv[3:4] != ["--"]:
        sys.exit("usage: git_facts.py record FACTS.json -- RDM-ARGS...")
    sys.exit(record(sys.argv[2], sys.argv[4:]))
