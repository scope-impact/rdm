"""Tests for the release gate: design controls + full verification (hard fail)."""

from __future__ import annotations

import json
import subprocess
from pathlib import Path

from rdm.story_audit.design_gate import run_release_gate, story_release_gate_command

COMPLETE = "# Doc\n\nApproved and complete.\n"


def _git(repo: Path, *args: str) -> None:
    subprocess.run(
        ["git", "-c", "user.email=t@t", "-c", "user.name=t", *args],
        cwd=repo,
        check=True,
        capture_output=True,
    )


def _result(results: Path, name: str, status: str, *ids: str) -> None:
    results.mkdir(parents=True, exist_ok=True)
    labels = [{"name": "story", "value": i} for i in ids]
    (results / f"{name}-result.json").write_text(
        json.dumps({"name": name, "status": status, "labels": labels})
    )


def _project(tmp_path: Path, user_needs: list[str], *, commit: bool = True) -> Path:
    """Build a git repo with approved design docs and an SDD declaring user needs."""
    repo = tmp_path / "repo"
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    _git(repo, "init")
    (docs / "design_input.md").write_text(COMPLETE)
    (docs / "design_review.md").write_text(COMPLETE)
    needs = "[" + ", ".join(user_needs) + "]"
    (docs / "software_design_specification.md").write_text(
        f"---\nid: SDS-001\ntitle: SDD\nuser_needs: {needs}\n---\n\nbody\n"
    )
    if commit:
        _git(repo, "add", "-A")
        _git(repo, "commit", "-m", "approve design")
    return repo / "dhf"


def test_passes_when_design_approved_and_all_verified(tmp_path: Path) -> None:
    dhf = _project(tmp_path, ["UN-001", "UN-002"])
    results = tmp_path / "allure"
    _result(results, "a", "passed", "UN-001")
    _result(results, "b", "passed", "UN-002")
    outcome = run_release_gate(dhf, results)
    assert outcome.passed
    assert set(outcome.verified) == {"UN-001", "UN-002"}
    assert story_release_gate_command(dhf_dir=dhf, allure_results_dir=results) == 0


def test_blocks_on_failed_user_need(tmp_path: Path) -> None:
    dhf = _project(tmp_path, ["UN-001"])
    results = tmp_path / "allure"
    _result(results, "a", "failed", "UN-001")
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("UN-001" in b and "FAILED" in b for b in outcome.blocking)


def test_blocks_on_untested_user_need(tmp_path: Path) -> None:
    dhf = _project(tmp_path, ["UN-001", "UN-002"])
    results = tmp_path / "allure"
    _result(results, "a", "passed", "UN-001")  # UN-002 has no test
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("UN-002" in b for b in outcome.blocking)


def test_blocks_when_design_not_approved(tmp_path: Path) -> None:
    # Design docs present + complete but uncommitted -> not approved.
    dhf = _project(tmp_path, ["UN-001"], commit=False)
    results = tmp_path / "allure"
    _result(results, "a", "passed", "UN-001")
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("design control" in b for b in outcome.blocking)


def test_blocks_when_no_user_needs_declared(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [])
    results = tmp_path / "allure"
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("no user needs" in b for b in outcome.blocking)


def test_orphan_tag_is_warning_not_blocking(tmp_path: Path) -> None:
    dhf = _project(tmp_path, ["UN-001"])
    results = tmp_path / "allure"
    _result(results, "a", "passed", "UN-001")
    _result(results, "b", "passed", "UN-777")  # orphan, shares prefix
    outcome = run_release_gate(dhf, results)
    assert outcome.passed
    assert any("UN-777" in w for w in outcome.warnings)


def test_command_requires_allure_results(tmp_path: Path) -> None:
    dhf = _project(tmp_path, ["UN-001"])
    assert story_release_gate_command(dhf_dir=dhf, allure_results_dir=None) == 2
