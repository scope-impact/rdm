"""Tests for the release gate: design controls + full verification (hard fail).

Verification is anchored on design inputs declared in the per-context design
documents: the denominator is the union of those inputs, a design input is
verified when its DI-tagged `@allure.story` test passes, and a user need with no
design input blocks the release.
"""

from __future__ import annotations

from pathlib import Path

from rdm.evidence.allure import VERIFIED, DesignInputVerification, VerificationReport
from rdm.release.gate import (
    NEED_UNADDRESSED,
    NO_DESIGN_INPUTS,
    ORPHAN_TAG,
    RELEASE_PERMITTED,
    State,
    derive,
    run_release_gate,
    story_release_gate_command,
)
from tests.util import COMPLETE_DOC as COMPLETE
from tests.util import git_run as _git
from tests.util import write_allure_result as _result
from tests.util import write_design_doc


def _project(
    tmp_path: Path,
    inputs: list[tuple[str, list[str]]],
    user_needs: list[str] | None = None,
    *,
    commit: bool = True,
) -> Path:
    """Build a git repo with an approved per-context design doc (carrying the
    design inputs), a design review, and a user-need registry. `inputs` is ``(DI-id, [user needs it traces_to])``.
    """
    if user_needs is None:
        user_needs = sorted({un for _, traces in inputs for un in traces})
    repo = tmp_path / "repo"
    docs = repo / "dhf" / "documents"
    docs.mkdir(parents=True)
    _git(repo, "init")
    write_design_doc(docs / "design", "core", design_inputs=tuple(inputs))
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


# The rules are pure: a hand-built state, no DHF on disk.

def _state(needs=("UN-1",), verified=("DI-1",), orphans=()) -> State:
    report = VerificationReport(by_id={i: DesignInputVerification(i, status=VERIFIED) for i in verified},
                                orphan_ids=list(orphans))
    return State("dhf", [], [{"id": "DI-1", "traces_to": ["UN-1"]}], set(needs), report)


def test_no_design_inputs_blocks() -> None:
    assert [e.name for e in derive(State("dhf", [], []))] == [NO_DESIGN_INPUTS]


def test_orphan_tag_warns_and_permits() -> None:
    events = derive(_state(orphans=["DI-777", "FT-001"]))  # FT-001 shares no declared prefix
    assert [e.name for e in events] == [RELEASE_PERMITTED, ORPHAN_TAG]
    assert "DI-777" in events[1].message


def test_unaddressed_need_blocks() -> None:
    events = derive(_state(needs=("UN-1", "UN-2")))
    assert [(e.name, e.message) for e in events] == [
        (NEED_UNADDRESSED, "user need UN-2 is addressed by no design input")]


def test_command_requires_allure_results(tmp_path: Path) -> None:
    dhf = _project(tmp_path, [("DI-1", ["UN-001"])])
    assert story_release_gate_command(dhf_dir=dhf, allure_results_dir=None) == 2
