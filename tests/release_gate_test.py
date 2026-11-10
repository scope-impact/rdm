"""Tests for the release gate: full verification (hard fail).

Verification is anchored on the requirements of the spec (stamped scenarios,
item-NN-SSS): a requirement is verified when a run claiming it (an Allure tag
label) passes, and a user need no requirement covers blocks the release.
"""

from __future__ import annotations

from pathlib import Path

from rdm.evidence.allure import VERIFIED, RequirementVerification, VerificationReport
from rdm.release.gate import (
    NEED_UNADDRESSED,
    NO_REQUIREMENTS,
    NO_SPEC,
    ORPHAN_TAG,
    RELEASE_PERMITTED,
    State,
    derive,
    run_release_gate,
    story_release_gate_command,
)
from tests.util import spec_project
from tests.util import write_allure_result as _result

NEEDS = {"Alarm": ("core", "Nurse", "A nurse is alerted."), "Log": ("core", "Nurse", "A nurse sees the log.")}


def _project(tmp_path: Path) -> Path:
    return spec_project(tmp_path / "repo", (("item-01-001", "alarms", ["Alarm"]), ("item-01-002", "logs", ["Log"])),
                        NEEDS, {"01": "core"})


def test_passes_when_all_verified(tmp_path: Path) -> None:
    dhf = _project(tmp_path)
    results = tmp_path / "allure"
    _result(results, "a", "passed", "item-01-001")
    _result(results, "b", "passed", "item-01-002")
    outcome = run_release_gate(dhf, results)
    assert outcome.passed, outcome.blocking
    assert set(outcome.verified) == {"item-01-001", "item-01-002"}
    assert story_release_gate_command(dhf_dir=dhf, allure_results_dir=results) == 0


# The rules are pure: a hand-built state, no DHF on disk.

def _state(needs=None, verified=("item-01-001",), orphans=()) -> State:
    report = VerificationReport(by_id={i: RequirementVerification(i, status=VERIFIED) for i in verified},
                                orphan_ids=list(orphans))
    needs = {"Alarm": ["item-01-001"]} if needs is None else needs
    return State("dhf", [{"id": "item-01-001", "traces_to": ["Alarm"]}], needs, report)


def test_no_requirements_blocks() -> None:
    assert [e.name for e in derive(State("dhf", []))] == [NO_REQUIREMENTS]


def test_no_spec_blocks() -> None:
    assert [e.name for e in derive(State("dhf", [], spec_found=False))] == [NO_SPEC]


def test_orphan_tag_warns_and_permits() -> None:
    events = derive(_state(orphans=["item-01-777", "FT-001"]))  # FT-001 shares no prefix with an item
    assert [e.name for e in events] == [RELEASE_PERMITTED, ORPHAN_TAG]
    assert "item-01-777" in events[1].message


def test_unaddressed_need_blocks() -> None:
    events = derive(_state(needs={"Alarm": ["item-01-001"], "Log": []}))
    assert [(e.name, e.message) for e in events] == [
        (NEED_UNADDRESSED, "user need Log is addressed by no requirement covering its rules or guarantees")]


def test_command_requires_allure_results(tmp_path: Path) -> None:
    dhf = _project(tmp_path)
    assert story_release_gate_command(dhf_dir=dhf, allure_results_dir=None) == 2
