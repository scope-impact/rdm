"""Acceptance test for evidence tied to the version it tested (DI-60, see dhf/).

Tagged `@allure.story("DI-60")`, over the real projection and gate shapes,
from a committed record and crafted Allure results. Skips cleanly if
allure-pytest or the `graph` extra is not installed.
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

allure = pytest.importorskip("allure")
pytest.importorskip("pyshacl")

from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate  # noqa: E402
from tests.acceptance.evidence import attach, clause  # noqa: E402
from tests.acceptance.test_graph import _record  # noqa: E402
from tests.util import git_run  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"
P = "urn:dhf:acme:"
UNVERSIONED = "test run is tied to no commit: the version it is evidence for is unknown"
STALE = "test run tested another commit than the record's: stale evidence"


def _sha(repo: Path) -> str:
    return subprocess.run(["git", "-C", str(repo), "rev-parse", "HEAD"], capture_output=True, text=True).stdout.strip()


def _run(results: Path, name: str, *labels: tuple[str, str]) -> None:
    (results / f"{name}-result.json").write_text(json.dumps({
        "name": name, "status": "passed",
        "labels": [{"name": "story", "value": "DI-1"}] + [{"name": n, "value": v} for n, v in labels]}))


@allure.story("DI-60")
@allure.label("output", "rdm/graph/allure.py")
@allure.label("output", "rdm/graph/project.py")
@allure.label("output", "rdm/graph/shapes.ttl")
def test_runs_are_tied_to_the_commit_they_tested(tmp_path: Path) -> None:
    """DI-60: each run links to the commit its label names; the record carries the
    commit it was built at and a dirty run is marked; shapes warn on an
    unversioned run and on a run of another commit."""
    dhf, results = _record(tmp_path)
    repo = dhf.parent
    old = _sha(repo)
    (repo / "NOTES.md").write_text("later\n")
    git_run(repo, "add", "-A")
    git_run(repo, "commit", "-m", "later")
    head = _sha(repo)
    for f in results.glob("*"):
        f.unlink()
    _run(results, "current", ("commit", head), ("worktree", "clean"))  # only "dirty" marks a run
    _run(results, "dirty", ("commit", head), ("worktree", "dirty"))
    _run(results, "stale", ("commit", old))
    _run(results, "unversioned")
    quads = project(dhf, results)
    facts = {(q.subject.value, q.predicate.value.replace(RDM, ""), q.object.value) for q in quads}
    report = validate(quads)
    attach("validation results", sorted((r.label, r.message) for r in report if r.message in (UNVERSIONED, STALE)))

    with clause("each run links to the commit its commit label names"):
        assert (P + "run/current-result", "testedAt", P + f"commit/{head}") in facts
        assert (P + "run/stale-result", "testedAt", P + f"commit/{old}") in facts
        assert not any(s == P + "run/unversioned-result" and p == "testedAt" for s, p, _ in facts)
    with clause("the record carries the commit it was built at"):
        assert (P + "record", "atCommit", P + f"commit/{head}") in facts
    with clause("a run of uncommitted changes is marked, a clean one is not"):
        assert (P + "run/dirty-result", "uncommittedChanges", "true") in facts
        assert not any(s == P + "run/current-result" and p == "uncommittedChanges" for s, p, _ in facts)
    with clause("a shape warns on a run tied to no commit"):
        assert {r.label for r in report if r.message == UNVERSIONED} == {"unversioned"}
    with clause("a shape warns on a run that tested another commit than the record's"):
        assert {r.label for r in report if r.message == STALE} == {"stale"}
        assert all(r.severity == "Warning" for r in report if r.message in (UNVERSIONED, STALE))
