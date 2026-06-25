"""Tests for the release gate: design controls + full verification (hard fail).

Verification is anchored on design inputs: the denominator is the design-input
registry, a design input is verified when its `@allure.story("DI-…")` test
passes, and a user need with no design input blocks the release.
"""

from __future__ import annotations

from pathlib import Path

from rdm.story_audit.design_gate import run_release_gate, story_release_gate_command
from tests.util import COMPLETE_DOC as COMPLETE
from tests.util import git_run as _git
from tests.util import write_allure_result as _result


def _project(
    tmp_path: Path,
    inputs: list[tuple[str, list[str]]],
    user_needs: list[str] | None = None,
    *,
    commit: bool = True,
) -> Path:
    """Build a git repo with approved design docs, a design-input registry, and
    a user-need registry. `inputs` is ``(DI-id, [user needs it traces_to])``;
    user_needs defaults to the union of everything the inputs trace to.
    """
    if user_needs is None:
        user_needs = sorted({un for _, traces in inputs for un in traces})
    repo = tmp_path / "repo"
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    _git(repo, "init")
    rows = "\n".join(
        f"  - {{id: {di}, text: {di} requirement, traces_to: [{', '.join(traces)}]}}"
        for di, traces in inputs
    )
    (docs / "design_input.md").write_text(
        f"---\nid: DI-001\ndesign_inputs:\n{rows}\n---\n\nApproved and complete.\n"
        if inputs
        else COMPLETE
    )
    (docs / "design_review.md").write_text(COMPLETE)
    needs = "\n".join(f"  - {{id: {n}, text: {n}}}" for n in user_needs)
    (docs / "verification_and_validation_plan.md").write_text(
        f"---\nid: VVP-001\nuser_needs:\n{needs}\n---\n\nplan\n"
    )
    if commit:
        _git(repo, "add", "-A")
        _git(repo, "commit", "-m", "approve design")
    return repo / "dhf"


def test_passes_when_design_approved_and_all_verified(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [("DI-1", ["UN-001"]), ("DI-2", ["UN-002"])])
    results = tmp_path / "allure"
    _result(results, "a", "passed", "DI-1")
    _result(results, "b", "passed", "DI-2")
    outcome = run_release_gate(dhf, results)
    assert outcome.passed
    assert set(outcome.verified) == {"DI-1", "DI-2"}
    assert story_release_gate_command(dhf_dir=dhf, allure_results_dir=results) == 0


def test_blocks_on_failed_design_input(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])])
    results = tmp_path / "allure"
    _result(results, "a", "failed", "DI-1")
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("DI-1" in b and "FAILED" in b for b in outcome.blocking)


def test_blocks_on_untested_design_input(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [("DI-1", ["UN-001"]), ("DI-2", ["UN-002"])])
    results = tmp_path / "allure"
    _result(results, "a", "passed", "DI-1")  # DI-2 has no test
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("DI-2" in b for b in outcome.blocking)


def test_blocks_on_user_need_with_no_design_input(tmp_path: Path) -> None:
    # UN-002 is declared in the registry but no design input traces to it.
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])], user_needs=["UN-001", "UN-002"])
    results = tmp_path / "allure"
    _result(results, "a", "passed", "DI-1")
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("UN-002" in b and "no design input" in b for b in outcome.blocking)


def test_blocks_when_design_not_approved(tmp_path: Path) -> None:
    # Design docs present + complete but uncommitted -> not approved.
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])], commit=False)
    results = tmp_path / "allure"
    _result(results, "a", "passed", "DI-1")
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("design control" in b for b in outcome.blocking)


def test_blocks_when_no_design_inputs_declared(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [], user_needs=["UN-001"])
    results = tmp_path / "allure"
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("no design inputs" in b for b in outcome.blocking)


def test_orphan_tag_is_warning_not_blocking(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])])
    results = tmp_path / "allure"
    _result(results, "a", "passed", "DI-1")
    _result(results, "b", "passed", "DI-777")  # orphan, shares prefix
    outcome = run_release_gate(dhf, results)
    assert outcome.passed
    assert any("DI-777" in w for w in outcome.warnings)


def test_command_requires_allure_results(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])])
    assert story_release_gate_command(dhf_dir=dhf, allure_results_dir=None) == 2
