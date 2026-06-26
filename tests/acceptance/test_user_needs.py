"""Acceptance tests for RDM's own design inputs (see dhf/).

Each test *is* the acceptance criterion ("live BDD"): the test is the behaviour,
the `@allure.story` tag (DI-n) is the traceability link to the design input it
verifies, and the optional `@allure.label("output", "...")` records the design
output exercised. There is no Gherkin / feature file / step glue — the reviewed
spec lives in the registries (the per-context design docs, the V&V plan) and the living doc
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
from rdm.story_audit.design_gate import check_design_docs, run_release_gate
from tests.util import COMPLETE_DOC as COMPLETE
from tests.util import git_run as _git
from tests.util import write_allure_result as _allure_result
from tests.util import write_design_doc
from tests.util import write_faithful_verdicts as _faithful

# Tagging requires allure-pytest; skip cleanly if it is not installed.
allure = pytest.importorskip("allure")


def _vv_plan(docs: Path, needs: list[str]) -> None:
    items = "\n".join(f"  - {{id: {n}, text: {n}}}" for n in needs)
    (docs / "verification_and_validation_plan.md").write_text(
        f"---\nid: VVP-001\nuser_needs:\n{items}\n---\n\nplan\n"
    )


def _approved_dhf(
    tmp_path: Path,
    needs: list[str],
    inputs: list[tuple[str, list[str]]] | None = None,
) -> Path:
    """A committed DHF: an approved per-context design doc (carrying the design
    inputs + output), a design review, and a V&V registry. By default one design
    input is declared per user need.
    """
    repo = tmp_path / "repo"
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    _git(repo, "init")
    if inputs is None:
        inputs = [(f"DI-{i + 1}", [n]) for i, n in enumerate(needs)]
    write_design_doc(docs / "design", "core", satisfies=tuple(needs),
                     design_inputs=tuple(inputs))
    (docs / "design_review.md").write_text(COMPLETE)
    _vv_plan(docs, needs)
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
    # Incomplete (placeholder) design doc -> not complete.
    docs = tmp_path / "dhf" / "documents" / "design"
    docs.mkdir(parents=True)
    (docs / "core.md").write_text("---\nkind: design\ncontext: core\n---\nTODO: fill me\nENDTODO\n")
    assert not check_design_docs(tmp_path / "dhf")[0].complete
    # Approved (committed clean) -> ok.
    dhf = _approved_dhf(tmp_path, ["UN-002"])
    assert all(c.ok for c in check_design_docs(dhf))


@allure.story("DI-3")
def test_release_gate_blocks_until_verified(tmp_path: Path) -> None:
    """DI-3: block release until every design input is verified by a passing test
    AND independently confirmed to verify it (the faithfulness gate)."""
    dhf = _approved_dhf(tmp_path, ["UN-003"])  # DI-1 traces to UN-003
    (dhf.parent / "tests").mkdir(exist_ok=True)  # isolate the test-source hash
    empty = tmp_path / "none"
    empty.mkdir()
    assert not run_release_gate(dhf, empty).passed  # untested -> blocked
    results = tmp_path / "allure"
    _allure_result(results, "a", "passed", "DI-1")
    # Verified (the test passed) but not yet faithfully reviewed -> still blocked.
    assert not run_release_gate(dhf, results).passed
    _faithful(dhf)  # record the independent faithfulness verdict
    assert run_release_gate(dhf, results).passed  # verified + faithful -> passes


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
    from types import SimpleNamespace

    from rdm.project_management.sync import build_task_body

    # The note declares non-record status...
    assert "not a controlled record" in PROVENANCE_NOTE
    # ...and a generated planning artifact actually carries the stamp (verifying
    # the behaviour, not merely the constant's wording).
    task = SimpleNamespace(id="rdm-001", description="x", business_value="",
                           acceptance_criteria=[], subtask_ids=[], priority="high")
    assert PROVENANCE_NOTE in build_task_body(task)
