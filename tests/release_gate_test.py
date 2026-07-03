"""Tests for the release gate: design controls + full verification (hard fail).

Verification is anchored on design inputs declared in the per-context design
documents: the denominator is the union of those inputs, a design input is
verified when its DI-tagged `@allure.story` test passes, and a user need with no
design input blocks the release.
"""

from __future__ import annotations

import json
from pathlib import Path

from rdm.story_audit.design_gate import run_release_gate, story_release_gate_command
from tests.util import COMPLETE_DOC as COMPLETE
from tests.util import git_run as _git
from tests.util import write_allure_result as _result
from tests.util import write_design_doc
from tests.util import write_faithful_verdicts as _faithful


def _project(
    tmp_path: Path,
    inputs: list[tuple[str, list[str]]],
    user_needs: list[str] | None = None,
    *,
    commit: bool = True,
    faithful: bool = True,
) -> Path:
    """Build a git repo with an approved per-context design doc (carrying the
    design inputs), a design review, a user-need registry, and (by default)
    faithfulness verdicts. `inputs` is ``(DI-id, [user needs it traces_to])``.
    """
    if user_needs is None:
        user_needs = sorted({un for _, traces in inputs for un in traces})
    repo = tmp_path / "repo"
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    (repo / "tests").mkdir()  # empty -> isolate the test-source hash from this repo
    _git(repo, "init")
    write_design_doc(docs / "design", "core", satisfies=tuple(user_needs),
                     design_inputs=tuple(inputs))
    (docs / "design_review.md").write_text(COMPLETE)
    needs = "\n".join(f"  - {{id: {n}, text: {n}}}" for n in user_needs)
    (docs / "verification_and_validation_plan.md").write_text(
        f"---\nid: VVP-001\nuser_needs:\n{needs}\n---\n\nplan\n"
    )
    if commit:
        _git(repo, "add", "-A")
        _git(repo, "commit", "-m", "approve design")
    dhf = repo / "dhf"
    if faithful:
        _faithful(dhf)
    return dhf


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


def test_blocks_when_verified_but_not_faithfully_reviewed(tmp_path: Path) -> None:
    # The test passes (verified) but no faithfulness verdict exists -> blocked.
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])], faithful=False)
    results = tmp_path / "allure"
    _result(results, "a", "passed", "DI-1")
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("DI-1" in b and "faithfulness" in b for b in outcome.blocking)


def test_blocks_on_unfaithful_verdict(tmp_path: Path) -> None:
    from rdm.record.allure import find_tests_dir
    from rdm.record.faithfulness import current_hashes
    from rdm.record.sdd import design_inputs

    dhf = _project(tmp_path, [("DI-1", ["UN-001"])], faithful=False)
    results = tmp_path / "allure"
    _result(results, "a", "passed", "DI-1")
    # An unfaithful verdict for the CURRENT test (matching hash, so it reads as
    # unfaithful rather than stale).
    cur = current_hashes(design_inputs(dhf), find_tests_dir(dhf))["DI-1"]
    (dhf / "faithfulness").mkdir(parents=True)
    (dhf / "faithfulness" / "DI-1-faithfulness.json").write_text(json.dumps({
        "design_input": "DI-1", "verdict": "unfaithful", "reviewer": "r",
        "rationale": "the test asserts a tautology, not the requirement",
        "test_hash": cur,
    }))
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("DI-1" in b and "FAILED faithfulness" in b for b in outcome.blocking)


def test_stale_verdict_blocks(tmp_path: Path) -> None:
    # A faithful verdict pinned to the wrong hash is stale -> blocked.
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])], faithful=False)
    results = tmp_path / "allure"
    _result(results, "a", "passed", "DI-1")
    (dhf / "faithfulness").mkdir(parents=True)
    (dhf / "faithfulness" / "DI-1-faithfulness.json").write_text(json.dumps({
        "design_input": "DI-1", "verdict": "faithful", "reviewer": "r",
        "rationale": "looked good at the time", "test_hash": "sha256:deadbeef",
    }))
    outcome = run_release_gate(dhf, results)
    assert not outcome.passed
    assert any("DI-1" in b and "STALE" in b for b in outcome.blocking)


def test_command_requires_allure_results(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])])
    assert story_release_gate_command(dhf_dir=dhf, allure_results_dir=None) == 2
