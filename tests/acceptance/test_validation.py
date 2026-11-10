"""Acceptance test for item-01-004 (.pi/spec/specification/spec.feature).

Summative validation records: ingested per user need, with the release gate
naming every need lacking an approved record — as a warning, never a blocker
(validation is human-evidenced). Skips cleanly if allure-pytest is absent.
"""

from __future__ import annotations

import json
from pathlib import Path

import pytest

from rdm.specification.validation import parse_validation_records, unvalidated_user_needs
from rdm.release.gate import run_release_gate
from tests.util import spec_project, write_allure_result

allure = pytest.importorskip("allure")

from tests.acceptance.evidence import verification_step  # noqa: E402


def _validated_record(tmp_path: Path) -> tuple[Path, Path]:
    """A minimal passing record: two user needs of the spec, one requirement
    covering each, verified."""
    dhf = spec_project(tmp_path, (("item-01-001", "req one", ["Alarm"]), ("item-01-002", "req two", ["Log"])),
                       {"Alarm": ("core", "Nurse", "A nurse is alerted."),
                        "Log": ("core", "Nurse", "A nurse reads the log.")}, {"01": "core"})
    results = tmp_path / "allure-results"
    for item in ("item-01-001", "item-01-002"):
        write_allure_result(results, item, "passed", item)
    return dhf, results


@allure.tag("item-01-004")
@allure.label("output", "rdm/specification/validation.py")
def test_release_gate_names_unvalidated_user_needs(tmp_path: Path) -> None:
    """item-01-004: validation records are ingested per user need of the spec;
    the release gate warns (never blocks) for each need without an approved
    record."""
    dhf, results = _validated_record(tmp_path)

    with verification_step("No validation records at all: every need is named, as a warning only"):
        gate = run_release_gate(dhf, results)
        validation_warnings = [w for w in gate.warnings if "validation record" in w]
        assert any("Alarm" in w for w in validation_warnings)
        assert any("Log" in w for w in validation_warnings)
        assert gate.passed  # warnings never block

    with verification_step("An APPROVED record for Alarm clears its warning; a non-approved disposition for Log "
                           "does not"):
        validation = dhf / "validation"
        validation.mkdir()
        (validation / "alarm-validation.json").write_text(json.dumps(
            {"user_need": "Alarm", "disposition": "approved",
             "reviewer": "maintainer (summative)", "summary": "journey reviewed"}
        ))
        (validation / "log-validation.json").write_text(json.dumps(
            {"user_need": "Log", "disposition": "pending", "reviewer": "maintainer"}
        ))
        gate = run_release_gate(dhf, results)
        validation_warnings = [w for w in gate.warnings if "validation record" in w]
        assert not any("Alarm" in w for w in validation_warnings)
        assert any("Log" in w for w in validation_warnings)
        assert gate.passed

    with verification_step("The ingest itself: records keyed by user need, disposition and reviewer read"):
        records = parse_validation_records(validation)
        assert records["Alarm"]["disposition"] == "approved"
        assert records["Alarm"]["reviewer"] == "maintainer (summative)"
        assert unvalidated_user_needs(dhf) == ["Log"]

    with verification_step("An approved record that names no reviewer is no validation: a person makes it"):
        for reviewer in ({"reviewer": ""}, {}, {"reviewer": ["maintainer"]}):
            (validation / "alarm-validation.json").write_text(json.dumps(
                {"user_need": "Alarm", "disposition": "approved", **reviewer}))
            assert unvalidated_user_needs(dhf) == ["Alarm", "Log"], reviewer
