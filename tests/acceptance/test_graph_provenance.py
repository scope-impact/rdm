"""Acceptance tests for provenance and document links in the graph (DI-51,
DI-52, see dhf/).

Tagged `@allure.story`, over the real projection and shapes and a real git
history: a direct commit, a merge landed by someone else, and a change on an
unmerged branch. Skips cleanly if allure-pytest or the `graph` extra is not
installed.
"""

from __future__ import annotations

import subprocess
from pathlib import Path

import pytest
import yaml

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402
pytest.importorskip("pyshacl")

from rdm.graph.project import project  # noqa: E402
from rdm.graph.validate import validate  # noqa: E402
from tests.acceptance.test_graph_shapes import _dhf  # noqa: E402
from tests.acceptance.test_risk import POLICY, _register, _risk  # noqa: E402

RDM = "https://github.com/scope-impact/rdm/ns#"
PROV = "http://www.w3.org/ns/prov#"
NOT_LANDED = "document's latest change has not landed on the default branch"


def _git(repo: Path, who: str, *args: str) -> str:
    return subprocess.run(["git", "-c", f"user.name={who}", "-c", f"user.email={who}@x", *args], cwd=repo,
                          check=True, capture_output=True, text=True).stdout.strip()


def _facts(quads, subject: str) -> set[tuple[str, str]]:
    return {(q.predicate.value.replace(RDM, "").replace(PROV, "prov:"), q.object.value)
            for q in quads if q.subject.value == subject}


def _label(quads, node: str) -> str:
    return next(q.object.value for q in quads if q.subject.value == node and q.predicate.value.endswith("#label"))


@allure.story("DI-51")
@allure.label("output", "rdm/graph/project.py")
def test_each_design_document_records_who_landed_its_latest_change(tmp_path: Path) -> None:
    """DI-51: the first-parent commit of the default branch that landed a
    controlled document's latest change — the commit itself when direct (or squashed), the
    merge otherwise — and its author; a shape warns when it has not landed."""
    dhf = _dhf(tmp_path / "x")                 # committed by "t" on the initial branch
    repo = dhf.parent
    _git(repo, "t", "branch", "-M", "main")
    direct = _git(repo, "t", "rev-parse", "HEAD")

    with verification_step("A change on a feature branch, merged into main by someone else"):
        _git(repo, "author", "checkout", "-q", "-b", "feature")
        (dhf / "documents" / "design" / "ui.md").write_text(
            "---\nid: SDS-UI\nkind: design\ncontext: ui\ndesign_inputs: []\n---\n# UI\n")
        _git(repo, "author", "add", "-A")
        _git(repo, "author", "commit", "-q", "-m", "ui design")
        change = _git(repo, "author", "rev-parse", "HEAD")
        _git(repo, "merger", "checkout", "-q", "main")
        _git(repo, "merger", "merge", "-q", "--no-ff", "-m", "Merge feature", "feature")
        merge = _git(repo, "merger", "rev-parse", "HEAD")
    with verification_step("Later work on main: the landing is still the merge, not the newest commit"):
        (repo / "NOTES.md").write_text("later\n")
        _git(repo, "later", "add", "-A")
        _git(repo, "later", "commit", "-q", "-m", "later work")

    with verification_step("A change on a branch that has not landed"):
        _git(repo, "wip", "checkout", "-q", "-b", "wip")
        core = dhf / "documents" / "design" / "core.md"
        core.write_text(core.read_text().replace("# Core", "# Core, revised"))
        _git(repo, "wip", "commit", "-qam", "revise core")

        quads = project(dhf)
        ui, core_doc = _facts(quads, "urn:dhf:proj:doc/SDS-UI"), _facts(quads, "urn:dhf:proj:doc/SDS-1")

    with verification_step("Merged: authored on the branch, landed by the merge and its author"):
        assert ("prov:wasGeneratedBy", f"urn:dhf:proj:commit/{change}") in ui
        assert ("landedIn", f"urn:dhf:proj:commit/{merge}") in ui
        assert {_label(quads, o) for p, o in ui if p == "landedBy"} == {"merger"}
    with verification_step("Not landed: no landing facts, and the warning names it"):
        assert not any(p.startswith("landed") for p, _ in core_doc)
        warned = {r.label for r in validate(quads) if r.message == NOT_LANDED}
        assert warned == {"SDS-1"}

    # Back on main, the core document's latest change is the direct commit: it
    # landed in itself, by its own author.
    with verification_step("Back on main, the core document's latest change is the direct commit: it landed in "
                           "itself, by…"):
        _git(repo, "t", "checkout", "-q", "main")
        quads = project(dhf)
        core_doc = _facts(quads, "urn:dhf:proj:doc/SDS-1")
        assert ("landedIn", f"urn:dhf:proj:commit/{direct}") in core_doc
        assert {_label(quads, o) for p, o in core_doc if p == "landedBy"} == {"t"}
        assert not {r.label for r in validate(quads) if r.message == NOT_LANDED}
    with verification_step("Every controlled document, not only design documents: the V&V plan has its commit and "
                           "landing"):
        vvp = _facts(quads, "urn:dhf:proj:doc/VVP-1")
        assert ("prov:wasGeneratedBy", f"urn:dhf:proj:commit/{direct}") in vvp
        assert ("landedIn", f"urn:dhf:proj:commit/{direct}") in vvp
    with verification_step("A repository with no remote whose one branch is not main or master: it is the default"):
        lone = _dhf(tmp_path / "trunk")
        _git(lone.parent, "t", "branch", "-m", "trunk")
        quads = project(lone)
        assert not {r.label for r in validate(quads) if r.message == NOT_LANDED}
        assert ("landedIn", f"urn:dhf:proj:commit/{_git(lone.parent, 't', 'rev-parse', 'HEAD')}") in _facts(
            quads, "urn:dhf:proj:doc/SDS-1")


@allure.story("DI-52")
@allure.label("output", "rdm/graph/project.py")
def test_needs_risks_and_design_documents_link_to_their_documents(tmp_path: Path) -> None:
    """DI-52: each user need to the document that declares it, each risk to the
    document holding the risk policy; no design document claims a review."""
    dhf = _dhf(tmp_path / "x")
    (dhf / "documents" / "risk").mkdir()
    (dhf / "documents" / "risk" / "rmp.md").write_text(
        "---\n" + yaml.safe_dump({"id": "RMP-9", "risk_policy": POLICY}) + "---\n# Policy\n")
    _register(dhf, [_risk("RISK-L-1")])
    quads = project(dhf)
    doc = "urn:dhf:proj:doc/"
    assert ("declaredIn", doc + "VVP-1") in _facts(quads, "urn:dhf:proj:need/UN-1")
    assert ("declaredIn", doc + "VVP-1") in _facts(quads, "urn:dhf:proj:need/UN-2")
    assert ("evaluatedAgainst", doc + "RMP-9") in _facts(quads, "urn:dhf:proj:risk/RISK-L-1")
    with verification_step("A design document is not linked to the design review: the record does not say which "
                           "covered it"):
        assert not any(o == doc + "DR-1" for _, o in _facts(quads, doc + "SDS-1"))
    with verification_step("Without a policy, a risk links to none"):
        (dhf / "documents" / "risk" / "rmp.md").unlink()
        assert not any(p == "evaluatedAgainst" for p, _ in _facts(project(dhf), "urn:dhf:proj:risk/RISK-L-1"))
