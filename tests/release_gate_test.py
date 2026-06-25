"""Tests for the release gate: design controls + full verification (hard fail)."""

from __future__ import annotations

from pathlib import Path

from rdm.story_audit.design_gate import run_release_gate, story_release_gate_command
from tests.util import COMPLETE_DOC as COMPLETE
from tests.util import git_run as _git
from tests.util import write_allure_result as _result


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
