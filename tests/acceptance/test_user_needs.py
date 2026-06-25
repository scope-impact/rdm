"""Acceptance tests for RDM's own design inputs (see dhf/).

Each test *is* the acceptance criterion ("live BDD"): the test is the behaviour,
the `@allure.story("DI-...")` tag is the traceability link to the design input it
verifies, and the optional `@allure.label("output", "...")` records the design
output exercised. There is no Gherkin / feature file / step glue — the reviewed
spec lives in the registries (`design_input.md`, the V&V plan) and the living doc
is the Allure report. An `allure.step(...)` narrative is available but optional
and free-form (it need not be Given/When/Then).

Run with Allure to produce the verification evidence the release gate consumes:

    uv run pytest tests/acceptance --alluredir=dhf/allure-results
    rdm story release-gate --dhf dhf --allure-results dhf/allure-results

Verification is anchored on design inputs (§820.30(f): output meets input);
validation against user needs stays UN-keyed (see test_formative_usability).
Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.project_management.sync import PROVENANCE_NOTE
from rdm.record import allure as allure_ingest
from rdm.record import persona
from rdm.record.verify import build_verification
from rdm.story_audit.design_gate import check_artifact, run_release_gate
from tests.util import COMPLETE_DOC as COMPLETE
from tests.util import git_run as _git
from tests.util import write_allure_result as _allure_result

# Tagging requires allure-pytest; skip cleanly if it is not installed.
allure = pytest.importorskip("allure")


def _vv_plan(docs: Path, needs: list[str]) -> None:
    items = "\n".join(f"  - {{id: {n}, text: {n}}}" for n in needs)
    (docs / "verification_and_validation_plan.md").write_text(
        f"---\nid: VVP-001\nuser_needs:\n{items}\n---\n\nplan\n"
    )


def _design_input_doc(docs: Path, inputs: list[tuple[str, list[str]]]) -> None:
    """Write a design_input.md with a `design_inputs` registry frontmatter.

    `inputs` is a list of ``(DI-id, [user-need IDs it traces_to])``.
    """
    rows = "\n".join(
        f"  - {{id: {di}, text: {di} requirement, traces_to: [{', '.join(traces)}]}}"
        for di, traces in inputs
    )
    (docs / "design_input.md").write_text(
        f"---\nid: DI-001\ndesign_inputs:\n{rows}\n---\n\nApproved and complete.\n"
    )


def _approved_dhf(
    tmp_path: Path,
    needs: list[str],
    inputs: list[tuple[str, list[str]]] | None = None,
) -> Path:
    """A committed DHF: approved design docs, a V&V registry, a design-input
    registry, and one SDD. By default one design input is declared per user need.
    """
    repo = tmp_path / "repo"
    docs = repo / "dhf" / "documents"
    (docs / "sdd").mkdir(parents=True)
    _git(repo, "init")
    if inputs is None:
        inputs = [(f"DI-{i + 1}", [n]) for i, n in enumerate(needs)]
    _design_input_doc(docs, inputs)
    (docs / "design_review.md").write_text(COMPLETE)
    _vv_plan(docs, needs)
    (docs / "sdd" / "core.md").write_text(
        f"---\ncontext: core\nsatisfies: [{', '.join(needs)}]\n---\n\ndesign\n"
    )
    _git(repo, "add", "-A")
    _git(repo, "commit", "-m", "approve")
    return repo / "dhf"


@allure.story("DI-1")
@allure.label("output", "SDS-record")
def test_compile_verification_from_the_record(tmp_path: Path) -> None:
    """DI-1: compile a DHF from the system of record (registry + results)."""
    dhf = _approved_dhf(tmp_path, ["UN-001"])  # DI-1 traces to UN-001
    results = tmp_path / "allure"
    _allure_result(results, "a", "passed", "DI-1")
    with allure.step("Reconcile the declared design inputs against Allure results"):
        data = build_verification(dhf, results)
    assert data["summary"]["total"] == 1
    # Rows are design inputs, grouped under the user need they trace to.
    assert data["groups"][0]["user_need"] == "UN-001"
    assert data["groups"][0]["design_inputs"][0]["design_input"] == "DI-1"


@allure.story("DI-2")
def test_design_gate_requires_approval(tmp_path: Path) -> None:
    """DI-2: block transition until design docs are complete and approved."""
    # Incomplete (placeholder) -> not complete.
    docs = tmp_path / "dhf" / "documents"
    docs.mkdir(parents=True)
    (docs / "design_input.md").write_text("TODO: fill me\nENDTODO\n")
    assert not check_artifact(tmp_path / "dhf", "design_input.md", "Design Input").complete
    # Approved (committed clean) -> ok.
    dhf = _approved_dhf(tmp_path, ["UN-002"])
    assert check_artifact(dhf, "design_input.md", "Design Input").ok


@allure.story("DI-3")
def test_release_gate_blocks_until_verified(tmp_path: Path) -> None:
    """DI-3: block release until every design input is verified by a passing test."""
    dhf = _approved_dhf(tmp_path, ["UN-003"])  # DI-1 traces to UN-003
    empty = tmp_path / "none"
    empty.mkdir()
    assert not run_release_gate(dhf, empty).passed  # untested -> blocked
    results = tmp_path / "allure"
    _allure_result(results, "a", "passed", "DI-1")
    assert run_release_gate(dhf, results).passed  # verified -> passes


@allure.story("DI-4")
def test_verification_status_traceable_from_results(tmp_path: Path) -> None:
    """DI-4: each design input's status is traceable from executed results."""
    results = tmp_path / "allure"
    _allure_result(results, "ok", "passed", "DI-A")
    _allure_result(results, "bad", "failed", "DI-B")
    report = allure_ingest.reconcile({"DI-A", "DI-B", "DI-C"}, results)
    assert report.verified == ["DI-A"]
    assert report.failed == ["DI-B"]
    assert report.untested == ["DI-C"]


@allure.story("DI-5")
def test_formative_usability_classified(tmp_path: Path) -> None:
    """DI-5: usability can be exercised formatively against a user need.

    Validation stays anchored on the *user need* (UN-001), not a design input:
    formative usability evidence never gates release.
    """
    runs = tmp_path / "persona-results"
    runs.mkdir()
    (runs / "p-persona.json").write_text(
        json.dumps({"persona": "nurse", "user_need": "UN-001", "outcome": "success",
                    "usability_issues": [{"severity": "confusion", "step": 1, "note": "x"}]})
    )
    report = persona.reconcile({"UN-001"}, runs)
    assert report.by_user_need["UN-001"].status == persona.ISSUES


@allure.story("DI-6")
def test_planning_artifacts_marked_non_record() -> None:
    """DI-6: planning tooling is optional and its outputs are marked non-record."""
    assert "not a controlled record" in PROVENANCE_NOTE
