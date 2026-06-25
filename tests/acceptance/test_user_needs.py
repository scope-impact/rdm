"""Acceptance tests for RDM's own user needs (see dhf/).

Each test is the acceptance criterion for a user need and is tagged
`@allure.story("UN-...")`. Run with Allure to produce verification evidence the
release gate consumes:

    uv run pytest tests/acceptance --alluredir=dhf/allure-results
    rdm story release-gate --dhf dhf --allure-results dhf/allure-results

Skips cleanly if allure-pytest is not installed.
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

import pytest

from rdm.record import allure as allure_ingest
from rdm.record import persona
from rdm.record.verify import build_verification
from rdm.story_audit.design_gate import check_artifact, run_release_gate

# Tagging requires allure-pytest; skip cleanly if it is not installed.
allure = pytest.importorskip("allure")

COMPLETE = "# Doc\n\nApproved and complete.\n"


def _git(repo: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-c", "user.email=t@t", "-c", "user.name=t", *args],
        cwd=repo, check=True, capture_output=True,
    )


def _allure_result(results: Path, name: str, status: str, *ids: str) -> None:
    results.mkdir(parents=True, exist_ok=True)
    labels = [{"name": "story", "value": i} for i in ids]
    (results / f"{name}-result.json").write_text(
        json.dumps({"name": name, "status": status, "labels": labels})
    )


def _vv_plan(docs: Path, needs: list[str]) -> None:
    items = "\n".join(f"  - {{id: {n}, text: {n}}}" for n in needs)
    (docs / "verification_and_validation_plan.md").write_text(
        f"---\nid: VVP-001\nuser_needs:\n{items}\n---\n\nplan\n"
    )


def _approved_dhf(tmp_path: Path, needs: list[str]) -> Path:
    """A committed DHF: approved design docs, a V&V registry, one SDD."""
    repo = tmp_path / "repo"
    docs = repo / "dhf" / "documents"
    (docs / "sdd").mkdir(parents=True)
    _git(repo, "init")
    (docs / "design_input.md").write_text(COMPLETE)
    (docs / "design_review.md").write_text(COMPLETE)
    _vv_plan(docs, needs)
    (docs / "sdd" / "core.md").write_text(
        f"---\ncontext: core\nsatisfies: [{', '.join(needs)}]\n---\n\ndesign\n"
    )
    _git(repo, "add", "-A")
    _git(repo, "commit", "-m", "approve")
    return repo / "dhf"


@allure.story("UN-001")
def test_compile_verification_from_the_record(tmp_path: Path) -> None:
    """UN-001: compile a DHF from the system of record (registry + results)."""
    dhf = _approved_dhf(tmp_path, ["UN-001"])
    results = tmp_path / "allure"
    _allure_result(results, "a", "passed", "UN-001")
    data = build_verification(dhf, results)
    assert data["summary"]["total"] == 1
    assert data["needs"][0]["user_need"] == "UN-001"


@allure.story("UN-002")
def test_design_gate_requires_approval(tmp_path: Path) -> None:
    """UN-002: block transition until design docs are complete and approved."""
    # Incomplete (placeholder) -> not complete.
    docs = tmp_path / "dhf" / "documents"
    docs.mkdir(parents=True)
    (docs / "design_input.md").write_text("TODO: fill me\nENDTODO\n")
    assert not check_artifact(tmp_path / "dhf", "design_input.md", "Design Input").complete
    # Approved (committed clean) -> ok.
    dhf = _approved_dhf(tmp_path, ["UN-002"])
    assert check_artifact(dhf, "design_input.md", "Design Input").ok


@allure.story("UN-003")
def test_release_gate_blocks_until_verified(tmp_path: Path) -> None:
    """UN-003: block release until every user need is verified by a passing test."""
    dhf = _approved_dhf(tmp_path, ["UN-003"])
    empty = tmp_path / "none"
    empty.mkdir()
    assert not run_release_gate(dhf, empty).passed  # untested -> blocked
    results = tmp_path / "allure"
    _allure_result(results, "a", "passed", "UN-003")
    assert run_release_gate(dhf, results).passed  # verified -> passes


@allure.story("UN-004")
def test_verification_status_traceable_from_results(tmp_path: Path) -> None:
    """UN-004: each user need's status is traceable from executed results."""
    results = tmp_path / "allure"
    _allure_result(results, "ok", "passed", "UN-A")
    _allure_result(results, "bad", "failed", "UN-B")
    report = allure_ingest.reconcile({"UN-A", "UN-B", "UN-C"}, results)
    assert report.verified == ["UN-A"]
    assert report.failed == ["UN-B"]
    assert report.untested == ["UN-C"]


@allure.story("UN-005")
def test_formative_usability_classified(tmp_path: Path) -> None:
    """UN-005: usability can be exercised formatively against a user need."""
    runs = tmp_path / "persona-results"
    runs.mkdir()
    (runs / "p-persona.json").write_text(
        json.dumps({"persona": "nurse", "user_need": "UN-001", "outcome": "success",
                    "usability_issues": [{"severity": "confusion", "step": 1, "note": "x"}]})
    )
    report = persona.reconcile({"UN-001"}, runs)
    assert report.by_user_need["UN-001"].status == persona.ISSUES
